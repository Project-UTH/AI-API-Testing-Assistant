package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.BugReport;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BugReportRepository extends JpaRepository<BugReport, UUID> {

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
