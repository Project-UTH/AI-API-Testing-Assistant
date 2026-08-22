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

    // Quota AI/ngày (Module 11) - tổng token đã dùng của 1 owner trong khoảng [from, to) - TestCaseGenerationService
    // gọi TRƯỚC khi gọi AI để chặn sớm, không tốn thêm lệnh gọi AI nào nếu đã vượt quota.
    @Query("SELECT COALESCE(SUM(e.totalTokens), 0) FROM TestGenerationEvent e "
            + "WHERE e.endpoint.project.owner = :owner AND e.createdAt >= :from AND e.createdAt < :to")
    long sumTotalTokensByOwnerAndCreatedAtBetween(
            @Param("owner") User owner, @Param("from") Instant from, @Param("to") Instant to);

    // Dashboard Admin (Module 11) - tổng token AI TOÀN HỆ THỐNG (mọi user) trong khoảng [from, to).
    @Query("SELECT COALESCE(SUM(e.totalTokens), 0) FROM TestGenerationEvent e "
            + "WHERE e.createdAt >= :from AND e.createdAt < :to")
    long sumTotalTokensByCreatedAtBetween(@Param("from") Instant from, @Param("to") Instant to);

    // Trang Admin (Module 11) - tổng token/số lần gọi AI theo TỪNG owner trong 1 trang user, gộp 1
    // query GROUP BY (đúng pattern countGroupedByOwnerIds đã dùng cho Project/TestCase/BugReport ở
    // AdminUserService) - from/to null = không giới hạn (toàn thời gian).
    @Query("SELECT ep.project.owner.id AS ownerId, COALESCE(SUM(e.totalTokens), 0) AS totalTokens, COUNT(e) AS callCount "
            + "FROM TestGenerationEvent e JOIN e.endpoint ep "
            + "WHERE ep.project.owner.id IN :ownerIds "
            + "AND (:from IS NULL OR e.createdAt >= :from) "
            + "AND (:to IS NULL OR e.createdAt < :to) "
            + "GROUP BY ep.project.owner.id")
    List<OwnerAiUsage> sumUsageGroupedByOwnerIds(
            @Param("ownerIds") List<UUID> ownerIds, @Param("from") Instant from, @Param("to") Instant to);

    interface OwnerAiUsage {
        UUID getOwnerId();
        Long getTotalTokens();
        Long getCallCount();
    }

    // Biểu đồ usage theo ngày/tuần/tháng (Module 11 - trang Tổng quan user + Admin). Chỉ lấy đúng 2
    // cột cần để gộp theo ngày Ở TẦNG JAVA (cùng cách tiếp cận "gộp trong Java" đã dùng ở
    // TestHistoryService/HistoryFeedService, tránh phải viết hàm ngày/tuần/tháng theo phương ngữ SQL
    // của MySQL) - AiUsageService tự bucket theo ngày rồi cộng dồn thành tuần/tháng phía client.
    @Query("SELECT e.createdAt AS createdAt, e.totalTokens AS totalTokens FROM TestGenerationEvent e "
            + "WHERE e.endpoint.project.owner = :owner AND e.createdAt >= :since ORDER BY e.createdAt ASC")
    List<UsagePoint> findUsagePointsByOwnerSince(@Param("owner") User owner, @Param("since") Instant since);

    // Bản KHÔNG lọc owner - Admin xem usage TOÀN HỆ THỐNG (mọi user gộp lại).
    @Query("SELECT e.createdAt AS createdAt, e.totalTokens AS totalTokens FROM TestGenerationEvent e "
            + "WHERE e.createdAt >= :since ORDER BY e.createdAt ASC")
    List<UsagePoint> findAllUsagePointsSince(@Param("since") Instant since);

    interface UsagePoint {
        Instant getCreatedAt();
        Integer getTotalTokens();
    }
}
