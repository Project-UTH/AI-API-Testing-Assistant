package com.aiapitesting.backend.service.rule;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.GenerateTestsRequest;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.GenerateTestsResponse;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.NotGenerated;
import com.aiapitesting.backend.entity.BusinessRule;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.RuleStatus;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseAuthOverride;
import com.aiapitesting.backend.entity.TestCaseDependency;
import com.aiapitesting.backend.entity.TestCaseGenerationOrigin;
import com.aiapitesting.backend.entity.TestCaseSource;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.repository.BugReportRepository;
import com.aiapitesting.backend.repository.BusinessRuleRepository;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.TestCaseAssertionRepository;
import com.aiapitesting.backend.repository.TestCaseDependencyRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.aiapitesting.backend.service.ProjectService;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner;
import com.aiapitesting.backend.service.TestCasePathValidator;
import com.aiapitesting.backend.service.TestCaseService;
import com.aiapitesting.backend.service.execution.ExpectedStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 17q G3 - sinh test TẤT ĐỊNH (0 token) từ luật nghiệp vụ ĐÃ DUYỆT: mượn dữ liệu của test gốc ĐÃ PASS (Pass của test vi phạm chỉ có nghĩa khi
 * request gốc hợp lệ thật), sửa đúng các field của luật ({@link RuleTestGenerator}). Kỳ vọng do người duyệt chịu trách nhiệm: luật có mã thì
 * dùng mã đó, không thì "bị từ chối (4xx)". Sinh lại cho 1 luật xoá test cũ CHƯA KHOÁ của đúng luật đó.
 */
@Service
@RequiredArgsConstructor
public class RuleTestService {

    private static final int NAME_MAX = 255;

    private final ProjectService projectService;
    private final BusinessRuleRepository ruleRepository;
    private final BusinessRuleService ruleService;
    private final EndpointRepository endpointRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestCaseDependencyRepository testCaseDependencyRepository;
    private final TestCaseAssertionRepository testCaseAssertionRepository;
    private final TestResultRepository testResultRepository;
    private final BugReportRepository bugReportRepository;
    private final TestCaseService testCaseService;
    private final TestCaseChecklistPlanner planner;
    private final RuleTestGenerator generator;
    private final TestCasePathValidator testCasePathValidator;
    private final com.aiapitesting.backend.repository.GenerationJobRepository generationJobRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public GenerateTestsResponse generate(UUID projectId, GenerateTestsRequest request) {
        Project project = projectService.getOwnedProject(projectId);
        if (generationJobRepository.existsByProjectIdAndStatusIn(projectId, java.util.Set.of("QUEUED", "RUNNING", "CANCELLING"))) {
            throw new com.aiapitesting.backend.exception.ExecutionInProgressException(
                    "Project đang có job nền (sinh test hoặc trích luật) chưa xong - đợi xong hoặc huỷ job đó trước khi sinh test từ luật");
        }
        boolean explicitRules = request != null && request.ruleIds() != null && !request.ruleIds().isEmpty();
        List<BusinessRule> rules = ruleRepository.findAllByProject(project).stream()
                .filter(r -> !explicitRules || request.ruleIds().contains(r.getId()))
                .filter(r -> request == null || request.endpointIds() == null || request.endpointIds().isEmpty()
                        || (r.getEndpointId() != null && request.endpointIds().contains(r.getEndpointId())))
                .sorted(java.util.Comparator.comparing(BusinessRule::getEndpointKey).thenComparing(BusinessRule::getCreatedAt))
                .toList();

        List<NotGenerated> notGenerated = new ArrayList<>();
        List<UUID> created = new ArrayList<>();
        Map<UUID, TestResultStatus> latest = latestStatuses(project);
        Map<UUID, Endpoint> endpoints = new HashMap<>();
        Map<UUID, Baseline> baselines = new HashMap<>();

        for (BusinessRule rule : rules) {
            RuleParams params = ruleService.read(rule.getParamsJson());
            String sentence = RuleSentence.of(rule.getTemplate(), params);
            if (rule.getStatus() != RuleStatus.APPROVED) {
                if (explicitRules) {
                    notGenerated.add(new NotGenerated(rule.getId(), sentence, "Luật chưa được duyệt (trạng thái " + rule.getStatus() + ")"));
                }
                continue;
            }
            // Endpoint theo id, không có thì theo "METHOD path" (import lại đổi id endpoint nhưng luật vẫn sống).
            Endpoint endpoint = ruleService.resolveEndpoint(project, rule).orElse(null);
            if (endpoint != null) {
                endpoints.put(endpoint.getId(), endpoint);
            }
            if (endpoint == null) {
                notGenerated.add(new NotGenerated(rule.getId(), sentence, "Endpoint của luật không còn trong project"));
                continue;
            }
            TestCaseChecklistPlanner.Spec spec = planner.readSpec(endpoint);
            RuleParamsValidator.Check check = RuleParamsValidator.check(rule.getTemplate(), params, spec);
            if (check.consistency() == RuleParamsValidator.Consistency.STALE) {
                rule.setStatus(RuleStatus.STALE);
                rule.setStatusNote(check.note());
                ruleRepository.save(rule);
                notGenerated.add(new NotGenerated(rule.getId(), sentence, check.note()));
                continue;
            }
            Baseline baseline = baselines.computeIfAbsent(endpoint.getId(), id -> pickBaseline(endpoint, spec, latest));
            RuleTestGenerator.Result result = generator.generate(rule.getTemplate(), params, spec,
                    baseline == null ? null : baseline.generated(), sentence);
            if (result.drafts().isEmpty()) {
                notGenerated.add(new NotGenerated(rule.getId(), sentence, result.reason()));
                continue;
            }
            deleteOldTests(rule);
            List<TestCase> entities = result.drafts().stream().map(d -> toEntity(endpoint, rule, sentence, d, baseline)).toList();
            List<TestCase> saved = testCaseRepository.saveAll(entities);
            copyDependencies(saved, baseline);
            saved.forEach(tc -> created.add(tc.getId()));
        }
        return new GenerateTestsResponse(created.size(), created, notGenerated);
    }

