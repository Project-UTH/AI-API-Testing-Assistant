package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.RequirementDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RequirementDocumentRepository extends JpaRepository<RequirementDocument, UUID> {

    List<RequirementDocument> findAllByProjectAndActiveTrueOrderByCreatedAtAsc(Project project);

    List<RequirementDocument> findAllByProjectOrderByCreatedAtAsc(Project project);

    long countByProjectAndActiveTrue(Project project);

    Optional<RequirementDocument> findByIdAndProject(UUID id, Project project);

    Optional<RequirementDocument> findFirstByProjectAndFileNameAndActiveTrue(Project project, String fileName);

    @Modifying
    @Query("DELETE FROM RequirementDocument d WHERE d.project = :project")
    void deleteAllByProject(@Param("project") Project project);
}
