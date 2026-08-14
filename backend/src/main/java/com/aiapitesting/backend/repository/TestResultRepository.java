package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TestResultRepository extends JpaRepository<TestResult, UUID> {
    void deleteAllByTestCaseEndpointProject(Project project);

    void deleteAllByTestCase(TestCase testCase);

    void deleteAllByTestCaseIn(List<TestCase> testCases);

    // Trang Tổng quan (Module 8) - tỷ lệ pass toàn thời gian trên toàn bộ project user sở hữu.
    long countByTestCaseEndpointProjectOwner(User owner);

    long countByTestCaseEndpointProjectOwnerAndStatus(User owner, TestResultStatus status);

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
}
