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
    long countByEndpointProjectOwner(User owner);

    // JOIN FETCH endpoint - tránh LazyInitializationException sau khi session đóng, và N+1 khi map danh sách.
    @Query("SELECT tc FROM TestCase tc JOIN FETCH tc.endpoint WHERE tc.endpoint.project = :project")
    List<TestCase> findAllByEndpointProject(@Param("project") Project project);

    @Query("SELECT tc FROM TestCase tc JOIN FETCH tc.endpoint WHERE tc.id = :id AND tc.endpoint = :endpoint")
    Optional<TestCase> findByIdAndEndpoint(@Param("id") UUID id, @Param("endpoint") Endpoint endpoint);

    // Bug Report - ownership check khi chỉ có testCaseId (không biết trước endpointId).
    @Query("SELECT tc FROM TestCase tc JOIN FETCH tc.endpoint WHERE tc.id = :id AND tc.endpoint.project = :project")
    Optional<TestCase> findByIdAndEndpointProject(@Param("id") UUID id, @Param("project") Project project);

    // JOIN FETCH endpoint để entity còn dùng được sau khi băng qua @Async (session gốc đã đóng).
    @Query("SELECT tc FROM TestCase tc JOIN FETCH tc.endpoint WHERE tc.id IN :ids AND tc.endpoint.project = :project")
    List<TestCase> findAllByIdInAndEndpointProject(@Param("ids") List<UUID> ids, @Param("project") Project project);

    /**
     * @Modifying bắt buộc - derived delete vỡ StaleStateException khi 2 lượt "Sinh Test Case" chồng
     * nhau cho cùng endpoint (lượt sau thấy dòng đã bị lượt trước xoá mất). AndLockedFalse - test
     * case đang khoá không bao giờ bị xoá khi regenerate.
     */
    @Modifying
    @Query("DELETE FROM TestCase tc WHERE tc.endpoint = :endpoint AND tc.source = :source AND tc.locked = false")
    void deleteAllByEndpointAndSourceAndLockedFalse(@Param("endpoint") Endpoint endpoint, @Param("source") TestCaseSource source);

    // Lấy trước danh sách sắp bị xoá - để guard kiểm tra TestCaseDependency trước khi xoá thật.
    List<TestCase> findAllByEndpointAndSourceAndLockedFalse(Endpoint endpoint, TestCaseSource source);

    // Gợi ý liên kết tự động - lấy test case 2xx tạo sớm nhất của 1 endpoint.
    List<TestCase> findAllByEndpointOrderByCreatedAtAsc(Endpoint endpoint);

    void deleteAllByEndpointProject(Project project);

    @Query("SELECT tc.endpoint.id AS endpointId, COUNT(tc) AS count FROM TestCase tc "
            + "WHERE tc.endpoint.id IN :endpointIds GROUP BY tc.endpoint.id")
    List<EndpointTestCaseCount> countByEndpointIds(@Param("endpointIds") List<UUID> endpointIds);

    interface EndpointTestCaseCount {
        UUID getEndpointId();
        long getCount();
    }

    // Tổng test case theo từng owner - cùng công thức GROUP BY tránh N+1 như countByEndpointIds.
    @Query("SELECT tc.endpoint.project.owner.id AS ownerId, COUNT(tc) AS count FROM TestCase tc "
            + "WHERE tc.endpoint.project.owner.id IN :ownerIds GROUP BY tc.endpoint.project.owner.id")
    List<OwnerTestCaseCount> countGroupedByOwnerIds(@Param("ownerIds") List<UUID> ownerIds);

    interface OwnerTestCaseCount {
        UUID getOwnerId();
        long getCount();
    }
}
