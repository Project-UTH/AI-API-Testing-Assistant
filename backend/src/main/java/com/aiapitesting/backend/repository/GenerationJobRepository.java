package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.GenerationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GenerationJobRepository extends JpaRepository<GenerationJob, UUID> {

    Optional<GenerationJob> findByIdAndProjectId(UUID id, UUID projectId);

    boolean existsByProjectIdAndStatusIn(UUID projectId, Collection<String> statuses);

    List<GenerationJob> findAllByProjectIdAndStatusInOrderByCreatedAtDesc(UUID projectId, Collection<String> statuses);

    List<GenerationJob> findTop20ByProjectIdOrderByCreatedAtDesc(UUID projectId);

    long countByOwnerIdAndStatusIn(UUID ownerId, Collection<String> statuses);

    @Modifying
    @Query("UPDATE GenerationJob j SET j.status = :to, j.finishedAt = :now, j.errorMessage = :message WHERE j.status IN :from")
    int markAll(@Param("from") Collection<String> from, @Param("to") String to, @Param("now") Instant now, @Param("message") String message);

    @Modifying
    @Query("UPDATE GenerationJob j SET j.tokensUsed = j.tokensUsed + :tokens WHERE j.id = :id")
    int addTokens(@Param("id") UUID id, @Param("tokens") int tokens);

    void deleteAllByProjectId(UUID projectId);

    @Query("SELECT j.id FROM GenerationJob j WHERE j.projectId = :projectId")
    List<UUID> findIdsByProjectId(@Param("projectId") UUID projectId);
}
