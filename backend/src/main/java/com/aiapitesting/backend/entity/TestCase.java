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

    /**
     * Module 15d (Tier 2) - AI xác nhận lại case bị Tier 1 gắn cờ NEEDS_REVIEW có phải lỗi thật hay
     * false positive (vd case Security cố ý gửi giá trị sai để test injection). null = Tier 2 chưa
     * chạy (case không bị gắn cờ, hoặc hết quota lúc đó) - KHÁC {@code reliabilityStatus=null} (không
     * áp dụng hẳn). true = AI xác nhận lỗi thật; false = AI cho là false positive, chủ đích.
     */
    private Boolean reliabilityAiConfirmed;

    /** Giải thích ngắn của AI cho reliabilityAiConfirmed - null khi reliabilityAiConfirmed null. */
    @Column(columnDefinition = "TEXT")
    private String reliabilityAiNote;

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

    /**
     * Module 15h - case này do AI sinh đạt checklist, AI đề xuất thêm, hay hệ thống tự bù. VARCHAR
     * thay vì MySQL ENUM - cùng lý do đã rút ra ở TestCaseSource (ENUM không tự mở rộng).
     */
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)")
    private TestCaseGenerationOrigin generationOrigin;

    /** Module 15h - mô tả mục checklist EP/BVA mà case này đạt (vd "Thiếu field bắt buộc 'email'..."), null nếu không ứng với mục nào. */
    @Column(columnDefinition = "VARCHAR(255)")
    private String checklistItem;

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
