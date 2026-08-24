package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.request.ProjectRequest;
import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.dto.response.ProjectResponse;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TargetAuthType;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.exception.ForbiddenException;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.exception.ProjectNotFoundException;
import com.aiapitesting.backend.repository.BugReportEventRepository;
import com.aiapitesting.backend.repository.BugReportRepository;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.ProjectRepository;
import com.aiapitesting.backend.repository.TestCaseAssertionRepository;
import com.aiapitesting.backend.repository.TestCaseDependencyRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestExecutionEndpointRepository;
import com.aiapitesting.backend.repository.TestExecutionRepository;
import com.aiapitesting.backend.repository.TestGenerationEventRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.aiapitesting.backend.security.AesEncryptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final EndpointRepository endpointRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestResultRepository testResultRepository;
    private final TestExecutionRepository testExecutionRepository;
    private final TestExecutionEndpointRepository testExecutionEndpointRepository;
    private final TestCaseDependencyRepository testCaseDependencyRepository;
    private final TestCaseAssertionRepository testCaseAssertionRepository;
    private final TestGenerationEventRepository testGenerationEventRepository;
    private final BugReportRepository bugReportRepository;
    private final BugReportEventRepository bugReportEventRepository;
    private final CurrentUserService currentUserService;
    private final AesEncryptionService aesEncryptionService;

    public ProjectResponse create(ProjectRequest request) {
        User owner = currentUserService.getCurrentUser();

        Project project = Project.builder()
                .name(request.name())
                .description(request.description())
                .owner(owner)
                .build();

        return ProjectResponse.from(projectRepository.save(project));
    }

    public PageResponse<ProjectResponse> list(Pageable pageable) {
        User owner = currentUserService.getCurrentUser();
        Page<Project> page = projectRepository.findAllByOwner(owner, pageable);
        return PageResponse.from(page, ProjectResponse::from);
    }

    public ProjectResponse getById(UUID id) {
        return ProjectResponse.from(getOwnedProject(id));
    }

    public ProjectResponse update(UUID id, ProjectRequest request) {
        Project project = getOwnedProject(id);
        project.setName(request.name());
        project.setDescription(request.description());
        return ProjectResponse.from(projectRepository.save(project));
    }

    /**
     * Cấu hình xác thực gọi API thật, độc lập với lúc import (import bằng file không gọi ra ngoài
     * nên không có chỗ set auth). Khác lúc import (NONE = giữ nguyên auth cũ): ở đây NONE là hành
     * động rõ ràng muốn xoá auth, nên xoá thật.
     */
    @Transactional
    public ProjectResponse updateTargetAuth(UUID id, TargetAuthType authType, String authValue) {
        Project project = getOwnedProject(id);
        validateTargetAuthValue(authType, authValue);
        if (authType == null || authType == TargetAuthType.NONE) {
            project.setTargetAuthType(TargetAuthType.NONE);
            project.setTargetAuthValueEncrypted(null);
        } else {
            project.setTargetAuthType(authType);
            project.setTargetAuthValueEncrypted(aesEncryptionService.encrypt(authValue));
        }
        return ProjectResponse.from(projectRepository.save(project));
    }

    /** Dùng chung với EndpointImportService để tránh trùng logic validate cặp authType/authValue. */
    public void validateTargetAuthValue(TargetAuthType authType, String authValue) {
        if (authType != null && authType != TargetAuthType.NONE && (authValue == null || authValue.isBlank())) {
            throw new InvalidRequestException("Thiếu giá trị xác thực cho loại xác thực đã chọn");
        }
    }

    @Transactional
    public void delete(UUID id) {
        Project project = getOwnedProject(id);
        // Thứ tự bắt buộc để tránh vi phạm khoá ngoại: bảng tham chiếu sâu nhất xoá trước.
        bugReportRepository.deleteAllByProject(project);
        bugReportEventRepository.deleteAllByEndpointProject(project);
        testExecutionEndpointRepository.deleteAllByExecutionProject(project);
        testResultRepository.deleteAllByTestCaseEndpointProject(project);
        testExecutionRepository.deleteAllByProject(project);
        testCaseDependencyRepository.deleteAllByProject(project);
        testCaseAssertionRepository.deleteAllByTestCaseEndpointProject(project);
        testCaseRepository.deleteAllByEndpointProject(project);
        testGenerationEventRepository.deleteAllByEndpointProject(project);
        endpointRepository.deleteAllByProject(project);
        projectRepository.delete(project);
    }

    /**
     * Trả về entity Project nếu thuộc user đang đăng nhập — dùng lại bởi các service khác
     * (vd. EndpointImportService) cần thao tác trên entity thay vì DTO.
     */
    public Project getOwnedProject(UUID id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ProjectNotFoundException("Không tìm thấy project với id đã cho"));

        User currentUser = currentUserService.getCurrentUser();
        if (!project.getOwner().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("Bạn không có quyền truy cập project này");
        }

        return project;
    }
}
