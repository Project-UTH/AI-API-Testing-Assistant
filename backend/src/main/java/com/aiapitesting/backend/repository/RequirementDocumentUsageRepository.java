package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.RequirementDocumentUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RequirementDocumentUsageRepository extends JpaRepository<RequirementDocumentUsage, UUID> {

    interface UsageRow {
        UUID getDocumentId();

        String getEndpointKey();

        Instant getLastUsedAt();

        long getTimesUsed();
    }

    /** Mỗi cặp (tài liệu, endpoint): lần dùng gần nhất và số lần dùng. */
    @Query("select u.documentId as documentId, u.endpointKey as endpointKey, max(u.usedAt) as lastUsedAt, count(u) as timesUsed "
            + "from RequirementDocumentUsage u where u.projectId = :projectId group by u.documentId, u.endpointKey")
    List<UsageRow> summarize(@Param("projectId") UUID projectId);

    @Modifying
    void deleteAllByProjectId(UUID projectId);
}
