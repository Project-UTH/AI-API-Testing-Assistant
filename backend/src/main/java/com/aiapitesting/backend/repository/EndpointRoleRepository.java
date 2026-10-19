package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.EndpointRole;
import com.aiapitesting.backend.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface EndpointRoleRepository extends JpaRepository<EndpointRole, UUID> {

    // JOIN FETCH endpoint: dùng sau khi session đóng và tránh N+1 khi tính Coverage cho cả project.
    @Query("SELECT r FROM EndpointRole r JOIN FETCH r.endpoint e WHERE e.project = :project")
    List<EndpointRole> findAllByEndpointProject(@Param("project") Project project);

    List<EndpointRole> findAllByEndpoint(Endpoint endpoint);

    void deleteAllByEndpointProject(Project project);

    void deleteAllByAuthProfileId(UUID authProfileId);
}
