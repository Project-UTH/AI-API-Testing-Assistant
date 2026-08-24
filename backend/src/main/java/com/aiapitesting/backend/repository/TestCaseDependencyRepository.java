package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseDependency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TestCaseDependencyRepository extends JpaRepository<TestCaseDependency, UUID> {

    // Dependency của riêng 1 test case - dùng để biết tham số nào đã có dependency, không gợi ý lại.
    List<TestCaseDependency> findAllByTestCase(TestCase testCase);

    /**
     * @Modifying bắt buộc - saveDependencies() xoá rồi insert lại dependency cho cùng 1 placeholder
     * trong cùng transaction; Hibernate flush theo thứ tự cố định (insert trước, delete sau) khiến
     * insert dòng mới vi phạm unique constraint nếu dùng derived delete thường.
     */
    @Modifying
    @Query("DELETE FROM TestCaseDependency tcd WHERE tcd.testCase = :testCase")
    void deleteAllByTestCase(@Param("testCase") TestCase testCase);

    /**
     * Dọn trước khi bulk-xoá 1 tập test case (regenerate AI) - các test case này có thể tự là
     * consumer của dependency khác, cần dọn để tránh lỗi khoá ngoại. @Modifying bắt buộc cùng lý do
     * deleteAllByTestCase phía trên.
     */
    @Modifying
    @Query("DELETE FROM TestCaseDependency tcd WHERE tcd.testCase IN :testCases")
    void deleteAllByTestCaseIn(@Param("testCases") List<TestCase> testCases);

    @Query("SELECT tcd FROM TestCaseDependency tcd JOIN FETCH tcd.testCase WHERE tcd.dependsOnTestCase.id IN :testCaseIds")
    List<TestCaseDependency> findAllByDependsOnTestCaseIdIn(@Param("testCaseIds") List<UUID> testCaseIds);

    // Toàn bộ dependency trong project (cả 2 phía FK), JOIN FETCH sâu tới dependsOnTestCase.endpoint
    // để TestExecutionService dùng ngay không cần query thêm.
    @Query("SELECT tcd FROM TestCaseDependency tcd "
            + "JOIN FETCH tcd.dependsOnTestCase dotc JOIN FETCH dotc.endpoint "
            + "WHERE tcd.testCase.endpoint.project = :project")
    List<TestCaseDependency> findAllByTestCaseEndpointProject(@Param("project") Project project);

    // Dọn trước khi bulk-xoá test case - cả 2 phía FK (consumer lẫn nguồn) đều có thể thuộc project đang xoá.
    @Modifying
    @Query("DELETE FROM TestCaseDependency tcd WHERE tcd.testCase.endpoint.project = :project "
            + "OR tcd.dependsOnTestCase.endpoint.project = :project")
    void deleteAllByProject(@Param("project") Project project);
}
