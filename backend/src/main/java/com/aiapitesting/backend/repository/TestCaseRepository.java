package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestCaseRepository extends JpaRepository<TestCase, UUID> {
    // JOIN FETCH endpoint để TestCaseResponse.from() đọc được endpoint.getPath()/getMethod() sau khi
    // session đã đóng (spring.jpa.open-in-view=false) - tránh LazyInitializationException, đồng thời
    // tránh N+1 query khi map danh sách.
    @Query("SELECT tc FROM TestCase tc JOIN FETCH tc.endpoint WHERE tc.endpoint.project = :project")
    List<TestCase> findAllByEndpointProject(@Param("project") Project project);

    @Query("SELECT tc FROM TestCase tc JOIN FETCH tc.endpoint WHERE tc.id = :id AND tc.endpoint = :endpoint")
    Optional<TestCase> findByIdAndEndpoint(@Param("id") UUID id, @Param("endpoint") Endpoint endpoint);

    void deleteAllByEndpointAndSource(Endpoint endpoint, TestCaseSource source);

    void deleteAllByEndpointProject(Project project);

    @Query("SELECT tc.endpoint.id AS endpointId, COUNT(tc) AS count FROM TestCase tc "
            + "WHERE tc.endpoint.id IN :endpointIds GROUP BY tc.endpoint.id")
    List<EndpointTestCaseCount> countByEndpointIds(@Param("endpointIds") List<UUID> endpointIds);

    interface EndpointTestCaseCount {
        UUID getEndpointId();
        long getCount();
    }
}
