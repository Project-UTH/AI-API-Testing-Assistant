package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseAssertion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TestCaseAssertionRepository extends JpaRepository<TestCaseAssertion, java.util.UUID> {

    // Assertion của riêng 1 test case (form sửa test case) - hiện lại danh sách đã có để sửa/xoá.
    List<TestCaseAssertion> findAllByTestCase(TestCase testCase);

    // @Modifying bắt buộc - saveAssertions() xoá rồi insert lại trong cùng transaction, cần DELETE
    // chạy thật ngay thay vì đợi flush.
    @Modifying
    @Query("DELETE FROM TestCaseAssertion tca WHERE tca.testCase = :testCase")
    void deleteAllByTestCase(@Param("testCase") TestCase testCase);

    // Dọn trước khi bulk-xoá 1 tập test case (regenerate AI, xoá endpoint khi import lại).
    @Modifying
    @Query("DELETE FROM TestCaseAssertion tca WHERE tca.testCase IN :testCases")
    void deleteAllByTestCaseIn(@Param("testCases") List<TestCase> testCases);

    // Dọn toàn bộ assertion trong 1 project.
    @Modifying
    @Query("DELETE FROM TestCaseAssertion tca WHERE tca.testCase.endpoint.project = :project")
    void deleteAllByTestCaseEndpointProject(@Param("project") Project project);

    // Nạp assertion cho cả tập test case sắp chạy.
    List<TestCaseAssertion> findAllByTestCaseIn(List<TestCase> testCases);

    // Toàn bộ assertion trong project.
    @Query("SELECT tca FROM TestCaseAssertion tca WHERE tca.testCase.endpoint.project = :project")
    List<TestCaseAssertion> findAllByTestCaseEndpointProject(@Param("project") Project project);
}
