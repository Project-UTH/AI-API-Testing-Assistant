package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.request.TestCaseDependencyInput;
import com.aiapitesting.backend.dto.request.TestCaseRequest;
import com.aiapitesting.backend.dto.response.TestCaseDependencyResponse;
import com.aiapitesting.backend.dto.response.TestCaseResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseDependency;
import com.aiapitesting.backend.entity.TestCaseSource;
import com.aiapitesting.backend.exception.EndpointNotFoundException;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.exception.TestCaseHasDependentsException;
import com.aiapitesting.backend.exception.TestCaseNotFoundException;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.TestCaseDependencyRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TestCaseService {

    private final ProjectService projectService;
    private final EndpointRepository endpointRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestCaseDependencyRepository testCaseDependencyRepository;
    private final TestResultRepository testResultRepository;
    private final TestCasePathValidator testCasePathValidator;

    public List<TestCaseResponse> listByProject(UUID projectId) {
        Project project = projectService.getOwnedProject(projectId);
        List<TestCase> testCases = testCaseRepository.findAllByEndpointProject(project);

        Map<UUID, List<TestCaseDependencyResponse>> dependenciesByTestCaseId =
                testCaseDependencyRepository.findAllByTestCaseEndpointProject(project).stream()
                        .collect(Collectors.groupingBy(
                                d -> d.getTestCase().getId(),
                                Collectors.mapping(TestCaseDependencyResponse::from, Collectors.toList())));

        return testCases.stream()
                .map(tc -> TestCaseResponse.from(tc, dependenciesByTestCaseId.getOrDefault(tc.getId(), List.of())))
                .toList();
    }

    @Transactional
    public TestCaseResponse create(UUID projectId, UUID endpointId, TestCaseRequest request) {
        Endpoint endpoint = getOwnedEndpoint(projectId, endpointId);
        testCasePathValidator.validate(request.resolvedPath(), request.pathParamFallbacks(), InvalidRequestException::new);

        TestCase testCase = TestCase.builder()
                .endpoint(endpoint)
                .name(request.name())
                .description(request.description())
                .requestHeaders(request.requestHeaders())
                .requestBody(request.requestBody())
                .expectedStatus(request.expectedStatus())
                .resolvedPath(request.resolvedPath())
                .pathParamFallbacks(request.pathParamFallbacks())
                .source(TestCaseSource.MANUAL)
                .build();
        testCaseRepository.save(testCase);

        List<TestCaseDependency> dependencies = saveDependencies(endpoint.getProject(), testCase, request.dependencies());
        return TestCaseResponse.from(testCase, dependencies.stream().map(TestCaseDependencyResponse::from).toList());
    }

    @Transactional
    public TestCaseResponse update(UUID projectId, UUID endpointId, UUID testCaseId, TestCaseRequest request) {
        Endpoint endpoint = getOwnedEndpoint(projectId, endpointId);
        TestCase testCase = getOwnedTestCase(endpoint, testCaseId);
        testCasePathValidator.validate(request.resolvedPath(), request.pathParamFallbacks(), InvalidRequestException::new);

        testCase.setName(request.name());
        testCase.setDescription(request.description());
        testCase.setRequestHeaders(request.requestHeaders());
        testCase.setRequestBody(request.requestBody());
        testCase.setExpectedStatus(request.expectedStatus());
        testCase.setResolvedPath(request.resolvedPath());
        testCase.setPathParamFallbacks(request.pathParamFallbacks());

        // save() trên 1 entity đã có id dùng merge() nội bộ, trả về 1 bản managed KHÁC với
        // association endpoint chưa init (không cascade MERGE) - dùng lại chính đối tượng
        // testCase đang có (endpoint đã init sẵn từ findByIdAndEndpoint) để tránh
        // LazyInitializationException khi TestCaseResponse.from() đọc endpoint.getPath()/getMethod().
        testCaseRepository.save(testCase);

        List<TestCaseDependency> dependencies = saveDependencies(endpoint.getProject(), testCase, request.dependencies());
        return TestCaseResponse.from(testCase, dependencies.stream().map(TestCaseDependencyResponse::from).toList());
    }

    @Transactional
    public void delete(UUID projectId, UUID endpointId, UUID testCaseId) {
        Endpoint endpoint = getOwnedEndpoint(projectId, endpointId);
        TestCase testCase = getOwnedTestCase(endpoint, testCaseId);
        ensureNoDependents(List.of(testCase));
        testResultRepository.deleteAllByTestCase(testCase);
        testCaseDependencyRepository.deleteAllByTestCase(testCase);
        testCaseRepository.delete(testCase);
    }

    /**
     * Chặn xoá/regenerate nếu còn TestCaseDependency khác trỏ vào (các) test case sắp xoá - dùng
     * lại ở cả đây (xoá tay) và TestCaseGenerationService.generate() (regenerate).
     */
    public void ensureNoDependents(List<TestCase> testCasesAboutToDelete) {
        List<UUID> ids = testCasesAboutToDelete.stream().map(TestCase::getId).toList();
        if (ids.isEmpty()) {
            return;
        }
        List<TestCaseDependency> dependents = testCaseDependencyRepository.findAllByDependsOnTestCaseIdIn(ids);
        if (!dependents.isEmpty()) {
            String names = dependents.stream()
                    .map(d -> d.getTestCase().getName())
                    .distinct()
                    .collect(Collectors.joining(", "));
            throw new TestCaseHasDependentsException(
                    "Không thể xoá vì đang có test case khác phụ thuộc dữ liệu từ test case này: " + names);
        }
    }

    /**
     * Xoá hết dependency cũ rồi tạo lại toàn bộ theo request (đúng pattern "xoá rồi tạo lại" đang
     * dùng ở EndpointImportService/regenerate). Validate: nguồn phải thuộc cùng project (tránh tham
     * chiếu chéo project khác), placeholderName phải khớp đúng 1 token {{}} có mặt trong
     * resolvedPath/requestBody/requestHeaders của chính test case đang lưu.
     */
    private List<TestCaseDependency> saveDependencies(Project project, TestCase testCase, List<TestCaseDependencyInput> inputs) {
        testCaseDependencyRepository.deleteAllByTestCase(testCase);
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }

        Set<String> placeholders = testCasePathValidator.extractPlaceholders(testCase.getResolvedPath());
        placeholders.addAll(testCasePathValidator.extractPlaceholders(testCase.getRequestBody()));
        placeholders.addAll(testCasePathValidator.extractPlaceholders(testCase.getRequestHeaders()));

        Map<UUID, TestCase> ownedTestCasesById = new HashMap<>();
        List<TestCaseDependency> toSave = new ArrayList<>();
        for (TestCaseDependencyInput input : inputs) {
            if (!placeholders.contains(input.placeholderName())) {
                throw new InvalidRequestException(
                        "Tên placeholder '" + input.placeholderName() + "' không khớp token {{}} nào trong test case này");
            }
            TestCase dependsOn = ownedTestCasesById.computeIfAbsent(input.dependsOnTestCaseId(),
                    id -> testCaseRepository.findById(id)
                            .filter(tc -> tc.getEndpoint().getProject().getId().equals(project.getId()))
                            .orElseThrow(() -> new InvalidRequestException("Không tìm thấy test case nguồn với id đã cho trong project này")));

            toSave.add(TestCaseDependency.builder()
                    .testCase(testCase)
                    .dependsOnTestCase(dependsOn)
                    .jsonPath(input.jsonPath())
                    .placeholderName(input.placeholderName())
                    .build());
        }
        return testCaseDependencyRepository.saveAll(toSave);
    }

    private Endpoint getOwnedEndpoint(UUID projectId, UUID endpointId) {
        Project project = projectService.getOwnedProject(projectId);
        return endpointRepository.findByIdAndProject(endpointId, project)
                .orElseThrow(() -> new EndpointNotFoundException("Không tìm thấy endpoint với id đã cho"));
    }

    private TestCase getOwnedTestCase(Endpoint endpoint, UUID testCaseId) {
        return testCaseRepository.findByIdAndEndpoint(testCaseId, endpoint)
                .orElseThrow(() -> new TestCaseNotFoundException("Không tìm thấy test case với id đã cho"));
    }
}
