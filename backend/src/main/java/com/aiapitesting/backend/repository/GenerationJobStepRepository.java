package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.GenerationJobStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface GenerationJobStepRepository extends JpaRepository<GenerationJobStep, UUID> {

    @Query("SELECT COALESCE(MAX(s.seq), 0) FROM GenerationJobStep s WHERE s.jobId = :jobId")
    int maxSeq(@Param("jobId") UUID jobId);

    /** Bước mới (seq > afterSeq) cộng các bước còn RUNNING (để client thấy chúng đổi sang OK/FAILED). */
    @Query("SELECT s FROM GenerationJobStep s WHERE s.jobId = :jobId AND (s.seq > :afterSeq OR s.status = 'RUNNING') ORDER BY s.seq ASC")
    List<GenerationJobStep> findForPolling(@Param("jobId") UUID jobId, @Param("afterSeq") int afterSeq);

    @Modifying
    @Query("UPDATE GenerationJobStep s SET s.status = 'FAILED', s.finishedAt = :now, s.detail = :message WHERE s.status = 'RUNNING'")
    int failAllRunning(@Param("now") Instant now, @Param("message") String message);

    void deleteAllByJobIdIn(Collection<UUID> jobIds);
}
