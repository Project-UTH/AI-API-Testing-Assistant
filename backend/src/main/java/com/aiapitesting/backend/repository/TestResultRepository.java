package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TestResultRepository extends JpaRepository<TestResult, UUID> {
    void deleteAllByTestCaseEndpointProject(Project project);

    void deleteAllByTestCase(TestCase testCase);

    void deleteAllByTestCaseIn(List<TestCase> testCases);

    // JOIN FETCH testCase để TestResultResponse.from() đọc được testCase.getName()/getExpectedStatus()
    // sau khi session đã đóng (spring.jpa.open-in-view=false) - tránh LazyInitializationException.
    @Query("SELECT tr FROM TestResult tr JOIN FETCH tr.testCase WHERE tr.execution = :execution ORDER BY tr.testCase.createdAt ASC")
    List<TestResult> findAllByExecutionOrderByTestCaseCreatedAt(@Param("execution") TestExecution execution);
}
