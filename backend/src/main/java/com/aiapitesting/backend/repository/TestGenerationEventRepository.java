package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestGenerationEvent;
import com.aiapitesting.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TestGenerationEventRepository extends JpaRepository<TestGenerationEvent, UUID> {

    // Toàn bộ sự kiện sinh test case của project (trang Lịch sử kiểm thử) - JOIN FETCH endpoint để
    // đọc được endpoint.getId() sau khi session đã đóng (spring.jpa.open-in-view=false).
    @Query("SELECT e FROM TestGenerationEvent e JOIN FETCH e.endpoint WHERE e.endpoint.project = :project")
    List<TestGenerationEvent> findAllByEndpointProject(@Param("project") Project project);

    // Toàn bộ sự kiện sinh test case của MỌI project thuộc owner (trang Lịch sử tổng, Module 8) -
    // lọc endpointId AN TOÀN ngay ở SQL (khác TestExecutionEndpoint) vì sự kiện sinh test case không
    // có khái niệm "chạy chung endpoint khác" cần tính trên tập đầy đủ.
    @Query("SELECT e FROM TestGenerationEvent e JOIN FETCH e.endpoint ep JOIN FETCH ep.project p "
            + "WHERE p.owner = :owner "
            + "AND (:projectId IS NULL OR p.id = :projectId) "
            + "AND (:endpointId IS NULL OR ep.id = :endpointId) "
            + "AND (:from IS NULL OR e.createdAt >= :from) "
            + "AND (:to IS NULL OR e.createdAt < :to)")
    List<TestGenerationEvent> findAllForHistoryFeed(
            @Param("owner") User owner, @Param("projectId") UUID projectId, @Param("endpointId") UUID endpointId,
            @Param("from") Instant from, @Param("to") Instant to);

    void deleteAllByEndpointProject(Project project);
}
