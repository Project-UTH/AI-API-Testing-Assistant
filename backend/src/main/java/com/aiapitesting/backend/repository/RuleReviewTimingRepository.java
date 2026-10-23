package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.RuleReviewTiming;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RuleReviewTimingRepository extends JpaRepository<RuleReviewTiming, UUID> {

    List<RuleReviewTiming> findAllByProjectIdOrderByCreatedAtAsc(UUID projectId);

    @Modifying
    @Query("DELETE FROM RuleReviewTiming t WHERE t.projectId = :projectId")
    void deleteAllByProjectId(@Param("projectId") UUID projectId);
}
