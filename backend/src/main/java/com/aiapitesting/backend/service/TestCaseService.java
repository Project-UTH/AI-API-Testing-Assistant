package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.request.TestCaseRequest;
import com.aiapitesting.backend.dto.response.TestCaseResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseSource;
import com.aiapitesting.backend.exception.EndpointNotFoundException;
import com.aiapitesting.backend.exception.TestCaseNotFoundException;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TestCaseService {

    private final ProjectService projectService;
    private final EndpointRepository endpointRepository;
    private final TestCaseRepository testCaseRepository;

    public List<TestCaseResponse> listByProject(UUID projectId) {
        Project project = projectService.getOwnedProject(projectId);
        return testCaseRepository.findAllByEndpointProject(project).stream()
                .map(TestCaseResponse::from)
                .toList();
    }

    public TestCaseResponse create(UUID projectId, UUID endpointId, TestCaseRequest request) {
        Endpoint endpoint = getOwnedEndpoint(projectId, endpointId);

        TestCase testCase = TestCase.builder()
                .endpoint(endpoint)
                .name(request.name())
                .description(request.description())
                .requestHeaders(request.requestHeaders())
                .requestBody(request.requestBody())
                .expectedStatus(request.expectedStatus())
                .source(TestCaseSource.MANUAL)
                .build();

        return TestCaseResponse.from(testCaseRepository.save(testCase));
    }

    public TestCaseResponse update(UUID projectId, UUID endpointId, UUID testCaseId, TestCaseRequest request) {
        Endpoint endpoint = getOwnedEndpoint(projectId, endpointId);
        TestCase testCase = getOwnedTestCase(endpoint, testCaseId);

        testCase.setName(request.name());
        testCase.setDescription(request.description());
        testCase.setRequestHeaders(request.requestHeaders());
        testCase.setRequestBody(request.requestBody());
        testCase.setExpectedStatus(request.expectedStatus());

        // save() trên 1 entity đã có id dùng merge() nội bộ, trả về 1 bản managed KHÁC với
        // association endpoint chưa init (không cascade MERGE) - dùng lại chính đối tượng
        // testCase đang có (endpoint đã init sẵn từ findByIdAndEndpoint) để tránh
        // LazyInitializationException khi TestCaseResponse.from() đọc endpoint.getPath()/getMethod().
        testCaseRepository.save(testCase);
        return TestCaseResponse.from(testCase);
    }

    public void delete(UUID projectId, UUID endpointId, UUID testCaseId) {
        Endpoint endpoint = getOwnedEndpoint(projectId, endpointId);
        TestCase testCase = getOwnedTestCase(endpoint, testCaseId);
        testCaseRepository.delete(testCase);
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
