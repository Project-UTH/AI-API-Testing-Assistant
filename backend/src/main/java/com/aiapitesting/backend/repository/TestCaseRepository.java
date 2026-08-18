package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseSource;
import com.aiapitesting.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestCaseRepository extends JpaRepository<TestCase, UUID> {
    // Trang Tổng quan (Module 8) - tổng test case của toàn bộ project user sở hữu.
    long countByEndpointProjectOwner(User owner);
    // JOIN FETCH endpoint để TestCaseResponse.from() đọc được endpoint.getPath()/getMethod() sau khi
    // session đã đóng (spring.jpa.open-in-view=false) - tránh LazyInitializationException, đồng thời
    // tránh N+1 query khi map danh sách.
    @Query("SELECT tc FROM TestCase tc JOIN FETCH tc.endpoint WHERE tc.endpoint.project = :project")
    List<TestCase> findAllByEndpointProject(@Param("project") Project project);

    @Query("SELECT tc FROM TestCase tc JOIN FETCH tc.endpoint WHERE tc.id = :id AND tc.endpoint = :endpoint")
    Optional<TestCase> findByIdAndEndpoint(@Param("id") UUID id, @Param("endpoint") Endpoint endpoint);

    // JOIN FETCH endpoint để entity còn dùng được (RestAssuredTestRunner đọc endpoint.getMethod())
    // sau khi băng qua @Async - entity truyền vào luồng khác, session gốc đã đóng.
    @Query("SELECT tc FROM TestCase tc JOIN FETCH tc.endpoint WHERE tc.id IN :ids AND tc.endpoint.project = :project")
    List<TestCase> findAllByIdInAndEndpointProject(@Param("ids") List<UUID> ids, @Param("project") Project project);

    /**
     * @Modifying bắt buộc - derived delete thường vỡ StaleStateException lúc flush khi 2 lượt
     * "Sinh Test Case" chồng nhau cho cùng endpoint (bấm nút 2 lần liên tiếp), vì lượt sau thấy
     * dòng đã bị lượt trước xoá mất (đã xác nhận qua log thật, cùng lớp lỗi với
     * TestResultRepository/TestCaseDependencyRepository/TestCaseAssertionRepository.deleteAllByTestCaseIn
     * chạy ngay trước lệnh này trong TestCaseGenerationService.generateGroup()).
     * AndLockedFalse - test case đang bị khoá (nút khoá riêng, mọi source) không bao giờ bị xoá khi
     * regenerate, bất kể đang xoá-thay nhóm Cơ bản hay Security.
     */
    @Modifying
    @Query("DELETE FROM TestCase tc WHERE tc.endpoint = :endpoint AND tc.source = :source AND tc.locked = false")
    void deleteAllByEndpointAndSourceAndLockedFalse(@Param("endpoint") Endpoint endpoint, @Param("source") TestCaseSource source);

    // Lấy trước danh sách sắp bị xoá bởi deleteAllByEndpointAndSourceAndLockedFalse - để guard kiểm
    // tra TestCaseDependency trước khi xoá thật (chặn regenerate nếu còn ai phụ thuộc). Đã lọc sẵn
    // locked=false nên case đang khoá không lọt vào danh sách "sắp xoá" - không cần check dependents
    // cho nó vì nó không hề bị đụng tới.
    List<TestCase> findAllByEndpointAndSourceAndLockedFalse(Endpoint endpoint, TestCaseSource source);

    // Dùng cho gợi ý liên kết tự động (Module 7) - lấy test case 2xx tạo sớm nhất của 1 endpoint.
    List<TestCase> findAllByEndpointOrderByCreatedAtAsc(Endpoint endpoint);

    void deleteAllByEndpointProject(Project project);

    @Query("SELECT tc.endpoint.id AS endpointId, COUNT(tc) AS count FROM TestCase tc "
            + "WHERE tc.endpoint.id IN :endpointIds GROUP BY tc.endpoint.id")
    List<EndpointTestCaseCount> countByEndpointIds(@Param("endpointIds") List<UUID> endpointIds);

    interface EndpointTestCaseCount {
        UUID getEndpointId();
        long getCount();
    }
}
