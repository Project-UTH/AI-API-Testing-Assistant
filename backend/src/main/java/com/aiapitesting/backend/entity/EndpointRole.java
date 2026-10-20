package com.aiapitesting.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Module 17 (17a) - 1 ô của ma trận phân quyền (endpoint × vai trò): vai trò này được phép (ALLOW) hay bị cấm (DENY) gọi endpoint.
 * Không có dòng = chưa khai báo (không sinh mục kiểm tra cho cặp đó). authProfileId là UUID thường (không quan hệ JPA) - cùng lý do
 * với TestCase.authProfileId: chạy/tính toán ngoài transaction.
 */
@Entity
@Table(name = "endpoint_roles", uniqueConstraints = @UniqueConstraint(columnNames = {"endpoint_id", "auth_profile_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EndpointRole {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "endpoint_id", nullable = false)
    private Endpoint endpoint;

    @Column(name = "auth_profile_id", nullable = false)
    private UUID authProfileId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(10)")
    private AuthzExpectation expectation;
}
