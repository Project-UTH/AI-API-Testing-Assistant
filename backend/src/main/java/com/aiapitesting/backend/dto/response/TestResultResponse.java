package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.BugReport;
import com.aiapitesting.backend.entity.ErrorKind;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseGenerationOrigin;
import com.aiapitesting.backend.entity.TestCaseReliability;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.entity.VerdictTrust;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

public record TestResultResponse(
        UUID id,
        UUID testCaseId,
        String testCaseName,
        UUID endpointId,
        TestResultStatus status,
        Integer expectedStatus,
        Integer responseStatus,
        String responseBody,
        String errorMessage,
        List<AssertionResultResponse> assertionResults,
        /** Bug Report đã tồn tại từ lần chạy này (nếu có) - null nếu chưa ai tạo. Cho phép frontend
         *  (trang Kết quả thực thi) hiện rõ dòng nào đã có bug rồi thay vì để người dùng tick lại
         *  vô ích, và cho bấm thẳng tới đúng bug đó (deep-link ?bugReportId=). */
        UUID existingBugReportId,
        String existingBugId,
        /** Module 16 - thời gian gọi target API (ms); null khi không gọi target hoặc kết quả trước Module 16. */
        Long durationMs,
        /** Module 16 - loại lỗi hạ tầng khi gọi target (chỉ ERROR); null với mọi trường hợp khác. */
        ErrorKind errorKind,
        /** Module 16 - nguyên nhân nghi ngờ kèm bằng chứng cho FAILED/ERROR; null cho PASSED/BLOCKED/kết quả cũ. */
        FailureCauseResponse failureCause,
        /** Module 16 gđ2 - HTTP method của endpoint (frontend dùng để cảnh báo khi chạy lại test có thể ghi dữ liệu). */
        String endpointMethod,
        /** Module 16 gđ2 - độ ổn định hiện tại của test case; null khi không đáng chú ý (ổn định/chưa đủ dữ liệu) hoặc lượt chạy chưa xong. */
        StabilityResponse stability,
        /** Cảnh báo phản hồi thật lệch schema response của spec (kiểu/trường bắt buộc); rỗng = khớp hoặc không kiểm. Không đổi Pass/Fail. */
        List<String> schemaViolations,
        /** Module 17 (17b) - cảnh báo dữ liệu nhạy cảm trong response (không chứa giá trị); rỗng = không có. Không đổi Pass/Fail. */
        List<SecurityFindingResponse> securityFindings,
        /** 17q G1 - cảnh báo mã thành công lệch spec; rỗng = không lệch hoặc không đủ căn cứ. Không đổi Pass/Fail. */
        List<String> specDeviations,
        /**
         * 17q G2 - độ tin kết luận của lần chạy này. Pass của test âm cần biết field đang kiểm (chỉ trang Coverage có) nên để null ở
         * đây; null cũng cho Pass/Fail chưa đánh giá được. Chỉ để hiển thị - không ảnh hưởng tạo/mở lại bug report.
         */
        VerdictTrust verdictTrust
) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static TestResultResponse from(TestResult result, BugReport existingBug) {
        return from(result, existingBug, null);
    }

    public static TestResultResponse from(TestResult result, BugReport existingBug, StabilityResponse stability) {
        return new TestResultResponse(
                result.getId(),
                result.getTestCase().getId(),
                result.getTestCase().getName(),
                result.getTestCase().getEndpoint().getId(),
                result.getStatus(),
                result.getTestCase().getExpectedStatus(),
                result.getResponseStatus(),
                result.getResponseBody(),
                result.getErrorMessage(),
                parseAssertionResults(result.getAssertionResultsJson()),
                existingBug == null ? null : existingBug.getId(),
                existingBug == null ? null : existingBug.getBugId(),
                result.getDurationMs(),
                result.getErrorKind(),
                result.getFailureCause() == null ? null
                        : FailureCauseResponse.of(result.getFailureCause(), parseEvidence(result.getFailureEvidence())),
                result.getTestCase().getEndpoint().getMethod(),
                stability,
                parseEvidence(result.getSchemaViolationsJson()),
                parseFindings(result.getSecurityFindingsJson()),
                parseEvidence(result.getSpecDeviationsJson()),
                verdictTrustOf(result)
        );
    }

    private static VerdictTrust verdictTrustOf(TestResult result) {
        TestCase testCase = result.getTestCase();
        Integer expected = testCase.getExpectedStatus();
        if (result.getStatus() == TestResultStatus.PASSED && !Boolean.TRUE.equals(result.getUnstable())
                && expected != null && (expected == 400 || expected == 422)) {
            return null; // Pass vì bị từ chối đầu vào: cần đối chiếu field đang kiểm - xem ở trang Coverage (401/403/404 tự nó là oracle)
        }
        // Cùng quy tắc với trang Coverage: kỳ vọng do AI viết/"kỳ vọng cần xem" hoặc mục biên ngầm → Fail tối đa NGHI; Pass mà mã thành
        // công lệch spec → NGHI.
        boolean assumed = com.aiapitesting.backend.service.EndpointCoverageService.isAssumedOracle(testCase)
                || com.aiapitesting.backend.service.EndpointCoverageService.isUnderspecifiedTest(testCase);
        boolean strongPass = result.getSpecDeviationsJson() == null;
        return VerdictTrust.of(result.getStatus(), result.getFailureCause(), result.getUnstable(), strongPass, assumed);
    }

    private static List<SecurityFindingResponse> parseFindings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<List<SecurityFindingResponse>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<String> parseEvidence(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of(); // JSON hỏng không được làm trang Kết quả thực thi trả 500
        }
    }

    private static List<AssertionResultResponse> parseAssertionResults(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<List<AssertionResultResponse>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
