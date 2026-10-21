package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.FailureCause;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestResultRepository extends JpaRepository<TestResult, UUID>, JpaSpecificationExecutor<TestResult> {
    void deleteAllByTestCaseEndpointProject(Project project);

    void deleteAllByTestCase(TestCase testCase);

    /**
     * @Modifying bắt buộc (giống TestCaseDependencyRepository.deleteAllByTestCase) - derived delete
     * thường load entity rồi remove() từng dòng, kiểm tra đúng 1 dòng bị xoá lúc flush; 2 lượt
     * "Sinh Test Case" chồng nhau cho CÙNG 1 endpoint (vd bấm nút 2 lần liên tiếp) khiến lượt sau
     * thấy dòng đã bị lượt trước xoá mất -> StaleStateException/ObjectOptimisticLockingFailureException
     * lúc commit (đã xác nhận qua log thật). Bulk DELETE bỏ qua persistence context, không kiểm tra
     * số dòng bị ảnh hưởng nên không vỡ khi dòng đã bị xoá sẵn.
     */
    @Modifying
    @Query("DELETE FROM TestResult tr WHERE tr.testCase IN :testCases")
    void deleteAllByTestCaseIn(@Param("testCases") List<TestCase> testCases);

    // Tổng lượt chạy của owner - mẫu số tỷ lệ pass (mỗi lần chạy tính riêng, kể cả chạy lại).
    long countByTestCaseEndpointProjectOwner(User owner);

    long countByTestCaseEndpointProject(Project project);

    long countByTestCaseEndpointProjectAndStatus(Project project, TestResultStatus status);

    long countByTestCaseEndpointProjectOwnerAndStatus(User owner, TestResultStatus status);

    // Tổng lượt chạy toàn hệ thống (mọi owner) - dùng cho Dashboard Admin.
    long countByStatus(TestResultStatus status);

    // Số test case phân biệt đã chạy ít nhất 1 lần - khác count phía trên vì không đếm trùng khi
    // 1 test case chạy nhiều lần.
    @Query("SELECT COUNT(DISTINCT tr.testCase) FROM TestResult tr WHERE tr.testCase.endpoint.project.owner = :owner")
    long countDistinctTestCaseByOwner(@Param("owner") User owner);

    @Query("SELECT COUNT(DISTINCT tr.testCase) FROM TestResult tr")
    long countDistinctTestCase();

    // JOIN FETCH testCase + testCase.endpoint để TestResultResponse.from() đọc được
    // testCase.getName()/getExpectedStatus()/getEndpoint().getId() sau khi session đã đóng
    // (spring.jpa.open-in-view=false) - tránh LazyInitializationException.
    @Query("SELECT tr FROM TestResult tr JOIN FETCH tr.testCase tc JOIN FETCH tc.endpoint WHERE tr.execution = :execution ORDER BY tc.createdAt ASC")
    List<TestResult> findAllByExecutionOrderByTestCaseCreatedAt(@Param("execution") TestExecution execution);

    // Bulk-fetch cho nhiều execution cùng lúc (TestHistoryService, Module 8) - tránh N+1 khi tính
    // selectedCount/passCount/failCount theo từng cặp (execution, endpoint). JOIN FETCH sâu tới
    // testCase.endpoint để nhóm theo endpoint mà không cần query thêm.
    @Query("SELECT tr FROM TestResult tr JOIN FETCH tr.testCase tc JOIN FETCH tc.endpoint WHERE tr.execution IN :executions")
    List<TestResult> findAllByExecutionIn(@Param("executions") List<TestExecution> executions);

    // Tầng 3 Bug Report (Module 10) - toàn bộ lần chạy của ĐÚNG 1 test case, sắp theo thời điểm chạy
    // thật (execution.startedAt, không phải TestResult.createdAt - cùng ý nghĩa nhưng startedAt là
    // mốc người dùng hiểu là "lúc chạy"). JOIN FETCH execution (đọc startedAt) VÀ testCase (đọc
    // expectedStatus ở TestResultHistoryItemResponse.from()) - thiếu JOIN FETCH testCase gây
    // LazyInitializationException "no session" vì testCase truyền vào param chỉ dùng để so khớp
    // WHERE, không tự làm association tr.testCase của kết quả trả về được init sẵn.
    @Query("SELECT tr FROM TestResult tr JOIN FETCH tr.execution ex JOIN FETCH tr.testCase WHERE tr.testCase = :testCase ORDER BY ex.startedAt ASC")
    List<TestResult> findAllByTestCaseOrderByExecutionStartedAtAsc(@Param("testCase") TestCase testCase);

    // Nguồn dữ liệu cho BugReportService.getDraft()/create() - ownership check + JOIN FETCH đủ để
    // đọc testCase/endpoint mà không cần query thêm.
    @Query("SELECT tr FROM TestResult tr JOIN FETCH tr.testCase tc JOIN FETCH tc.endpoint WHERE tr.id = :id AND tc.endpoint.project = :project")
    Optional<TestResult> findByIdAndTestCaseEndpointProject(@Param("id") UUID id, @Param("project") Project project);

    // BugReportService.getBugReports() - tập testCaseId ĐÃ TỪNG chạy ít nhất 1 lần trong project,
    // dùng để tính TestCaseBugSummaryResponse.hasRuns (disable nút mở Tầng 3 khi chưa có gì để
    // xem) - 1 query duy nhất cho cả project, không N+1 theo từng test case.
    @Query("SELECT DISTINCT tr.testCase.id FROM TestResult tr WHERE tr.testCase.endpoint.project = :project")
    List<UUID> findDistinctTestCaseIdsWithResultsByProject(@Param("project") Project project);

    // BugReportService.generateForProject() - "Sinh tất cả" ở trang Bug Report: TOÀN BỘ lần chạy
    // Fail trong project (không giới hạn 1 execution như generateForExecution), để rồi service lọc
    // tiếp dòng nào đã có bug. JOIN FETCH đủ testCase/endpoint để buildDescription() đọc được.
    @Query("SELECT tr FROM TestResult tr JOIN FETCH tr.testCase tc JOIN FETCH tc.endpoint "
            + "WHERE tc.endpoint.project = :project AND tr.status = com.aiapitesting.backend.entity.TestResultStatus.FAILED")
    List<TestResult> findAllFailedByProject(@Param("project") Project project);

    // BugReportService.generateForProject() - "Sinh theo lựa chọn": ownership check qua
    // tc.endpoint.project ngay trong query (id không thuộc project sẽ tự bị loại, không lỗi 404).
    @Query("SELECT tr FROM TestResult tr JOIN FETCH tr.testCase tc JOIN FETCH tc.endpoint "
            + "WHERE tr.id IN :ids AND tc.endpoint.project = :project")
    List<TestResult> findAllByIdInAndTestCaseEndpointProject(@Param("ids") List<UUID> ids, @Param("project") Project project);

    // Module 15j - Coverage theo kết quả chạy: lượt chạy MỚI NHẤT của từng test case trong project, CHỈ vài
    // cột (không kéo responseBody/assertionResultsJson TEXT lên). 1 query cho cả project, không N+1.
    // Module 16 - thêm failureCause: FAILED do môi trường/token không được tính là "đã kiểm và thấy API lệch".
    // Module 16 gđ2 - thêm unstable: lần chạy mới nhất của test không ổn định (kể cả Pass) không phải bằng chứng chắc chắn.
    @Query("SELECT tr.testCase.id AS testCaseId, tr.status AS status, tr.failureCause AS failureCause, tr.unstable AS unstable "
            + "FROM TestResult tr "
            + "WHERE tr.testCase.endpoint.project = :project AND tr.createdAt = "
            + "(SELECT MAX(tr2.createdAt) FROM TestResult tr2 WHERE tr2.testCase = tr.testCase)")
    List<TestCaseLatestResult> findLatestResultsByProject(@Param("project") Project project);

    interface TestCaseLatestResult {
        UUID getTestCaseId();
        TestResultStatus getStatus();
        FailureCause getFailureCause();
        Boolean getUnstable();
    }

    // Module 16 - lịch sử các lần chạy của nhiều test case cho ExecutionFailureAnalyzer: chỉ vài cột nhẹ, KHÔNG
    // kéo responseBody. Chỉ lấy kết quả có fingerprint (dữ liệu trước Module 16 không có - không so được phiên
    // bản). Sắp theo thời gian tăng dần, service tự lọc theo (testCaseId, fingerprint).
    @Query("SELECT tr.id AS resultId, tr.testCase.id AS testCaseId, tr.testCaseFingerprint AS fingerprint, tr.status AS status, "
            + "tr.responseStatus AS responseStatus, tr.createdAt AS createdAt, tr.execution.targetBaseUrlSnapshot AS baseUrl, "
            + "tr.failureCause AS failureCause "
            + "FROM TestResult tr WHERE tr.testCase.id IN :testCaseIds AND tr.testCaseFingerprint IS NOT NULL "
            + "ORDER BY tr.createdAt ASC")
    List<HistoryRow> findHistoryRowsByTestCaseIds(@Param("testCaseIds") List<UUID> testCaseIds);

    // Module 17 (17c) - so sánh 2 lượt chạy: chỉ vài cột nhẹ (KHÔNG kéo responseBody), 1 query cho cả lượt, không N+1.
    @Query("SELECT tr.testCase.id AS testCaseId, tr.testCase.name AS testCaseName, tr.testCase.endpoint.id AS endpointId, "
            + "tr.testCase.endpoint.method AS endpointMethod, tr.testCase.endpoint.path AS endpointPath, tr.status AS status, "
            + "tr.responseStatus AS responseStatus, tr.failureCause AS failureCause, tr.unstable AS unstable, "
            + "tr.testCaseFingerprint AS fingerprint FROM TestResult tr WHERE tr.execution = :execution")
    List<DiffRow> findDiffRowsByExecution(@Param("execution") TestExecution execution);

    interface DiffRow {
        UUID getTestCaseId();
        String getTestCaseName();
        UUID getEndpointId();
        String getEndpointMethod();
        String getEndpointPath();
        TestResultStatus getStatus();
        Integer getResponseStatus();
        FailureCause getFailureCause();
        Boolean getUnstable();
        String getFingerprint();
    }

    // Module 17 (17c) - đếm kết quả theo (lượt chạy, trạng thái) cho nhiều lượt cùng lúc, 1 query.
    @Query("SELECT tr.execution.id AS executionId, tr.status AS status, COUNT(tr) AS count FROM TestResult tr "
            + "WHERE tr.execution IN :executions GROUP BY tr.execution.id, tr.status")
    List<ExecutionStatusCount> countByExecutionsGroupedByStatus(@Param("executions") List<TestExecution> executions);

    interface ExecutionStatusCount {
        UUID getExecutionId();
        TestResultStatus getStatus();
        long getCount();
    }

    // Module 17 (G4) - tổng hợp cho 1 lần chạy mà không nạp từng kết quả (trang kết quả phân trang).
    @Query("SELECT tr.failureCause AS cause, COUNT(tr) AS count FROM TestResult tr "
            + "WHERE tr.execution = :execution AND tr.failureCause IS NOT NULL GROUP BY tr.failureCause")
    List<FailureCauseCount> countByExecutionGroupedByFailureCause(@Param("execution") TestExecution execution);

    interface FailureCauseCount {
        FailureCause getCause();
        long getCount();
    }

    long countByExecutionAndUnstableTrue(TestExecution execution);

    // Test case được tự động chạy kèm do dependency (Module 7) trong lần chạy này.
    @Query("SELECT tr.testCase.id FROM TestResult tr WHERE tr.execution = :execution AND tr.autoIncluded = true")
    List<UUID> findAutoIncludedTestCaseIds(@Param("execution") TestExecution execution);

    interface HistoryRow {
        UUID getResultId();
        FailureCause getFailureCause();
        UUID getTestCaseId();
        String getFingerprint();
        TestResultStatus getStatus();
        Integer getResponseStatus();
        Instant getCreatedAt();
        String getBaseUrl();
    }
}
