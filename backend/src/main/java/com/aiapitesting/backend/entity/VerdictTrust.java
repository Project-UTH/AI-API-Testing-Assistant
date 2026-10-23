package com.aiapitesting.backend.entity;

/**
 * 17q G2 - độ tin của KẾT LUẬN rút ra từ 1 lần chạy (khác với nhãn {@link TestCaseReliability} vốn chỉ nói "test có khớp spec").
 *
 * - CERTAIN: kết luận đủ chắc - Pass có oracle mạnh, hoặc Fail có nhãn lỗi API rõ ràng.
 * - DOUBTFUL: đã chạy ra kết quả nhưng kết luận còn nghi - cần người xem (Fail chưa rõ nguyên nhân, kỳ vọng do AI viết/giả định...).
 * - NO_VERDICT: lần chạy không nói gì về API (môi trường/token/cấu hình test, test không ổn định, ERROR/BLOCKED, chưa chạy).
 *
 * Chỉ dùng để HIỂN THỊ và đếm độ phủ; KHÔNG chặn tạo/mở lại bug report (Fail nghi vẫn phải báo được - nguyên tắc cốt lõi).
 */
public enum VerdictTrust {
    CERTAIN,
    DOUBTFUL,
    NO_VERDICT;

    /**
     * @param strongPassOracle với PASSED: true khi Pass đủ làm bằng chứng (test kỳ vọng thành công/bảo mật/phân quyền - oracle là mã
     *                         trạng thái; test âm thì lý do API từ chối phải nhắc đúng field đang kiểm). Bỏ qua khi không phải PASSED.
     * @param assumedOracle    kỳ vọng do AI viết/giả định (test nghiệp vụ, test bị đánh dấu "kỳ vọng cần xem") hoặc mục "spec thiếu
     *                         ràng buộc": Fail của các test này tối đa là DOUBTFUL.
     */
    public static VerdictTrust of(TestResultStatus status, FailureCause cause, Boolean unstable,
                                  boolean strongPassOracle, boolean assumedOracle) {
        if (status == null || Boolean.TRUE.equals(unstable)) {
            return NO_VERDICT;
        }
        return switch (status) {
            case PASSED -> strongPassOracle ? CERTAIN : DOUBTFUL;
            case FAILED -> {
                VerdictTrust base = ofFailure(cause);
                yield base == CERTAIN && assumedOracle ? DOUBTFUL : base;
            }
            default -> NO_VERDICT; // ERROR, BLOCKED, SKIPPED
        };
    }

    /** Ánh xạ thuần theo nhãn nguyên nhân Fail (null = kết quả cũ chưa được phân loại → nghi). */
    public static VerdictTrust ofFailure(FailureCause cause) {
        if (cause == null) {
            return DOUBTFUL;
        }
        if (cause.isNotApiFault() || cause == FailureCause.FLAKY) {
            return NO_VERDICT;
        }
        return switch (cause) {
            case STABLE_FAILURE, AUTHZ_VIOLATION, MASS_ASSIGNMENT, API_WRONG_STATUS, RULE_VIOLATION_ACCEPTED, HTTP_ROBUSTNESS -> CERTAIN;
            default -> DOUBTFUL; // ASSERTION_FAILED, DATA_STATE, UNDETERMINED, EXPECTATION_MISMATCH, STATE_CONFLICT, TEST_DATA_SUSPECT, TOKEN_FORBIDDEN
        };
    }
}
