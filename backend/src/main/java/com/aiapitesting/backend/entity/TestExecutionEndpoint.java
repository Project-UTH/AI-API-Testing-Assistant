package com.aiapitesting.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Bảng nối nhiều-nhiều TestExecution <-> Endpoint (Module 8) - 1 lần "Chạy Test" có thể liên quan
 * nhiều endpoint cùng lúc (người dùng chọn test case của nhiều endpoint, hoặc Test Data Chaining tự
 * kéo theo test case của endpoint khác làm nguồn dữ liệu). Ghi 1 dòng cho MỌI endpoint có mặt trong
 * tập test case đã chạy (kể cả tự chọn lẫn auto-included), để xem lịch sử của bất kỳ endpoint nào
 * trong số đó cũng thấy đúng sự kiện chạy này. Bảng nối thuần tuý phục vụ query, không phải quan hệ
 * nghiệp vụ có vòng đời riêng như TestCaseDependency nên không cần createdAt.
 */
@Entity
@Table(name = "test_execution_endpoints", uniqueConstraints = @UniqueConstraint(
        name = "uk_test_execution_endpoints_execution_endpoint", columnNames = {"execution_id", "endpoint_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestExecutionEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_id", nullable = false)
    private TestExecution execution;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "endpoint_id", nullable = false)
    private Endpoint endpoint;
}
