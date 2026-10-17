package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.dto.request.CoverageFillRequest;
import com.aiapitesting.backend.dto.response.CoverageDimensionResponse;
import com.aiapitesting.backend.dto.response.CoverageFillResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseAuthOverride;
import com.aiapitesting.backend.entity.TestCaseDependency;
import com.aiapitesting.backend.entity.TestCaseGenerationOrigin;
import com.aiapitesting.backend.entity.TestCaseReliability;
import com.aiapitesting.backend.entity.TestCaseSource;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.exception.EndpointNotFoundException;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.TestCaseDependencyRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Module 15j - bù khoảng trống coverage NGAY từ trang Coverage: với mỗi mục checklist còn thiếu, copy 1
 * test case gốc hợp lệ đang có rồi sửa ĐÚNG 1 chỗ (cùng cơ chế {@code TestCaseChecklistPlanner.fill()} mà
 * 15h dùng để bù sau khi AI sinh). Tất định, 0 token, không gọi AI - phần AI viết test case vẫn là luồng
 * "Sinh Test Case" (Module 4/15h); ở đây chỉ lấp đúng mục đang thiếu mà không xoá-và-thay case đang có.
 *
 * Case bù thuộc nhóm do hệ thống quản lý (source AI_GENERATED/SECURITY, generationOrigin SYSTEM_FILLED) -
 * giống case bù lúc sinh, nên lần "Sinh Test Case" sau sẽ thay thế nếu người dùng không khoá.
 */
@Service
@RequiredArgsConstructor
public class CoverageGapFillService {

    /** Độ dài cột TestCase.checklistItem (VARCHAR(255)). */
    private static final int CHECKLIST_LABEL_MAX = 255;

    static final String NO_BASELINE_REASON = "Chưa có test case hợp lệ nào (kỳ vọng thành công, đủ field bắt buộc, không vi "
            + "phạm ràng buộc spec) để làm gốc tạo case bù - hãy sinh test case bằng AI (tick Positive) hoặc tự thêm 1 case "
            + "hợp lệ, rồi bấm Bổ sung lại";

    private final ProjectService projectService;
    private final EndpointRepository endpointRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestCaseDependencyRepository testCaseDependencyRepository;
    private final TestResultRepository testResultRepository;
    private final TestCaseChecklistPlanner testCaseChecklistPlanner;
    private final TestCaseReliabilityValidator testCaseReliabilityValidator;
    private final TestCasePathValidator testCasePathValidator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private record Candidate(TestCase entity, GeneratedTestCase generated) {
    }

    /** Case bù chờ lưu + case gốc của nó (để chép liên kết dữ liệu Module 7 còn dùng được). */
    private record Pending(TestCase entity, TestCase baseline) {
    }

