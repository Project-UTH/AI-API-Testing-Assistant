package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.ApiToken;
import com.aiapitesting.backend.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiTokenRepository extends JpaRepository<ApiToken, UUID> {

    // JOIN FETCH project: bộ lọc xác thực chạy ngoài transaction và chỉ cần projectId - không để lazy proxy.
    @Query("SELECT t FROM ApiToken t JOIN FETCH t.project WHERE t.tokenHash = :hash")
    Optional<ApiToken> findByTokenHashWithProject(@Param("hash") String hash);

    List<ApiToken> findAllByProjectOrderByCreatedAtDesc(Project project);

    Optional<ApiToken> findByIdAndProject(UUID id, Project project);

    /** Token còn hiệu lực (chưa thu hồi, chưa hết hạn) - giới hạn số token mỗi project. */
    @Query("SELECT COUNT(t) FROM ApiToken t WHERE t.project = :project AND t.revokedAt IS NULL "
            + "AND (t.expiresAt IS NULL OR t.expiresAt > :now)")
    long countActive(@Param("project") Project project, @Param("now") Instant now);

    void deleteAllByProject(Project project);
}
