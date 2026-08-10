package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {
    Page<Endpoint> findAllByProject(Project project, Pageable pageable);

    void deleteAllByProject(Project project);

    Optional<Endpoint> findByIdAndProject(UUID id, Project project);

    // Dò endpoint "tạo resource" cùng path gốc - dùng cho gợi ý liên kết tự động (Module 7).
    Optional<Endpoint> findByProjectAndPathAndMethod(Project project, String path, String method);
}
