package com.aiapitesting.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "test_executions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExecutionStatus status;

    @Column(nullable = false, updatable = false)
    private Instant startedAt;

    private Instant finishedAt;

    /** Module 16 - Target Base URL lúc chạy (project có thể đổi URL giữa 2 lần chạy - bằng chứng cho nguyên nhân môi trường). */
    private String targetBaseUrlSnapshot;

    /**
     * Module 16 - thời điểm token đích được đặt/đổi gần nhất lúc chạy (Project.targetAuthUpdatedAt). CHỈ lưu thời
     * điểm, không lưu hay hash token.
     */
    private Instant targetAuthUpdatedAtSnapshot;

    @PrePersist
    protected void onCreate() {
        this.startedAt = Instant.now();
        if (this.status == null) {
            this.status = ExecutionStatus.PENDING;
        }
    }
}
