package com.aiapitesting.backend.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bắt đầu 1 job sinh test chạy nền. Là lớp thường (mọi trường tuỳ chọn) để client gửi thiếu trường vẫn hợp lệ.
 *
 * <p>type: RULES (AI trích luật từ tài liệu/chỉ dẫn, luật chờ duyệt; tokenCap là ngân sách cứng), FULL (AI sinh test gốc + chạy xác nhận + hệ thống bổ sung; mặc định khi chọn 1 endpoint), SYSTEM (chỉ hệ thống, 0 token; mặc định khi chọn
 * nhiều endpoint), LEGACY (nút 'Sinh Test Case' cũ: AI sinh theo nhóm đã tick). dryRun: chỉ liệt kê các bước sẽ làm, không gọi AI/API đích.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class StartGenerationJobRequest {
    private List<UUID> endpointIds;
    private String type;
    private Boolean dryRun;
    /** Trần token AI cho cả job (0/null = mặc định 30.000 mỗi endpoint). */
    private Integer tokenCap;
    private Boolean confirmWrite;
    private Integer maxRepairRounds;
    private Map<String, String> pathParamValues;
    private Boolean fillCoverage;
    // Chỉ cho LEGACY: các nhóm đã tick
    private Boolean includePositive;
    private Boolean includeNegative;
    private Boolean includeBoundary;
    private Boolean includeSecurity;
    /** Phạm vi probe OWASP (mass assignment, request lỗi định dạng, TRACE): 0 token, chỉ gửi thêm request tới API đích. Mặc định tắt. */
    private Boolean includeOwasp;
    private Boolean includeBusiness;
    // Chỉ cho RULES (trích luật nghiệp vụ): tài liệu được chọn (null/rỗng = tất cả), chỉ dẫn thêm, có nhận luật GIẢ ĐỊNH không
    private List<UUID> documentIds;
    private String instruction;
    private Boolean allowInferred;
    /** FULL/SYSTEM: sau khi sinh test gốc, AI lập checklist nghiệp vụ (documentIds/instruction/suggest/rulesTokenBudget áp dụng cho pha này). */
    private Boolean businessChecklist;
    /**
     * Chỉ lập lại checklist nghiệp vụ cho endpoint đã có test gốc Pass (dùng khi người dùng trả lời câu hỏi của AI): KHÔNG sinh/chạy lại test gốc, KHÔNG đổi
     * phạm vi nhóm test của endpoint, không cần xác nhận ghi thật. Cần businessChecklist=true.
     */
    private Boolean checklistOnly;
    /** Bật làn "AI gợi ý thêm" (ngoài tài liệu, GIẢ ĐỊNH). Tự bật khi không có tài liệu lẫn chỉ dẫn. */
    private Boolean suggest;
    /**
     * Phân công tài liệu RIÊNG cho từng endpoint (endpointId -> danh sách documentId). Endpoint có trong map dùng đúng danh sách của nó (rỗng = không dùng
     * tài liệu nào, chỉ chỉ dẫn/gợi ý); endpoint không có trong map dùng documentIds chung (hoặc tất cả tài liệu nếu cũng rỗng).
     */
    private Map<UUID, List<UUID>> documentsByEndpoint;
    /** Ngân sách token CỨNG riêng của pha trích checklist (mặc định 60.000, kẹp 5.000..1.000.000). */
    private Integer rulesTokenBudget;
}
