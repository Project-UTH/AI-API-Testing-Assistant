package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseSource;
import com.aiapitesting.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
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

    void deleteAllByEndpointAndSource(Endpoint endpoint, TestCaseSource source);

    // Lấy trước danh sách sắp bị xoá bởi deleteAllByEndpointAndSource - để guard kiểm tra
    // TestCaseDependency trước khi xoá thật (chặn regenerate nếu còn ai phụ thuộc).
    List<TestCase> findAllByEndpointAndSource(Endpoint endpoint, TestCaseSource source);

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
