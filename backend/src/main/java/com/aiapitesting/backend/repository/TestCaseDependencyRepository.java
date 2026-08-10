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

    // Dependency của riêng 1 test case (form sửa test case, gợi ý liên kết) - dùng để biết tham số
    // nào đã có dependency rồi, không cần gợi ý lại.
    List<TestCaseDependency> findAllByTestCase(TestCase testCase);

    /**
     * @Modifying bat buoc o day (khong the dung derived delete thuong) - saveDependencies() xoa
     * roi insert lai dependency CHO CUNG 1 placeholder trong cung 1 transaction; derived delete
     * mac dinh chi danh dau entity de xoa trong persistence context, con Hibernate flush THEO THU
     * TU CO DINH (insert truoc, delete sau) bat ke thu tu goi trong code - khien insert dong moi
     * dung y het (test_case_id, placeholder_name) voi dong sap bi xoa chay TRUOC, vi pham unique
     * constraint. @Modifying ep chay bulk DELETE that ngay lap tuc, khong doi flush.
     */
    @Modifying
    @Query("DELETE FROM TestCaseDependency tcd WHERE tcd.testCase = :testCase")
    void deleteAllByTestCase(@Param("testCase") TestCase testCase);

    // Dọn trước khi bulk-xoá 1 tập test case cụ thể (regenerate AI - TestCaseGenerationService) -
    // các test case này có thể tự là consumer của dependency khác, không chỉ nguồn (đã chặn ở
    // ensureNoDependents phía nguồn, nhưng phía consumer vẫn cần dọn để tránh lỗi khoá ngoại 1451.
    void deleteAllByTestCaseIn(List<TestCase> testCases);

    @Query("SELECT tcd FROM TestCaseDependency tcd JOIN FETCH tcd.testCase WHERE tcd.dependsOnTestCase.id IN :testCaseIds")
    List<TestCaseDependency> findAllByDependsOnTestCaseIdIn(@Param("testCaseIds") List<UUID> testCaseIds);

    // Toàn bộ dependency trong project (cả 2 phía FK), JOIN FETCH sâu tới dependsOnTestCase.endpoint
    // để TestExecutionService (BFS/topological sort, đồng bộ) dùng ngay không cần query thêm.
    @Query("SELECT tcd FROM TestCaseDependency tcd "
            + "JOIN FETCH tcd.dependsOnTestCase dotc JOIN FETCH dotc.endpoint "
            + "WHERE tcd.testCase.endpoint.project = :project")
    List<TestCaseDependency> findAllByTestCaseEndpointProject(@Param("project") Project project);

    // Dọn trước khi bulk-xoá test case (doImport()/ProjectService.delete()) - tránh lỗi khoá ngoại
    // 1451, cả 2 phía FK (consumer lẫn nguồn) đều có thể thuộc project đang xoá.
    @Modifying
    @Query("DELETE FROM TestCaseDependency tcd WHERE tcd.testCase.endpoint.project = :project "
            + "OR tcd.dependsOnTestCase.endpoint.project = :project")
    void deleteAllByProject(@Param("project") Project project);
}
