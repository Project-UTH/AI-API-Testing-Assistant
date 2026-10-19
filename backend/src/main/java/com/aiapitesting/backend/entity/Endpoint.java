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
@Table(name = "endpoints")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Endpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false)
    private String path;

    @Column(nullable = false)
    private String method;

    @Column
    private String summary;

    @Column(name = "schema_definition", columnDefinition = "TEXT")
    private String schema;

    /**
     * Schema response (chỉ mã 2xx có nội dung JSON) theo mã trạng thái: {"200": {...schema OpenAPI đã inline $ref...}}.
     * Lưu RIÊNG khỏi {@link #schema} vì {@code schema} được gửi cho AI (dung lượng/token) còn cột này chỉ dùng để đối chiếu
     * phản hồi thật lúc chạy test. Null = spec không khai hoặc endpoint import trước khi có tính năng (cần import lại).
     */
    @Column(name = "response_schemas", columnDefinition = "TEXT")
    private String responseSchemas;

    /**
     * Suy ra từ operation.getSecurity() (hoặc security toàn cục khi operation không override) lúc
     * import - dùng cho chiều Auth của Coverage Matrix (Module 15c). columnDefinition cần khai rõ
     * DEFAULT vì đây là cột NOT NULL thêm vào bảng đã có dữ liệu (cùng gotcha VARCHAR/TINYINT đã rút
     * ra ở Module 11a) - TINYINT ngầm định 0 nên endpoint cũ tự nhận "không yêu cầu auth" (an toàn,
     * không crash), chỉ cần re-import để có giá trị đúng.
     */
    @Column(nullable = false, columnDefinition = "TINYINT(1) DEFAULT 0")
    @Builder.Default
    private boolean requiresAuth = false;

    /**
     * Phạm vi đo coverage: loại test case người dùng chọn kiểm cho endpoint này (ghi lại mỗi lần sinh test case,
     * hoặc chỉnh ở trang Coverage). Cả 4 cột null = chưa từng chọn (endpoint cũ) → coi như đủ 4 loại. Dùng
     * Boolean nullable (không DEFAULT) để {@code ddl-auto=update} thêm cột được lên bảng đã có dữ liệu.
     */
    @Column
    private Boolean scopePositive;

    @Column
    private Boolean scopeNegative;

    @Column
    private Boolean scopeBoundary;

    @Column
    private Boolean scopeSecurity;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
