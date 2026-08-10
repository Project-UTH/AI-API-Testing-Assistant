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
@Table(name = "test_cases")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestCase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "endpoint_id", nullable = false)
    private Endpoint endpoint;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String requestHeaders;

    @Column(columnDefinition = "TEXT")
    private String requestBody;

    @Column(nullable = false)
    private Integer expectedStatus;

    /**
     * Path đã thay tham số OpenAPI dạng {tenThamSo} bằng token {{tenThamSo}} (không phải giá trị
     * cụ thể) - vd "/pet/{{petId}}". Tham số query (OpenAPI "in": "query") không có field riêng -
     * gắn thẳng vào cuối path dùng cùng cú pháp, vd "/pet/{{petId}}?name={{name}}". Giá trị thật
     * cho token được resolve lúc thực thi (Module 6/7), ưu tiên TestCaseDependency (Module 7)
     * trước, rồi mới tới pathParamFallbacks.
     */
    @Column(columnDefinition = "TEXT")
    private String resolvedPath;

    /**
     * Giá trị dự phòng (AI đoán hoặc người dùng tự nhập) cho từng tham số path hoặc query trong
     * resolvedPath, dạng JSON {"petId": "1", "name": "Buddy"} - chỉ dùng khi tham số đó không có
     * TestCaseDependency khớp tên lúc thực thi.
     */
    @Column(columnDefinition = "TEXT")
    private String pathParamFallbacks;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TestCaseSource source;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