    @Transactional
    public CoverageFillResponse fill(UUID projectId, UUID endpointId, CoverageFillRequest request) {
        Project project = projectService.getOwnedProject(projectId);
        Endpoint endpoint = endpointRepository.findByIdAndProject(endpointId, project)
                .orElseThrow(() -> new EndpointNotFoundException("Không tìm thấy endpoint với id đã cho"));
        boolean requiresAuth = endpoint.isRequiresAuth();
        TestCaseChecklistPlanner.Spec spec = testCaseChecklistPlanner.readSpec(endpoint);
        List<Candidate> candidates = testCaseRepository.findAllByEndpointOrderByCreatedAtAsc(endpoint).stream()
                .map(tc -> new Candidate(tc, testCaseChecklistPlanner.toCandidate(tc)))
                .toList();
        Set<String> requested = request == null || request.itemKeys() == null || request.itemKeys().isEmpty()
                ? null : new HashSet<>(request.itemKeys());

        // Chấm lại NGAY lúc bù (không tin trạng thái frontend đang hiển thị) - mục đã có case đạt thì bỏ qua,
        // tránh tạo trùng khi người dùng bấm 2 lần hoặc vừa tự thêm case ở tab khác.
        List<TestCaseChecklistPlanner.ChecklistItem> missing = testCaseChecklistPlanner.planAll(spec, requiresAuth).stream()
                .filter(item -> item.notApplicableReason() == null)
                .filter(item -> requested == null || requested.contains(TestCaseChecklistPlanner.key(item)))
                .filter(item -> candidates.stream().noneMatch(c -> testCaseChecklistPlanner.covers(spec, item, c.generated(), requiresAuth)))
                .toList();
        if (missing.isEmpty()) {
            return new CoverageFillResponse(0, List.of(), List.of(), null);
        }

        Candidate baseline = pickBaseline(project, spec, candidates, requiresAuth);
        // Security chỉ đổi cách gọi (authOverride) nên không cần body hợp lệ tuyệt đối - không có case hợp lệ
        // thì lấy tạm case đầu tiên làm gốc, cùng cách luồng sinh 15i đang làm.
        Candidate securityBaseline = baseline != null ? baseline : candidates.isEmpty() ? null : candidates.get(0);

        List<Pending> pending = new ArrayList<>();
        List<CoverageFillResponse.NotFilled> notFilled = new ArrayList<>();
        for (TestCaseChecklistPlanner.ChecklistItem item : missing) {
            String key = TestCaseChecklistPlanner.key(item);
            boolean security = isSecurity(item);
            Candidate base = security ? securityBaseline : baseline;
            if (base == null) {
                notFilled.add(new CoverageFillResponse.NotFilled(key, item.description(), NO_BASELINE_REASON));
                continue;
            }
            GeneratedTestCase filled = testCaseChecklistPlanner.fill(spec, item, base.generated());
            if (filled == null) {
                notFilled.add(new CoverageFillResponse.NotFilled(key, item.description(),
                        "Hệ thống không tạo được case đúng 1 lỗi cho mục này từ case gốc '" + base.entity().getName()
                                + "' (vd không đối chiếu được vị trí tham số path trong đường dẫn của case gốc)"));
                continue;
            }
            String fallbacksJson = writeJson(filled.pathParamFallbacks());
            try {
                testCasePathValidator.validate(filled.resolvedPath(), fallbacksJson, InvalidRequestException::new);
            } catch (InvalidRequestException e) {
                notFilled.add(new CoverageFillResponse.NotFilled(key, item.description(), e.getMessage()));
                continue;
            }
            pending.add(new Pending(toEntity(endpoint, item, filled, fallbacksJson, base.entity().getName(), security),
                    base.entity()));
        }

        List<TestCase> saved = testCaseRepository.saveAll(pending.stream().map(Pending::entity).toList());
        copyDependencies(pending);

        Candidate usedBaseline = baseline != null ? baseline : securityBaseline;
        return new CoverageFillResponse(
                saved.size(),
                saved.stream().map(tc -> new CoverageDimensionResponse.TestCaseRef(tc.getId(), tc.getName(), null)).toList(),
                notFilled,
                pending.isEmpty() || usedBaseline == null ? null : usedBaseline.entity().getName());
    }

    /**
     * Case gốc = case kỳ vọng thành công, không vi phạm ràng buộc nào. Ưu tiên case đã chạy PASS thật (dữ
     * liệu chắc chắn dùng được với hệ thống đích, vd tài khoản đăng nhập thật), rồi case người dùng đã khoá
     * hoặc tự thêm (đã được người xem lại), cuối cùng theo thứ tự tạo.
     */
    private Candidate pickBaseline(Project project, TestCaseChecklistPlanner.Spec spec, List<Candidate> candidates, boolean requiresAuth) {
        Map<UUID, TestResultStatus> latest = new HashMap<>();
        for (TestResultRepository.TestCaseLatestResult row : testResultRepository.findLatestResultsByProject(project)) {
            latest.putIfAbsent(row.getTestCaseId(), row.getStatus());
        }
        return candidates.stream()
                .filter(c -> !(requiresAuth && overridesAuth(c.generated())))
                .filter(c -> testCaseChecklistPlanner.isValidBaseline(spec, c.generated()))
                .min(Comparator.comparingInt(c -> baselineRank(c.entity(), latest)))
                .orElse(null);
    }

    private int baselineRank(TestCase testCase, Map<UUID, TestResultStatus> latest) {
        if (latest.get(testCase.getId()) == TestResultStatus.PASSED) {
            return 0;
        }
        return testCase.isLocked() || testCase.getSource() == TestCaseSource.MANUAL ? 1 : 2;
    }

