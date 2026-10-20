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
 * <p>type: FULL (AI sinh test gốc + chạy xác nhận + hệ thống bổ sung; mặc định khi chọn 1 endpoint), SYSTEM (chỉ hệ thống, 0 token; mặc định khi chọn
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
    private Boolean includeBusiness;
}