    // ---- test gốc ----

    private record Baseline(TestCase entity, GeneratedTestCase generated) {
    }

    /** Test gốc = test kỳ vọng thành công, hợp lệ theo spec VÀ lần chạy mới nhất PASS (ưu tiên test gốc AI đã khoá, rồi theo thứ tự tạo). */
    private Baseline pickBaseline(Endpoint endpoint, TestCaseChecklistPlanner.Spec spec, Map<UUID, TestResultStatus> latest) {
        boolean requiresAuth = endpoint.effectiveRequiresAuth();
        return testCaseRepository.findAllByEndpointOrderByCreatedAtAsc(endpoint).stream()
                .filter(tc -> tc.getSource() != TestCaseSource.BUSINESS)
                .filter(tc -> latest.get(tc.getId()) == TestResultStatus.PASSED)
                .map(tc -> new Baseline(tc, planner.toCandidate(tc)))
                .filter(b -> planner.isUsableBaseline(spec, b.generated(), requiresAuth))
                .min(java.util.Comparator.comparingInt((Baseline b) -> b.entity().getGenerationOrigin() == TestCaseGenerationOrigin.AI_BASELINE
                        || b.entity().isLocked() ? 0 : 1))
                .orElse(null);
    }

    private Map<UUID, TestResultStatus> latestStatuses(Project project) {
        Map<UUID, TestResultStatus> latest = new HashMap<>();
        for (var row : testResultRepository.findLatestResultsByProject(project)) {
            latest.putIfAbsent(row.getTestCaseId(), row.getStatus());
        }
        return latest;
    }

    // ---- tạo / xoá ----

