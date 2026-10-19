package com.aiapitesting.backend.service.report;

import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.entity.FailureCause;
import com.aiapitesting.backend.entity.TestResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Module 17 (17d) - tóm tắt 1 lượt chạy và KẾT LUẬN để CI quyết định pass/fail pipeline. Thuần tuý, không DB. Nguyên tắc (cùng triết
 * lý với bug report/Coverage): Fail do môi trường/token/cấu hình test/id không tồn tại không nói gì về API nên mặc định KHÔNG làm
 * pipeline đỏ (chế độ API_FAIL), nhưng luôn kèm cảnh báo; ai muốn nghiêm ngặt chọn ANY_FAIL.
 */
public final class ExecutionConclusionCalculator {

    private ExecutionConclusionCalculator() {
    }

    public enum FailOn {
        /** Đỏ khi có Fail do API (mặc định). */
        API_FAIL,
        /** Đỏ khi có bất kỳ Fail/Error/Blocked nào. */
        ANY_FAIL
    }

    public enum Conclusion {
        PENDING, PASSED, FAILED, ERROR
    }

    /**
     * @param apiFailed   Fail mà nguyên nhân KHÔNG phải môi trường/token/cấu hình test và test không "không ổn định"
     * @param notApiFault Fail do môi trường/token/cấu hình test/id không tồn tại
     * @param flaky       kết quả của test đang không ổn định (không tính vào apiFailed)
     * @param needsReview Fail cần xem lại bằng tay, KHÔNG đủ bằng chứng là lỗi API: nghi dữ liệu test sai, hoặc "chưa đủ dữ liệu" mà API
     *                    trả 4xx (vd test gọi nhầm token vai trò). Không làm đỏ pipeline ở API_FAIL, nhưng luôn có cảnh báo. Fail "chưa
     *                    đủ dữ liệu" với 5xx/không có phản hồi vẫn tính là apiFailed (không để lỗi server lọt qua lần chạy đầu).
     */
    public record Summary(int total, int passed, int failed, int apiFailed, int notApiFault, int error, int blocked, int skipped, int flaky,
                          int needsReview) {
    }

    public record Result(Summary summary, Conclusion conclusion, List<String> warnings) {
    }

    /** Fail chưa đủ bằng chứng để gọi là lỗi API: nghi dữ liệu test, hoặc "chưa đủ dữ liệu" với phản hồi 4xx. 5xx/không phản hồi thì không. */
    private static boolean needsReview(TestResult r) {
        FailureCause cause = r.getFailureCause();
        if (cause == FailureCause.TEST_DATA_SUSPECT || cause == FailureCause.TOKEN_FORBIDDEN) {
            return true;
        }
        Integer status = r.getResponseStatus();
        return cause == FailureCause.UNDETERMINED && status != null && status >= 400 && status < 500;
    }

    public static Result calculate(ExecutionStatus executionStatus, List<TestResult> results, FailOn failOn) {
        int passed = 0, failed = 0, apiFailed = 0, notApiFault = 0, error = 0, blocked = 0, skipped = 0, flaky = 0, needsReview = 0;
        for (TestResult r : results) {
            boolean unstable = Boolean.TRUE.equals(r.getUnstable()) || r.getFailureCause() == FailureCause.FLAKY;
            if (unstable) {
                flaky++;
            }
            switch (r.getStatus()) {
                case PASSED -> passed++;
                case FAILED -> {
                    failed++;
                    if (r.getFailureCause() != null && r.getFailureCause().isNotApiFault()) {
                        notApiFault++;
                    } else if (!unstable) {
                        if (needsReview(r)) {
                            needsReview++;
                        } else {
                            apiFailed++;
                        }
                    }
                }
                case ERROR -> error++;
                case BLOCKED -> blocked++;
                case SKIPPED -> skipped++;
            }
        }
        Summary summary = new Summary(results.size(), passed, failed, apiFailed, notApiFault, error, blocked, skipped, flaky, needsReview);

        List<String> warnings = new ArrayList<>();
        if (notApiFault > 0) {
            warnings.add(notApiFault + " kết quả Fail do môi trường/token/cấu hình test (không tính là lỗi API)");
        }
        if (needsReview > 0) {
            warnings.add(needsReview + " kết quả Fail cần xem lại (nghi dữ liệu test sai / chưa đủ dữ liệu để kết luận) - không làm đỏ pipeline "
                    + "ở chế độ API_FAIL, hãy kiểm tra thủ công");
        }
        if (error > 0) {
            warnings.add(error + " test lỗi khi gọi hệ thống đích (kết nối/timeout)");
        }
        if (blocked > 0) {
            warnings.add(blocked + " test bị chặn vì test nguồn (Test Data Chaining) không Pass");
        }
        if (flaky > 0) {
            warnings.add(flaky + " test không ổn định - không kết luận chắc chắn");
        }

        Conclusion conclusion;
        if (executionStatus == ExecutionStatus.PENDING || executionStatus == ExecutionStatus.RUNNING) {
            conclusion = Conclusion.PENDING;
        } else if (executionStatus == ExecutionStatus.FAILED) {
            conclusion = Conclusion.ERROR; // lỗi tầng điều phối: kết quả không đáng tin, CI nên coi là đỏ
        } else if (failOn == FailOn.ANY_FAIL) {
            conclusion = failed + error + blocked > 0 ? Conclusion.FAILED : Conclusion.PASSED;
        } else {
            conclusion = apiFailed > 0 ? Conclusion.FAILED : Conclusion.PASSED;
        }
        return new Result(summary, conclusion, List.copyOf(warnings));
    }
}
