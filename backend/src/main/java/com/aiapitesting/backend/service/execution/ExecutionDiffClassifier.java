package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.dto.response.ExecutionDiffCategory;
import com.aiapitesting.backend.entity.FailureCause;
import com.aiapitesting.backend.entity.TestResultStatus;

import java.util.Objects;

/**
 * Module 17 (17c) - phân loại thay đổi của CÙNG 1 test case giữa 2 lượt chạy. Thuần tuý (không DB) để test từng luật. Nguyên tắc:
 * chỉ gọi là HỒI QUY khi test không đổi, đủ tin cậy ở cả 2 lượt và Fail đến từ API (không phải môi trường/token/cấu hình test) - so sánh
 * nhầm là cách nhanh nhất để người dùng mất tin vào báo cáo.
 */
public final class ExecutionDiffClassifier {

    private ExecutionDiffClassifier() {
    }

    /** 1 kết quả đã rút gọn (đủ để so sánh, không chứa response). */
    public record Side(TestResultStatus status, FailureCause failureCause, Boolean unstable, String fingerprint) {
    }

    public record Verdict(ExecutionDiffCategory category, String note) {
    }

    private enum Outcome {
        PASS, FAIL, UNKNOWN
    }

    public static Verdict classify(Side base, Side target) {
        if (base == null) {
            return new Verdict(ExecutionDiffCategory.ONLY_IN_TARGET, "Test case chỉ có ở lượt sau (mới thêm/sinh)");
        }
        if (target == null) {
            return new Verdict(ExecutionDiffCategory.ONLY_IN_BASE, "Test case chỉ có ở lượt trước (đã bị xoá hoặc không chạy ở lượt sau)");
        }
        // Test đã sửa giữa 2 lượt (hoặc dữ liệu cũ chưa có fingerprint): kết quả khác nhau có thể do chính việc sửa test.
        if (base.fingerprint() == null || target.fingerprint() == null || !Objects.equals(base.fingerprint(), target.fingerprint())) {
            return new Verdict(ExecutionDiffCategory.CHANGED_TEST,
                    "Test case đã được sửa giữa 2 lượt (hoặc thiếu dấu vân tay phiên bản) - không so sánh trực tiếp được");
        }
        if (isUnreliable(base) || isUnreliable(target)) {
            return new Verdict(ExecutionDiffCategory.UNRELIABLE,
                    "Test case không ổn định (kết quả đổi qua lại dù nội dung không đổi) - không kết luận hồi quy/được sửa");
        }
        Outcome before = outcomeOf(base);
        Outcome after = outcomeOf(target);
        if (before == Outcome.UNKNOWN || after == Outcome.UNKNOWN) {
            return new Verdict(ExecutionDiffCategory.INCONCLUSIVE,
                    "Một trong hai lượt không có kết luận về API (bị chặn/lỗi kết nối hoặc Fail do môi trường/token/cấu hình test)");
        }
        if (before == Outcome.PASS && after == Outcome.PASS) {
            return new Verdict(ExecutionDiffCategory.STILL_PASSING, null);
        }
        if (before == Outcome.PASS) {
            return new Verdict(ExecutionDiffCategory.REGRESSED, "Pass ở lượt trước, Fail ở lượt sau");
        }
        if (after == Outcome.PASS) {
            return new Verdict(ExecutionDiffCategory.FIXED, "Fail ở lượt trước, Pass ở lượt sau");
        }
        return new Verdict(ExecutionDiffCategory.STILL_FAILING, null);
    }

    private static boolean isUnreliable(Side side) {
        return Boolean.TRUE.equals(side.unstable()) || side.failureCause() == FailureCause.FLAKY;
    }

    private static Outcome outcomeOf(Side side) {
        if (side.status() == TestResultStatus.PASSED) {
            return Outcome.PASS;
        }
        if (side.status() == TestResultStatus.FAILED) {
            // Fail do môi trường/token/cấu hình test/id không tồn tại: không nói gì về API.
            return side.failureCause() != null && side.failureCause().isNotApiFault() ? Outcome.UNKNOWN : Outcome.FAIL;
        }
        return Outcome.UNKNOWN; // ERROR, BLOCKED, SKIPPED
    }
}
