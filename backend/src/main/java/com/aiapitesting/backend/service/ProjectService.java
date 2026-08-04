package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.request.ProjectRequest;
import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.dto.response.ProjectResponse;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.exception.ForbiddenException;
import com.aiapitesting.backend.exception.ProjectNotFoundException;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.ProjectRepository;
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
    private final CurrentUserService currentUserService;

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

    @Transactional
    public void delete(UUID id) {
        Project project = getOwnedProject(id);
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
