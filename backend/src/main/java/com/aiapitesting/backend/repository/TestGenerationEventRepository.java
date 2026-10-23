package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestGenerationEvent;
import com.aiapitesting.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestGenerationEventRepository extends JpaRepository<TestGenerationEvent, UUID> {

    // Module 15h/15i - dòng lịch sử mới nhất của endpoint, chứa báo cáo checklist của lần sinh gần nhất.
    Optional<TestGenerationEvent> findFirstByEndpointOrderByCreatedAtDesc(Endpoint endpoint);

    // Toàn bộ sự kiện sinh test case của project - JOIN FETCH endpoint để tránh LazyInitializationException.
    @Query("SELECT e FROM TestGenerationEvent e JOIN FETCH e.endpoint WHERE e.endpoint.project = :project")
    List<TestGenerationEvent> findAllByEndpointProject(@Param("project") Project project);

    // Toàn bộ sự kiện sinh test case của mọi project thuộc owner (trang Lịch sử tổng).
    @Query("SELECT e FROM TestGenerationEvent e JOIN FETCH e.endpoint ep JOIN FETCH ep.project p "
            + "WHERE p.owner = :owner "
            + "AND (:projectId IS NULL OR p.id = :projectId) "
            + "AND (:endpointId IS NULL OR ep.id = :endpointId) "
            + "AND (:from IS NULL OR e.createdAt >= :from) "
            + "AND (:to IS NULL OR e.createdAt < :to)")
    List<TestGenerationEvent> findAllForHistoryFeed(
            @Param("owner") User owner, @Param("projectId") UUID projectId, @Param("endpointId") UUID endpointId,
            @Param("from") Instant from, @Param("to") Instant to);

    /**
     * 17q G3.3 - TÁCH sự kiện khỏi endpoint của project (thay cho xoá): import REPLACE và xoá project từng xoá luôn sổ token nên quota ngày bị
     * đặt về 0. Giữ dòng (kèm ownerId/projectId) để quota vẫn đúng; bỏ snapshot nặng vì lịch sử đã không còn truy cập được.
     */
    @Modifying
    @Query("UPDATE TestGenerationEvent e SET e.endpoint = null, e.snapshotJson = '[]', e.planSummaryJson = null "
            + "WHERE e.endpoint IN (SELECT ep FROM Endpoint ep WHERE ep.project = :project)")
    void detachFromEndpointsOfProject(@Param("project") Project project);

    long countByEndpointProject(Project project);

    // Tổng token đã dùng của 1 owner trong [from, to) - gọi trước khi gọi AI để chặn sớm.
    @Query("SELECT COALESCE(SUM(e.totalTokens), 0) FROM TestGenerationEvent e "
            + "WHERE e.ownerId = :#{#owner.id} AND e.createdAt >= :from AND e.createdAt < :to")
    long sumTotalTokensByOwnerAndCreatedAtBetween(
            @Param("owner") User owner, @Param("from") Instant from, @Param("to") Instant to);

    // Tổng token AI toàn hệ thống (mọi user) trong [from, to) - Dashboard Admin.
    @Query("SELECT COALESCE(SUM(e.totalTokens), 0) FROM TestGenerationEvent e "
            + "WHERE e.createdAt >= :from AND e.createdAt < :to")
    long sumTotalTokensByCreatedAtBetween(@Param("from") Instant from, @Param("to") Instant to);

    // Tổng token/số lần gọi AI theo từng owner - from/to null = không giới hạn.
    @Query("SELECT e.ownerId AS ownerId, COALESCE(SUM(e.totalTokens), 0) AS totalTokens, COUNT(e) AS callCount "
            + "FROM TestGenerationEvent e "
            + "WHERE e.ownerId IN :ownerIds "
            + "AND (:from IS NULL OR e.createdAt >= :from) "
            + "AND (:to IS NULL OR e.createdAt < :to) "
            + "GROUP BY e.ownerId")
    List<OwnerAiUsage> sumUsageGroupedByOwnerIds(
            @Param("ownerIds") List<UUID> ownerIds, @Param("from") Instant from, @Param("to") Instant to);

    interface OwnerAiUsage {
        UUID getOwnerId();
        Long getTotalTokens();
        Long getCallCount();
    }

    // Biểu đồ usage theo ngày/tuần/tháng - chỉ lấy 2 cột cần, AiUsageService tự bucket theo ngày ở
    // tầng Java rồi cộng dồn thành tuần/tháng phía client.
    @Query("SELECT e.createdAt AS createdAt, e.totalTokens AS totalTokens FROM TestGenerationEvent e "
            + "WHERE e.ownerId = :#{#owner.id} AND e.createdAt >= :since ORDER BY e.createdAt ASC")
    List<UsagePoint> findUsagePointsByOwnerSince(@Param("owner") User owner, @Param("since") Instant since);

    // Bản không lọc owner - Admin xem usage toàn hệ thống.
    @Query("SELECT e.createdAt AS createdAt, e.totalTokens AS totalTokens FROM TestGenerationEvent e "
            + "WHERE e.createdAt >= :since ORDER BY e.createdAt ASC")
    List<UsagePoint> findAllUsagePointsSince(@Param("since") Instant since);

    interface UsagePoint {
        Instant getCreatedAt();
        Integer getTotalTokens();
    }
}