    private TestCase toEntity(Endpoint endpoint, BusinessRule rule, String sentence, RuleTestGenerator.Draft draft, Baseline baseline) {
        GeneratedTestCase g = draft.testCase();
        String body = g.requestBody();
        String path = g.resolvedPath();
        String headers = write(g.requestHeaders());
        if (draft.sendTwice()) {
            // {{uuid}}/{{rand}} không tên cho giá trị mới mỗi lần xuất hiện: gửi 2 lần phải ra CÙNG giá trị nên đổi thành dạng có tên.
            body = nameUniqueTokens(body);
            path = nameUniqueTokens(path);
            headers = nameUniqueTokens(headers);
        }
        Integer expected = g.expectedStatus();
        String expectedClass = null;
        if (draft.violation()) {
            if (rule.getExpectedStatus() != null) {
                expected = rule.getExpectedStatus();
            } else {
                expected = 400;
                expectedClass = ExpectedStatus.REJECTED;
            }
        }
        TestCase base = baseline == null ? null : baseline.entity();
        return TestCase.builder()
                .endpoint(endpoint)
                .name(truncate(draft.name(), NAME_MAX))
                .description(g.description())
                .requestHeaders(headers)
                .requestBody(body)
                .rationale(g.rationale())
                .expectedStatus(expected)
                .expectedStatusClass(expectedClass)
                .resolvedPath(path)
                .pathParamFallbacks(write(g.pathParamFallbacks()))
                .source(TestCaseSource.BUSINESS)
                .generationOrigin(TestCaseGenerationOrigin.RULE_TEMPLATE)
                .checklistItem(truncate("Luật: " + sentence, NAME_MAX))
                .authOverride(g.authOverride() == null ? TestCaseAuthOverride.DEFAULT : g.authOverride())
                .authProfileId(base == null ? null : base.getAuthProfileId())
                .ruleId(rule.getId())
                .sendTwice(draft.sendTwice())
                .build();
    }

    private void deleteOldTests(BusinessRule rule) {
        List<TestCase> old = testCaseRepository.findAllByRuleId(rule.getId()).stream().filter(tc -> !tc.isLocked()).toList();
        if (old.isEmpty()) {
            return;
        }
        testCaseService.ensureNoDependents(old);
        bugReportRepository.deleteAllByTestCaseIn(old);
        testResultRepository.deleteAllByTestCaseIn(old);
        testCaseDependencyRepository.deleteAllByTestCaseIn(old);
        testCaseAssertionRepository.deleteAllByTestCaseIn(old);
        testCaseRepository.deleteAll(old);
    }

    /** Test sinh ra còn token {{x}} của test gốc (Test Data Chaining) thì chép liên kết dữ liệu, nếu không lúc chạy sẽ rơi về giá trị dự phòng. */
    private void copyDependencies(List<TestCase> saved, Baseline baseline) {
        if (baseline == null) {
            return;
        }
        List<TestCaseDependency> baselineDeps = testCaseDependencyRepository.findAllByTestCase(baseline.entity());
        if (baselineDeps.isEmpty()) {
            return;
        }
        List<TestCaseDependency> copies = new ArrayList<>();
        for (TestCase tc : saved) {
            Set<String> placeholders = new HashSet<>();
            placeholders.addAll(testCasePathValidator.extractPlaceholders(tc.getResolvedPath()));
            placeholders.addAll(testCasePathValidator.extractPlaceholders(tc.getRequestBody()));
            placeholders.addAll(testCasePathValidator.extractPlaceholders(tc.getRequestHeaders()));
            for (TestCaseDependency dep : baselineDeps) {
                if (placeholders.contains(dep.getPlaceholderName())) {
                    copies.add(TestCaseDependency.builder().testCase(tc).dependsOnTestCase(dep.getDependsOnTestCase())
                            .jsonPath(dep.getJsonPath()).placeholderName(dep.getPlaceholderName()).build());
                }
            }
        }
        if (!copies.isEmpty()) {
            testCaseDependencyRepository.saveAll(copies);
        }
    }

    // ---- tiện ích ----

    static String nameUniqueTokens(String text) {
        return text == null ? null : text.replaceAll("\\{\\{(uuid|rand)}}", "{{$1:dup}}");
    }

    private String write(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map == null ? new LinkedHashMap<>() : map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    /** Đếm test đã sinh theo luật (cho danh sách luật/coverage nghiệp vụ). */
    public Map<UUID, Long> testCounts(java.util.Collection<UUID> ruleIds) {
        if (ruleIds.isEmpty()) {
            return Map.of();
        }
        return testCaseRepository.findAllByRuleIdIn(ruleIds).stream()
                .collect(Collectors.groupingBy(TestCase::getRuleId, Collectors.counting()));
    }
}
