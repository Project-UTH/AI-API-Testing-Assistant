package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.request.TestCaseAssertionInput;
import com.aiapitesting.backend.dto.request.TestCaseDependencyInput;
import com.aiapitesting.backend.dto.request.TestCaseRequest;
import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.dto.response.TestCaseAssertionResponse;
import com.aiapitesting.backend.dto.response.TestCaseDependencyResponse;
import com.aiapitesting.backend.dto.response.TestCaseResponse;
import com.aiapitesting.backend.entity.AssertionOperator;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseAssertion;
import com.aiapitesting.backend.entity.TestCaseAuthOverride;
import com.aiapitesting.backend.entity.TestCaseDependency;
import com.aiapitesting.backend.entity.TestCaseSource;
import com.aiapitesting.backend.exception.EndpointNotFoundException;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.exception.TestCaseHasDependentsException;
import com.aiapitesting.backend.exception.TestCaseNotFoundException;
import com.aiapitesting.backend.repository.BugReportRepository;
import com.aiapitesting.backend.repository.AuthProfileRepository;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.TestCaseAssertionRepository;
import com.aiapitesting.backend.repository.TestCaseDependencyRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestCaseSpecifications;
import com.aiapitesting.backend.repository.TestResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
    private final TestCaseAssertionRepository testCaseAssertionRepository;
    private final TestResultRepository testResultRepository;
    private final BugReportRepository bugReportRepository;
    private final TestCasePathValidator testCasePathValidator;
    private final AuthProfileRepository authProfileRepository;

    public List<TestCaseResponse> listByProject(UUID projectId) {
        Project project = projectService.getOwnedProject(projectId);
        List<TestCase> testCases = testCaseRepository.findAllByEndpointProject(project);

        Map<UUID, List<TestCaseDependencyResponse>> dependenciesByTestCaseId =
                testCaseDependencyRepository.findAllByTestCaseEndpointProject(project).stream()
                        .collect(Collectors.groupingBy(
                                d -> d.getTestCase().getId(),
                                Collectors.mapping(TestCaseDependencyResponse::from, Collectors.toList())));

        Map<UUID, List<TestCaseAssertionResponse>> assertionsByTestCaseId =
                testCaseAssertionRepository.findAllByTestCaseEndpointProject(project).stream()
                        .collect(Collectors.groupingBy(
                                a -> a.getTestCase().getId(),
                                Collectors.mapping(TestCaseAssertionResponse::from, Collectors.toList())));

        return testCases.stream()
                .map(tc -> TestCaseResponse.from(
                        tc,
                        dependenciesByTestCaseId.getOrDefault(tc.getId(), List.of()),
                        assertionsByTestCaseId.getOrDefault(tc.getId(), List.of())))
                .toList();
    }

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_QUERY_LENGTH = 100;
    private static final Map<String, TestCaseSpecifications.Sort> SORTS = Map.of(
            "endpoint", TestCaseSpecifications.Sort.ENDPOINT,
            "createdAt,asc", TestCaseSpecifications.Sort.CREATED_ASC,
            "createdAt,desc", TestCaseSpecifications.Sort.CREATED_DESC,
            "name,asc", TestCaseSpecifications.Sort.NAME_ASC,
            "name,desc", TestCaseSpecifications.Sort.NAME_DESC);

    /**
     * Danh sách theo trang (Module 17 G4): lọc/sắp xếp ở DB, chỉ nạp phụ thuộc + assertion của đúng các dòng trong trang.
     * {@link #listByProject} giữ nguyên cho các nơi cần toàn bộ danh sách.
     */
    @Transactional(readOnly = true)
    public PageResponse<TestCaseResponse> listByProjectPaged(UUID projectId, TestCaseListQuery query) {
        Project project = projectService.getOwnedProject(projectId);
        PagingSupport.Paging paging = PagingSupport.resolve(query.page(), query.size(), DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        String sortKey = PagingSupport.requireAllowedSort(query.sort(), "endpoint", SORTS.keySet());
        String method = PagingSupport.normalizeMethod(query.method());
        TestCaseSpecifications.Filter filter = new TestCaseSpecifications.Filter(
                PagingSupport.normalizeQuery(query.q(), MAX_QUERY_LENGTH), method, query.endpointId(), query.source(),
                query.generationOrigin(), query.locked(), query.reliability(), SORTS.get(sortKey));

        Page<TestCase> page = testCaseRepository.findAll(
                TestCaseSpecifications.forProject(project, filter), PageRequest.of(paging.page(), paging.size()));

        List<UUID> ids = page.getContent().stream().map(TestCase::getId).toList();
        Map<UUID, List<TestCaseDependencyResponse>> dependenciesByTestCaseId = new HashMap<>();
        Map<UUID, List<TestCaseAssertionResponse>> assertionsByTestCaseId = new HashMap<>();
        if (!ids.isEmpty()) {
            testCaseDependencyRepository.findAllByTestCaseIdIn(ids).forEach(d -> dependenciesByTestCaseId
                    .computeIfAbsent(d.getTestCase().getId(), k -> new ArrayList<>()).add(TestCaseDependencyResponse.from(d)));
            testCaseAssertionRepository.findAllByTestCaseIdIn(ids).forEach(a -> assertionsByTestCaseId
                    .computeIfAbsent(a.getTestCase().getId(), k -> new ArrayList<>()).add(TestCaseAssertionResponse.from(a)));
        }
        return PageResponse.from(page, tc -> TestCaseResponse.from(
                tc,
                dependenciesByTestCaseId.getOrDefault(tc.getId(), List.of()),
                assertionsByTestCaseId.getOrDefault(tc.getId(), List.of())));
    }

    @Transactional
    public TestCaseResponse create(UUID projectId, UUID endpointId, TestCaseRequest request) {
        Endpoint endpoint = getOwnedEndpoint(projectId, endpointId);
        testCasePathValidator.validate(request.resolvedPath(), request.pathParamFallbacks(), InvalidRequestException::new);
        testCasePathValidator.validateDynamicTokens(InvalidRequestException::new, request.resolvedPath(), request.requestBody(),
                request.requestHeaders());

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
                .authOverride(request.authOverride() == null ? TestCaseAuthOverride.DEFAULT : request.authOverride())
                .authProfileId(validatedProfileId(endpoint.getProject(), request.authProfileId()))
                .build();
        testCaseRepository.save(testCase);

        List<TestCaseDependency> dependencies = saveDependencies(endpoint.getProject(), testCase, request.dependencies());
        List<TestCaseAssertion> assertions = saveAssertions(testCase, request.assertions());
        return TestCaseResponse.from(
                testCase,
                dependencies.stream().map(TestCaseDependencyResponse::from).toList(),
                assertions.stream().map(TestCaseAssertionResponse::from).toList());
    }

    @Transactional
    public TestCaseResponse update(UUID projectId, UUID endpointId, UUID testCaseId, TestCaseRequest request) {
        Endpoint endpoint = getOwnedEndpoint(projectId, endpointId);
        TestCase testCase = getOwnedTestCase(endpoint, testCaseId);
        testCasePathValidator.validate(request.resolvedPath(), request.pathParamFallbacks(), InvalidRequestException::new);
        testCasePathValidator.validateDynamicTokens(InvalidRequestException::new, request.resolvedPath(), request.requestBody(),
                request.requestHeaders());

        testCase.setName(request.name());
        testCase.setDescription(request.description());
        testCase.setRequestHeaders(request.requestHeaders());
        testCase.setRequestBody(request.requestBody());
        if (!java.util.Objects.equals(testCase.getExpectedStatus(), request.expectedStatus())) {
            // 17q G1 - người dùng tự đặt lại kỳ vọng: ghi chú "hệ thống đã đổi" không còn đúng.
            testCase.setOriginalExpectedStatus(null);
            testCase.setExpectationNote(null);
            testCase.setExpectedStatusClass(null); // người dùng đặt mã cụ thể: không còn là "bị từ chối bất kỳ 4xx"
        }
        testCase.setExpectedStatus(request.expectedStatus());
        testCase.setResolvedPath(request.resolvedPath());
        testCase.setPathParamFallbacks(request.pathParamFallbacks());
        testCase.setAuthOverride(request.authOverride() == null ? TestCaseAuthOverride.DEFAULT : request.authOverride());
        testCase.setAuthProfileId(validatedProfileId(endpoint.getProject(), request.authProfileId()));

        // save() trên entity đã có id dùng merge() nội bộ, trả về bản managed khác với association
        // endpoint chưa init - dùng lại testCase đang có (endpoint đã init sẵn) để tránh
        // LazyInitializationException.
        testCaseRepository.save(testCase);

        List<TestCaseDependency> dependencies = saveDependencies(endpoint.getProject(), testCase, request.dependencies());
        List<TestCaseAssertion> assertions = saveAssertions(testCase, request.assertions());
        return TestCaseResponse.from(
                testCase,
                dependencies.stream().map(TestCaseDependencyResponse::from).toList(),
                assertions.stream().map(TestCaseAssertionResponse::from).toList());
    }

    /** Profile xác thực phải thuộc ĐÚNG project của endpoint - không cho test case của project này dùng token của project khác. */
    private UUID validatedProfileId(Project project, UUID authProfileId) {
        if (authProfileId == null) {
            return null;
        }
        return authProfileRepository.findByIdAndProject(authProfileId, project)
                .orElseThrow(() -> new InvalidRequestException("Profile xác thực không tồn tại trong project này"))
                .getId();
    }

    /** Bật/tắt khoá 1 test case - test case đang khoá không bị xoá khi "Sinh Test Case" xoá-và-thay. */
    @Transactional
    public TestCaseResponse setLocked(UUID projectId, UUID endpointId, UUID testCaseId, boolean locked) {
        Endpoint endpoint = getOwnedEndpoint(projectId, endpointId);
        TestCase testCase = getOwnedTestCase(endpoint, testCaseId);
        testCase.setLocked(locked);
        testCaseRepository.save(testCase);
        return TestCaseResponse.from(testCase);
    }

    @Transactional
    public void delete(UUID projectId, UUID endpointId, UUID testCaseId) {
        Endpoint endpoint = getOwnedEndpoint(projectId, endpointId);
        TestCase testCase = getOwnedTestCase(endpoint, testCaseId);
        ensureNoDependents(List.of(testCase));
        bugReportRepository.deleteAllByTestCaseIn(List.of(testCase));
        testResultRepository.deleteAllByTestCase(testCase);
        testCaseDependencyRepository.deleteAllByTestCase(testCase);
        testCaseAssertionRepository.deleteAllByTestCase(testCase);
        testCaseRepository.delete(testCase);
    }

    /** Chặn xoá/regenerate nếu còn TestCaseDependency khác trỏ vào test case sắp xoá. */
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
     * Xoá hết dependency cũ rồi tạo lại toàn bộ theo request. Validate: nguồn phải thuộc cùng
     * project, placeholderName phải khớp 1 token {{}} có mặt trong resolvedPath/body/headers.
     */
    private List<TestCaseDependency> saveDependencies(Project project, TestCase testCase, List<TestCaseDependencyInput> inputs) {
        testCaseDependencyRepository.deleteAllByTestCase(testCase);
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }

        Set<String> placeholders = testCasePathValidator.extractPlaceholders(testCase.getResolvedPath());
        placeholders.addAll(testCasePathValidator.extractPlaceholders(testCase.getRequestBody()));
        placeholders.addAll(testCasePathValidator.extractPlaceholders(testCase.getRequestHeaders()));

        Set<String> seenPlaceholders = new HashSet<>();
        Map<UUID, TestCase> ownedTestCasesById = new HashMap<>();
        List<TestCaseDependency> toSave = new ArrayList<>();
        for (TestCaseDependencyInput input : inputs) {
            if (!placeholders.contains(input.placeholderName())) {
                throw new InvalidRequestException(
                        "Tên placeholder '" + input.placeholderName() + "' không khớp token {{}} nào trong test case này");
            }
            // Ràng buộc DB chỉ cho phép đúng 1 dependency mỗi placeholder - chặn sớm để trả lỗi rõ ràng.
            if (!seenPlaceholders.add(input.placeholderName())) {
                throw new InvalidRequestException(
                        "Placeholder '" + input.placeholderName() + "' bị gán trùng nhiều hơn 1 dependency");
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

    /**
     * Xoá hết assertion cũ rồi tạo lại toàn bộ theo request. expectedValue bắt buộc trừ khi
     * operator=EXISTS (chỉ cần kiểm tra field có mặt).
     */
    private List<TestCaseAssertion> saveAssertions(TestCase testCase, List<TestCaseAssertionInput> inputs) {
        testCaseAssertionRepository.deleteAllByTestCase(testCase);
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }

        List<TestCaseAssertion> toSave = new ArrayList<>();
        for (TestCaseAssertionInput input : inputs) {
            if (input.operator() != AssertionOperator.EXISTS
                    && (input.expectedValue() == null || input.expectedValue().isBlank())) {
                throw new InvalidRequestException(
                        "Assertion với operator '" + input.operator() + "' bắt buộc phải có expectedValue");
            }
            toSave.add(TestCaseAssertion.builder()
                    .testCase(testCase)
                    .jsonPath(input.jsonPath())
                    .operator(input.operator())
                    .expectedValue(input.expectedValue())
                    .build());
        }
        return testCaseAssertionRepository.saveAll(toSave);
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