    private TestCase toEntity(
            Endpoint endpoint, TestCaseChecklistPlanner.ChecklistItem item, GeneratedTestCase filled,
            String fallbacksJson, String baselineName, boolean security
    ) {
        TestCaseSource source = security ? TestCaseSource.SECURITY : TestCaseSource.AI_GENERATED;
        // Tier 1 (15b) chạy cho case bù giống mọi case khác; mục Tier 1 không nhìn thấy lý do lỗi (thiếu tham
        // số query/header bắt buộc, id không tồn tại) thì tin bộ chấm checklist - cùng quy tắc luồng sinh 15i.
        TestCaseReliabilityValidator.Result reliability = testCaseReliabilityValidator.validate(endpoint, filled, source);
        if (item.tier1CannotSee() && reliability.status() == TestCaseReliability.NEEDS_REVIEW) {
            reliability = TestCaseReliabilityValidator.Result.reliable();
        }
        return TestCase.builder()
                .endpoint(endpoint)
                .name(filled.name())
                .description("Hệ thống tự bổ sung từ trang Coverage (copy case gốc '" + baselineName + "', sửa đúng 1 chỗ) "
                        + "vì chưa có test case nào đạt mục checklist: " + item.description())
                .requestHeaders(writeJson(filled.requestHeaders()))
                .requestBody(filled.requestBody())
                .rationale(item.rationale())
                .reliabilityStatus(reliability.status())
                .reliabilityNotes(reliability.notes() == null || reliability.notes().isEmpty() ? null : writeJson(reliability.notes()))
                .expectedStatus(filled.expectedStatus())
                .resolvedPath(filled.resolvedPath())
                .pathParamFallbacks(fallbacksJson)
                .source(source)
                .generationOrigin(TestCaseGenerationOrigin.SYSTEM_FILLED)
                .checklistItem(truncate(item.description()))
                .authOverride(filled.authOverride() == null ? TestCaseAuthOverride.DEFAULT : filled.authOverride())
                .build();
    }

    /**
     * Case gốc có thể lấy giá trị thật từ case khác (Test Data Chaining, Module 7) - case bù vẫn còn token
     * {{x}} đó thì phải chép theo liên kết, nếu không lúc chạy sẽ rơi về giá trị dự phòng. Token đã bị thay
     * bằng giá trị cố định (vd id = 0 ở mục biên) thì không chép.
     */
    private void copyDependencies(List<Pending> pending) {
        Map<UUID, List<TestCaseDependency>> baselineDependencies = new HashMap<>();
        List<TestCaseDependency> copies = new ArrayList<>();
        for (Pending p : pending) {
            List<TestCaseDependency> dependencies = baselineDependencies.computeIfAbsent(
                    p.baseline().getId(), id -> testCaseDependencyRepository.findAllByTestCase(p.baseline()));
            if (dependencies.isEmpty()) {
                continue;
            }
            Set<String> placeholders = new HashSet<>();
            placeholders.addAll(testCasePathValidator.extractPlaceholders(p.entity().getResolvedPath()));
            placeholders.addAll(testCasePathValidator.extractPlaceholders(p.entity().getRequestBody()));
            placeholders.addAll(testCasePathValidator.extractPlaceholders(p.entity().getRequestHeaders()));
            for (TestCaseDependency dependency : dependencies) {
                if (placeholders.contains(dependency.getPlaceholderName())) {
                    copies.add(TestCaseDependency.builder()
                            .testCase(p.entity())
                            .dependsOnTestCase(dependency.getDependsOnTestCase())
                            .jsonPath(dependency.getJsonPath())
                            .placeholderName(dependency.getPlaceholderName())
                            .build());
                }
            }
        }
        if (!copies.isEmpty()) {
            testCaseDependencyRepository.saveAll(copies);
        }
    }

    private boolean isSecurity(TestCaseChecklistPlanner.ChecklistItem item) {
        return item.type().name().startsWith("SECURITY_");
    }

    private boolean overridesAuth(GeneratedTestCase candidate) {
        return candidate.authOverride() == TestCaseAuthOverride.NONE || candidate.authOverride() == TestCaseAuthOverride.INVALID;
    }

    private String truncate(String text) {
        return text.length() <= CHECKLIST_LABEL_MAX ? text : text.substring(0, CHECKLIST_LABEL_MAX - 3) + "...";
    }

    /** Map/List rỗng hoặc null → null (cùng quy ước lưu requestHeaders/pathParamFallbacks của luồng sinh). */
    private String writeJson(Object value) {
        if (value == null || (value instanceof Map<?, ?> map && map.isEmpty())) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
