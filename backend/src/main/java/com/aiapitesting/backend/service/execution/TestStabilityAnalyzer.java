package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.entity.StabilityState;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.service.execution.FailureCauseClassifier.HistoryPoint;

import java.util.List;

/**
 * Module 16 giai đoạn 2 - đánh giá TẤT ĐỊNH độ ổn định kết quả của 1 test case từ lịch sử các lần chạy CÙNG phiên bản
 * test (đã lọc theo fingerprint ở nơi gọi). Thuần túy, không DB, không AI, không phụ thuộc target cụ thể.
 *
 * Chỉ xét các lần Pass/Fail "có nghĩa": bỏ ERROR/BLOCKED và bỏ các lần Fail do môi trường/token/cấu hình test - test
 * Pass khi có token và Fail khi mất token là test nhạy cảm với token, KHÔNG phải "chập chờn". Kết quả là nhận định về
 * tính tất định của KẾT QUẢ, không khẳng định nguyên nhân (black-box không biết nằm ở test hay API đích).
 *
 * CÁC NGƯỠNG LÀ THAM SỐ THIẾT KẾ DO NHÓM TỰ ĐẶT, CHƯA calibrate bằng dữ liệu thực nghiệm.
 */
public final class TestStabilityAnalyzer {

    /** Cần ít nhất chừng này lần chạy (Pass/Fail) cùng phiên bản mới gọi 1 test là "không ổn định". */
    public static final int MIN_RUNS_FOR_FLAKY = 5;
    /** Số lần đổi Pass↔Fail tối thiểu để gọi là xen kẽ (đổi đúng 1 lần là "trạng thái đổi", không phải chập chờn). */
    public static final int MIN_FLIPS_FOR_FLAKY = 2;

    private TestStabilityAnalyzer() {
    }

    /**
     * @param flips        số lần kết quả đổi giữa Pass và Fail trong các lần chạy tính được
     * @param rerunReason  khi {@code state = NEEDS_MORE_RUNS}: vì sao nên chạy lại; còn lại null
     */
    public record Assessment(StabilityState state, int runs, int passes, int fails, int flips, String rerunReason) {
        public double passRate() {
            return runs == 0 ? 0 : (double) passes / runs;
        }
    }

    public static Assessment assess(List<HistoryPoint> history) {
        List<HistoryPoint> verdicts = history == null ? List.<HistoryPoint>of() : history.stream()
                .filter(h -> h.status() == TestResultStatus.PASSED || h.status() == TestResultStatus.FAILED)
                .filter(h -> h.cause() == null || !h.cause().isNotApiFault())
                .toList();
        int runs = verdicts.size();
        int passes = (int) verdicts.stream().filter(h -> h.status() == TestResultStatus.PASSED).count();
        int flips = 0;
        for (int i = 1; i < runs; i++) {
            if (verdicts.get(i).status() != verdicts.get(i - 1).status()) {
                flips++;
            }
        }
        if (runs < 2) {
            return new Assessment(StabilityState.INSUFFICIENT, runs, passes, runs - passes, flips, null);
        }
        boolean lastTwoDiffer = verdicts.get(runs - 1).status() != verdicts.get(runs - 2).status();
        if (flips >= MIN_FLIPS_FOR_FLAKY && runs >= MIN_RUNS_FOR_FLAKY) {
            return new Assessment(StabilityState.FLAKY, runs, passes, runs - passes, flips, null);
        }
        // Chưa đủ để kết luận NHƯNG đã có dấu hiệu: đổi qua lại từ 2 lần, hoặc lần gần nhất vừa khác lần trước.
        if (flips >= MIN_FLIPS_FOR_FLAKY) {
            return new Assessment(StabilityState.NEEDS_MORE_RUNS, runs, passes, runs - passes, flips,
                    "Kết quả đã đổi qua lại " + flips + " lần trong " + runs + " lần chạy cùng phiên bản test - nên chạy lại thêm "
                            + "để có khoảng " + MIN_RUNS_FOR_FLAKY + " lần rồi mới kết luận có phải test không ổn định");
        }
        if (lastTwoDiffer) {
            String before = verdicts.get(runs - 2).status() == TestResultStatus.PASSED ? "Pass" : "Fail";
            String now = verdicts.get(runs - 1).status() == TestResultStatus.PASSED ? "Pass" : "Fail";
            return new Assessment(StabilityState.NEEDS_MORE_RUNS, runs, passes, runs - passes, flips,
                    "Lần chạy gần nhất cho kết quả khác lần trước (" + before + " → " + now + ") - nên chạy lại để xem có lặp lại không");
        }
        return new Assessment(StabilityState.STABLE, runs, passes, runs - passes, flips, null);
    }
}
