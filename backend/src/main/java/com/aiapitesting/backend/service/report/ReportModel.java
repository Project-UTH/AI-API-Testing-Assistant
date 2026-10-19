package com.aiapitesting.backend.service.report;

import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.entity.TestResultStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Dữ liệu báo cáo đã LÀM SẠCH: chỉ gồm các field cho phép (whitelist) và mọi chuỗi tự do đã qua {@link SecretMasker}. Không có
 * request header, request body, response body hay token - builder chỉ nhìn thấy model này nên không thể rò rỉ chúng.
 */
public record ReportModel(
        String projectName, UUID executionId, Instant startedAt, Instant finishedAt, ExecutionStatus status,
        String targetBaseUrl, ExecutionConclusionCalculator.Summary summary, ExecutionConclusionCalculator.Conclusion conclusion,
        List<EndpointGroup> endpoints
) {

    public record EndpointGroup(String method, String path, List<Case> cases) {
    }

    /**
     * @param failureCause nguyên nhân Fail (mã enum) hoặc null; notApiFault = bằng chứng Fail KHÔNG phải do API (môi trường/token...)
     * @param message      1 dòng tóm tắt đã che/cắt (mã trả về, lỗi gọi...)
     * @param evidence     tối đa vài dòng bằng chứng đã che/cắt
     * @param securityFindings cảnh báo dữ liệu nhạy cảm ("đường.dẫn [QUY_TẮC] - mô tả"), KHÔNG có giá trị thật
     */
    public record Case(
            String name, TestResultStatus status, Integer expectedStatus, Integer responseStatus, Long durationMs,
            String failureCause, String failureCauseLabel, boolean notApiFault, boolean unstable, String message, List<String> evidence,
            List<String> securityFindings
    ) {
    }
}
