package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

import com.aiapitesting.backend.entity.ExecutionStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestExecutionRepository extends JpaRepository<TestExecution, UUID> {
    void deleteAllByProject(Project project);

    long countByProject(Project project);

    boolean existsByProjectAndStatusIn(Project project, Collection<ExecutionStatus> statuses);

    // Dọn các lần chạy bị bỏ dở do server tắt giữa chừng (xem ExecutionStartupCleaner).
    @Modifying
    @Query("UPDATE TestExecution e SET e.status = com.aiapitesting.backend.entity.ExecutionStatus.FAILED, e.finishedAt = :now "
            + "WHERE e.status IN :statuses")
    int markUnfinishedAsFailed(@Param("statuses") Collection<ExecutionStatus> statuses, @Param("now") Instant now);

    Optional<TestExecution> findByIdAndProject(UUID id, Project project);

    // Module 17 (17c) - các lượt chạy gần nhất của project (chọn lượt để so sánh).
    List<TestExecution> findTop50ByProjectOrderByStartedAtDesc(Project project);
}
