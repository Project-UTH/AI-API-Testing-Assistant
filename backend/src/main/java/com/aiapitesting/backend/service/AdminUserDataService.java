package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.response.BugReportPageResponse;
import com.aiapitesting.backend.dto.response.EndpointResponse;
import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.dto.response.ProjectResponse;
import com.aiapitesting.backend.dto.response.TestCaseResponse;
import com.aiapitesting.backend.dto.response.TestResultHistoryItemResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.exception.ProjectNotFoundException;
import com.aiapitesting.backend.exception.UserNotFoundException;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.ProjectRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestCaseRepository.EndpointTestCaseCount;
import com.aiapitesting.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cho admin xem (CHỈ ĐỌC) dữ liệu Project/Endpoint/TestCase của 1 user CỤ THỂ khác - phục vụ hỗ
 * trợ/điều tra (Module 11). Cố ý KHÔNG tái dùng ProjectService/EndpointImportService/TestCaseService
 * (những service đó luôn ràng buộc theo CurrentUserService, tức "user đang đăng nhập") - ở đây
 * đích đến là 1 user KHÁC do admin chỉ định qua path {userId}, nên tự truy vấn thẳng repository.
 * Không có method ghi nào - đây là 1 quyết định kiến trúc cố ý (xem skill api-contract mục 4d):
 * ownership check của các endpoint /api/v1/projects/** thường vẫn luôn theo owner đang đăng nhập,
 * kể cả khi người gọi là ADMIN - đường ghi dữ liệu hộ user khác không thuộc phạm vi hệ thống này.
 */
@Service
@RequiredArgsConstructor
public class AdminUserDataService {

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final EndpointRepository endpointRepository;
    private final TestCaseRepository testCaseRepository;
    private final BugReportService bugReportService;

    public PageResponse<ProjectResponse> listProjects(UUID userId, Pageable pageable) {
        User owner = getUser(userId);
        Page<Project> page = projectRepository.findAllByOwner(owner, pageable);
        return PageResponse.from(page, ProjectResponse::from);
    }

    public ProjectResponse getProject(UUID userId, UUID projectId) {
        return ProjectResponse.from(getOwnedProject(userId, projectId));
    }

    public PageResponse<EndpointResponse> listEndpoints(UUID userId, UUID projectId, Pageable pageable) {
        Project project = getOwnedProject(userId, projectId);
        Page<Endpoint> page = endpointRepository.findAllByProject(project, pageable);

        List<UUID> endpointIds = page.getContent().stream().map(Endpoint::getId).toList();
        Map<UUID, Long> testCaseCountByEndpointId = testCaseRepository.countByEndpointIds(endpointIds).stream()
                .collect(Collectors.toMap(EndpointTestCaseCount::getEndpointId, EndpointTestCaseCount::getCount));

        return PageResponse.from(page, endpoint ->
                EndpointResponse.from(endpoint, testCaseCountByEndpointId.getOrDefault(endpoint.getId(), 0L)));
    }

    public List<TestCaseResponse> listTestCases(UUID userId, UUID projectId) {
        Project project = getOwnedProject(userId, projectId);
        return testCaseRepository.findAllByEndpointProject(project).stream()
                .map(TestCaseResponse::from)
                .toList();
    }

    public BugReportPageResponse getBugReports(UUID userId, UUID projectId) {
        Project project = getOwnedProject(userId, projectId);
        return bugReportService.getBugReportsForProject(project);
    }

    public List<TestResultHistoryItemResponse> getRunHistory(UUID userId, UUID projectId, UUID testCaseId) {
        Project project = getOwnedProject(userId, projectId);
        return bugReportService.getRunHistoryForProject(project, testCaseId);
    }

    private Project getOwnedProject(UUID userId, UUID projectId) {
        User owner = getUser(userId);
        return projectRepository.findByIdAndOwner(projectId, owner)
                .orElseThrow(() -> new ProjectNotFoundException("Không tìm thấy project với id đã cho"));
    }

    private User getUser(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("Không tìm thấy người dùng với id đã cho"));
    }
}
