package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.BugReport;
import com.aiapitesting.backend.entity.BugStatus;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BugReportRepository extends JpaRepository<BugReport, UUID> {

    // Số bug "Đang mở" (khác CLOSED) trên toàn bộ project user sở hữu.
    long countByProjectOwnerAndStatusNot(User owner, BugStatus status);

    // Tổng bug report của 1 user (không lọc status) - dùng ở danh sách/chi tiết user Admin.
    long countByProjectOwner(User owner);

    long countByStatusNot(BugStatus status);

    @Query("SELECT b.project.owner.id AS ownerId, COUNT(b) AS count FROM BugReport b "
            + "WHERE b.project.owner.id IN :ownerIds GROUP BY b.project.owner.id")
    List<OwnerBugReportCount> countGroupedByOwnerIds(@Param("ownerIds") List<UUID> ownerIds);

    interface OwnerBugReportCount {
        UUID getOwnerId();
        long getCount();
    }

    // JOIN FETCH đủ quan hệ lazy để tránh LazyInitializationException sau khi session đóng - 1
    // query duy nhất, không N+1 theo từng endpoint/test case.
    @Query("SELECT b FROM BugReport b "
            + "JOIN FETCH b.endpoint "
            + "JOIN FETCH b.testCase "
            + "JOIN FETCH b.reporter "
            + "JOIN FETCH b.sourceTestResult str "
            + "JOIN FETCH str.execution "
            + "WHERE b.project = :project ORDER BY b.createdAt ASC")
    List<BugReport> findAllByProject(@Param("project") Project project);

    @Query("SELECT b FROM BugReport b "
            + "JOIN FETCH b.endpoint "
            + "JOIN FETCH b.testCase "
            + "JOIN FETCH b.reporter "
            + "JOIN FETCH b.sourceTestResult str "
            + "JOIN FETCH str.execution "
            + "WHERE b.id = :id AND b.project = :project")
    Optional<BugReport> findByIdAndProject(@Param("id") UUID id, @Param("project") Project project);

    // Dùng bởi BugReportStatusService để dò bug cần tự động chuyển trạng thái khi có TestResult mới.
    List<BugReport> findAllByTestCase(TestCase testCase);

    boolean existsBySourceTestResultId(UUID sourceTestResultId);

    // Cho trang Kết quả thực thi biết dòng nào đã có Bug Report rồi.
    @Query("SELECT b FROM BugReport b WHERE b.sourceTestResult.id IN :testResultIds")
    List<BugReport> findAllBySourceTestResultIdIn(@Param("testResultIds") List<UUID> testResultIds);

    // MAX chứ không phải COUNT: sau khi xoá 1 bug ở giữa, COUNT sẽ hụt và tính ra số trùng bug còn
    // tồn tại. MAX(seqInProject) luôn lớn hơn mọi bug hiện có.
    @Query("SELECT MAX(b.seqInProject) FROM BugReport b WHERE b.project = :project")
    Optional<Integer> findMaxSeqInProjectByProject(@Param("project") Project project);

    // @Modifying bắt buộc - bulk DELETE bỏ qua persistence context, không vỡ khi 2 luồng xoá chồng
    // nhau. Gọi trước khi xoá TestCase/TestResult/Endpoint/Project liên quan.
    @Modifying
    @Query("DELETE FROM BugReport b WHERE b.project = :project")
    void deleteAllByProject(@Param("project") Project project);

    @Modifying
    @Query("DELETE FROM BugReport b WHERE b.testCase IN :testCases")
    void deleteAllByTestCaseIn(@Param("testCases") List<TestCase> testCases);
}
