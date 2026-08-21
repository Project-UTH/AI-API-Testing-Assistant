package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
    Page<Project> findAllByOwner(User owner, Pageable pageable);

    long countByOwner(User owner);

    // BugReportService.create() - tính projectSeq tiếp theo khi 1 project lần đầu có bug (Module 10),
    // phạm vi trong owner hiện tại để không rò rỉ hoạt động giữa các user không liên quan.
    @Query("SELECT MAX(p.bugReportProjectSeq) FROM Project p WHERE p.owner = :owner")
    Optional<Integer> findMaxBugReportProjectSeqByOwner(@Param("owner") User owner);
}
