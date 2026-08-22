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

    // Trang Tổng quan (Module 8/11) - số bug "Đang mở" (khác CLOSED) trên TOÀN BỘ project user sở
    // hữu, cùng định nghĩa "open" với buildDashboardSummary() ở BugReportService (totalCount - closed).
    long countByProjectOwnerAndStatusNot(User owner, BugStatus status);

    // Trang Admin (Module 11) - tổng bug report của 1 user (không lọc status), dùng ở danh sách/chi
    // tiết user. Bản KHÔNG giới hạn owner (khác 2 method trên) cho Dashboard hệ thống.
    long countByProjectOwner(User owner);

    long countByStatusNot(BugStatus status);

    @Query("SELECT b.project.owner.id AS ownerId, COUNT(b) AS count FROM BugReport b "
            + "WHERE b.project.owner.id IN :ownerIds GROUP BY b.project.owner.id")
    List<OwnerBugReportCount> countGroupedByOwnerIds(@Param("ownerIds") List<UUID> ownerIds);

    interface OwnerBugReportCount {
        UUID getOwnerId();
        long getCount();
    }

    // JOIN FETCH đủ quan hệ lazy sẽ đọc tới ở BugReportResponse/gộp-theo-endpoint (BugReportService)
    // để tránh LazyInitializationException sau khi session đóng (spring.jpa.open-in-view=false) -
    // 1 query duy nhất cho cả Dashboard lẫn cấu trúc lồng 3 tầng, không N+1 theo từng endpoint/test case.
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

    // Dùng bởi TestExecutionService.getExecution() để hiện rõ dòng nào ở trang Kết quả thực thi đã
    // có Bug Report rồi (không JOIN FETCH gì thêm - chỉ đọc id/bugId/sourceTestResult.id, đều là cột
    // trực tiếp hoặc id trên proxy LAZY, không cần initialize đầy đủ association).
    @Query("SELECT b FROM BugReport b WHERE b.sourceTestResult.id IN :testResultIds")
    List<BugReport> findAllBySourceTestResultIdIn(@Param("testResultIds") List<UUID> testResultIds);

    // MAX chứ không phải COUNT: sau khi xoá 1 bug ở giữa (VD B1_002), COUNT sẽ hụt và tính lại đúng
    // seqInProject của 1 bug CÒN TỒN TẠI (VD count=3 -> "B1_004" trùng bug có sẵn) -> DataIntegrityViolationException.
    // MAX(seqInProject) luôn ra số lớn hơn mọi bug hiện có, kể cả sau khi xoá ở giữa.
    @Query("SELECT MAX(b.seqInProject) FROM BugReport b WHERE b.project = :project")
    Optional<Integer> findMaxSeqInProjectByProject(@Param("project") Project project);

    /**
     * @Modifying bắt buộc (đúng convention TestResultRepository.deleteAllByTestCaseIn/
     * TestCaseDependencyRepository) - bulk DELETE bỏ qua persistence context, không vỡ khi 2 luồng
     * xoá chồng nhau. Gọi TRƯỚC khi xoá TestCase/TestResult/Endpoint/Project liên quan ở mọi chuỗi
     * xoá cascade hiện có (ProjectService.delete(), EndpointImportService.doImport(),
     * TestCaseService.delete()) - BugReport tham chiếu cả 4 bảng đó, xoá sau sẽ vỡ khoá ngoại 1451.
     */
    @Modifying
    @Query("DELETE FROM BugReport b WHERE b.project = :project")
    void deleteAllByProject(@Param("project") Project project);

    @Modifying
    @Query("DELETE FROM BugReport b WHERE b.testCase IN :testCases")
    void deleteAllByTestCaseIn(@Param("testCases") List<TestCase> testCases);
}
