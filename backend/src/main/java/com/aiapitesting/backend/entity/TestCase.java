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

    /**
     * Module 15a (Explainable AI) - câu giải thích ngắn do AI tự khai lúc sinh, chỉ rõ rule/field
     * nào của spec kích hoạt case này. Nullable: null cho case MANUAL (người tự thêm, không có gì để
     * giải thích) và dữ liệu lịch sử tạo trước khi có field này. CHỈ phục vụ con người đọc hiểu -
     * KHÔNG được dùng để tính Coverage Matrix (EndpointCoverageService, Module 15c) vì rationale AI
     * tự khai có thể không trung thực với lý do thật bên trong model (xem Module 15 trong roadmap).
     */
    @Column(columnDefinition = "TEXT")
    private String rationale;

    /**
     * Module 15b (Tier 1, rule-based) - đối chiếu tất định giữa giá trị AI sinh với ràng buộc
     * required/enum/min-max của OpenAPI spec, chỉ áp dụng cho case kỳ vọng thành công
     * (expectedStatus &lt; 400) - case kỳ vọng lỗi không bị đối chiếu (vi phạm ràng buộc có thể là
     * chủ đích). Null = không áp dụng (case MANUAL, dữ liệu cũ tạo trước field này, hoặc case kỳ
     * vọng lỗi). VARCHAR thay vì để Hibernate tự suy ENUM - cùng lý do đã rút ra ở TestCaseSource.
     */
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)")
    private TestCaseReliability reliabilityStatus;

    /** JSON array các câu cảnh báo cụ thể (tên field + rule bị vi phạm) khi reliabilityStatus=NEEDS_REVIEW - null khi RELIABLE hoặc không áp dụng. */
    @Column(columnDefinition = "TEXT")
    private String reliabilityNotes;

    @Column(nullable = false)
    private Integer expectedStatus;

    /**
     * Path đã thay tham số OpenAPI dạng {tenThamSo} bằng token {{tenThamSo}}, vd "/pet/{{petId}}".
     * Tham số query gắn thẳng vào cuối path cùng cú pháp, vd "/pet/{{petId}}?name={{name}}". Giá
     * trị thật cho token resolve lúc thực thi, ưu tiên TestCaseDependency trước, rồi pathParamFallbacks.
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

    // columnDefinition = VARCHAR thay vì để Hibernate tự suy MySQL ENUM(...) - ddl-auto=update
    // không tự nới rộng ENUM đã tồn tại khi thêm hằng số Java mới (đã gặp lỗi "Data truncated" thật
    // khi thêm SECURITY vào TestCaseSource) - VARCHAR tránh hẳn lớp lỗi này.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(30)")
    private TestCaseSource source;

    /**
     * Ghi đè cách gắn auth target API lúc thực thi - DEFAULT giữ hành vi cũ (auth thật của
     * Project), NONE/INVALID dùng cho case Security cố tình test thiếu/sai auth. Enum khai báo
     * DEFAULT trước tiên để khớp default ngầm của MySQL khi cột mới thêm vào.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(20)")
    @Builder.Default
    private TestCaseAuthOverride authOverride = TestCaseAuthOverride.DEFAULT;

    /** Khoá test case khỏi bị xoá khi "Sinh Test Case" xoá-và-thay - bật/tắt qua nút khoá riêng. */
    @Column(nullable = false)
    @Builder.Default
    private boolean locked = false;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
