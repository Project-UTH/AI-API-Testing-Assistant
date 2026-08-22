package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestResultRepository extends JpaRepository<TestResult, UUID> {
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

    // Trang Tổng quan (Module 8) - tỷ lệ pass toàn thời gian trên toàn bộ project user sở hữu.
    long countByTestCaseEndpointProjectOwner(User owner);

    long countByTestCaseEndpointProjectOwnerAndStatus(User owner, TestResultStatus status);

    // Trang Admin (Module 11) - tỷ lệ pass TOÀN HỆ THỐNG (mọi owner), khác 2 method trên (giới hạn
    // theo 1 owner, dùng cho Dashboard cá nhân Module 8).
    long countByStatus(TestResultStatus status);

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
}
