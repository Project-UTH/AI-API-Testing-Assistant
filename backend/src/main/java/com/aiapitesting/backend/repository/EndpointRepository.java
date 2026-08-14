package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {
    Page<Endpoint> findAllByProject(Project project, Pageable pageable);

    // Trang Tổng quan (Module 8) - tổng endpoint của toàn bộ project user sở hữu.
    long countByProjectOwner(User owner);

    // Bản không phân trang - trang Lịch sử kiểm thử (Module 8) cần load hết endpoint của project.
    List<Endpoint> findAllByProjectOrderByCreatedAtAsc(Project project);

    void deleteAllByProject(Project project);

    Optional<Endpoint> findByIdAndProject(UUID id, Project project);

    // Dò endpoint "tạo resource" cùng path gốc - dùng cho gợi ý liên kết tự động (Module 7).
    Optional<Endpoint> findByProjectAndPathAndMethod(Project project, String path, String method);
}
