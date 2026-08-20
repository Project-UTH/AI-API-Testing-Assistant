package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.BugReportEvent;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BugReportEventRepository extends JpaRepository<BugReportEvent, UUID> {

    // Toàn bộ sự kiện tạo/xoá Bug Report của MỌI project thuộc owner (trang Lịch sử tổng, Module 8) -
    // cùng công thức lọc với TestGenerationEventRepository.findAllForHistoryFeed.
    @Query("SELECT e FROM BugReportEvent e JOIN FETCH e.endpoint ep JOIN FETCH ep.project p "
            + "WHERE p.owner = :owner "
            + "AND (:projectId IS NULL OR p.id = :projectId) "
            + "AND (:endpointId IS NULL OR ep.id = :endpointId) "
            + "AND (:from IS NULL OR e.occurredAt >= :from) "
            + "AND (:to IS NULL OR e.occurredAt < :to)")
    List<BugReportEvent> findAllForHistoryFeed(
            @Param("owner") User owner, @Param("projectId") UUID projectId, @Param("endpointId") UUID endpointId,
            @Param("from") Instant from, @Param("to") Instant to);

    void deleteAllByEndpointProject(Project project);
}
