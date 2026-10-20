package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.AuthProfile;
import com.aiapitesting.backend.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthProfileRepository extends JpaRepository<AuthProfile, UUID> {

    List<AuthProfile> findAllByProjectOrderByCreatedAtAsc(Project project);

    Optional<AuthProfile> findByIdAndProject(UUID id, Project project);

    boolean existsByProjectAndRoleKey(Project project, String roleKey);

    /** Dùng lúc chạy test (ngoài transaction, không đụng association lazy): profile phải thuộc ĐÚNG project của test. */
    @Query("SELECT p FROM AuthProfile p WHERE p.id = :id AND p.project.id = :projectId")
    Optional<AuthProfile> findByIdAndProjectId(@Param("id") UUID id, @Param("projectId") UUID projectId);

    void deleteAllByProject(Project project);
}
