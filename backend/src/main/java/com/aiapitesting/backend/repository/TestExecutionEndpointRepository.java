package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestExecutionEndpoint;
import com.aiapitesting.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TestExecutionEndpointRepository extends JpaRepository<TestExecutionEndpoint, UUID> {

    // Toàn bộ liên kết execution<->endpoint của project (trang Lịch sử kiểm thử) - JOIN FETCH cả
    // execution lẫn endpoint để đọc được sau khi session đã đóng (spring.jpa.open-in-view=false).
    @Query("SELECT tee FROM TestExecutionEndpoint tee JOIN FETCH tee.execution JOIN FETCH tee.endpoint "
            + "WHERE tee.endpoint.project = :project ORDER BY tee.execution.startedAt ASC")
    List<TestExecutionEndpoint> findAllByEndpointProject(@Param("project") Project project);

    // Toàn bộ liên kết của MỌI project thuộc owner (trang Lịch sử tổng, Module 8) - CỐ Ý không nhận
    // tham số endpointId: otherEndpointCount phải tính từ toàn bộ endpoint 1 execution chạm tới, lọc
    // endpoint ngay ở SQL sẽ làm sai số đó. Lọc endpoint được áp ở tầng Java sau khi đã tính xong.
    @Query("SELECT tee FROM TestExecutionEndpoint tee JOIN FETCH tee.execution ex JOIN FETCH tee.endpoint ep "
            + "JOIN FETCH ep.project p "
            + "WHERE p.owner = :owner "
            + "AND (:projectId IS NULL OR p.id = :projectId) "
            + "AND (:from IS NULL OR ex.startedAt >= :from) "
            + "AND (:to IS NULL OR ex.startedAt < :to) "
            + "ORDER BY ex.startedAt DESC")
    List<TestExecutionEndpoint> findAllForHistoryFeed(
            @Param("owner") User owner, @Param("projectId") UUID projectId,
            @Param("from") Instant from, @Param("to") Instant to);

    // Dọn trước khi bulk-xoá TestExecution (doImport()/ProjectService.delete()) - tránh lỗi khoá
    // ngoại 1451, cùng loại đã xảy ra 3 lần trong dự án này.
    void deleteAllByExecutionProject(Project project);
}
