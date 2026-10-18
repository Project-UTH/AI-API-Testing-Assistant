package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.dto.response.StabilityResponse;
import com.aiapitesting.backend.entity.FailureCause;
import com.aiapitesting.backend.entity.StabilityState;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.service.execution.FailureCauseClassifier.HistoryPoint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Module 16 giai đoạn 2 - đánh giá độ ổn định: tất định, không DB. Test các mẫu kết quả qua các lần chạy và các ca dễ
 * nhầm (đổi 1 lần không phải chập chờn; fail do token không phải chập chờn; thiếu lần chạy thì chỉ gợi ý).
 */
class TestStabilityAnalyzerTest {

    private static final Instant T0 = Instant.parse("2026-10-10T08:00:00Z");

    /** "PFPFP" → danh sách lần chạy; chữ F = Fail(500), P = Pass, E = ERROR (kết nối), A = Fail do token. */
    private static List<HistoryPoint> runs(String pattern) {
        List<HistoryPoint> history = new ArrayList<>();
        int minute = 0;
        for (char c : pattern.toCharArray()) {
            Instant at = T0.plusSeconds(60L * minute++);
            history.add(switch (c) {
                case 'P' -> new HistoryPoint(TestResultStatus.PASSED, 200, at, "u");
                case 'F' -> new HistoryPoint(TestResultStatus.FAILED, 500, at, "u");
                case 'E' -> new HistoryPoint(TestResultStatus.ERROR, null, at, "u");
                case 'A' -> new HistoryPoint(TestResultStatus.FAILED, 403, at, "u", FailureCause.AUTH_CONFIG);
                case 'X' -> new HistoryPoint(TestResultStatus.FAILED, 500, at, "u", FailureCause.FLAKY);
                default -> throw new IllegalArgumentException("ky tu " + c);
            });
        }
        return history;
    }

    private static TestStabilityAnalyzer.Assessment assess(String pattern) {
        return TestStabilityAnalyzer.assess(runs(pattern));
    }

    // ---- FLAKY ----

    @Test
    void alternatingFiveRuns_isFlakyWithCounts() {
        TestStabilityAnalyzer.Assessment a = assess("PFPFP");

        assertThat(a.state()).isEqualTo(StabilityState.FLAKY);
        assertThat(a.runs()).isEqualTo(5);
        assertThat(a.passes()).isEqualTo(3);
        assertThat(a.fails()).isEqualTo(2);
        assertThat(a.flips()).isEqualTo(4);
        assertThat(a.passRate()).isEqualTo(0.6);
        assertThat(a.rerunReason()).isNull();
    }

    @Test
    void twoFlipsInFiveRuns_isFlaky() {
        assertThat(assess("PPFFP").state()).isEqualTo(StabilityState.FLAKY);
        assertThat(assess("PFFFP").state()).isEqualTo(StabilityState.FLAKY);
    }

    @Test
    void manyRunsWithOccasionalFail_isFlaky() {
        assertThat(assess("PPPPPFPPPPPFP").state()).isEqualTo(StabilityState.FLAKY);
    }

    // ---- NEEDS_MORE_RUNS ----

    @Test
    void fewRunsButAlreadyAlternating_suggestsRerunToReachFiveRuns() {
        TestStabilityAnalyzer.Assessment a = assess("PFP");

        assertThat(a.state()).isEqualTo(StabilityState.NEEDS_MORE_RUNS);
        assertThat(a.rerunReason()).contains("2 lần trong 3 lần chạy").contains("khoảng 5 lần");
    }

    @Test
    void justChangedResult_suggestsRerunToSeeIfItRepeats() {
        TestStabilityAnalyzer.Assessment a = assess("PF");

        assertThat(a.state()).isEqualTo(StabilityState.NEEDS_MORE_RUNS);
        assertThat(a.rerunReason()).contains("Pass → Fail").contains("lặp lại");
    }

    @Test
    void longStablePassThenSuddenFail_isOnlyASuggestionNotFlaky() {
        // 1 lần đổi sau 5 lần Pass: có thể tài nguyên vừa bị xoá - chưa thể gọi là chập chờn, chỉ gợi ý chạy lại.
        TestStabilityAnalyzer.Assessment a = assess("PPPPPF");

        assertThat(a.state()).isEqualTo(StabilityState.NEEDS_MORE_RUNS);
        assertThat(a.flips()).isEqualTo(1);
    }

    // ---- STABLE / INSUFFICIENT ----

    @Test
    void changedOnceThenSettled_isNotFlaky() {
        // Pass rồi Fail liên tiếp: nghi dữ liệu/trạng thái đổi (nhãn DATA_STATE) - không gợi ý chạy lại, không phải chập chờn.
        assertThat(assess("PPFF").state()).isEqualTo(StabilityState.STABLE);
        assertThat(assess("PPPFFF").state()).isEqualTo(StabilityState.STABLE);
    }

    @Test
    void identicalResults_areStable() {
        assertThat(assess("PPPPP").state()).isEqualTo(StabilityState.STABLE);
        assertThat(assess("FFFFF").state()).isEqualTo(StabilityState.STABLE);
    }

    @Test
    void lessThanTwoRuns_isInsufficient() {
        assertThat(assess("P").state()).isEqualTo(StabilityState.INSUFFICIENT);
        assertThat(TestStabilityAnalyzer.assess(List.of()).state()).isEqualTo(StabilityState.INSUFFICIENT);
        assertThat(TestStabilityAnalyzer.assess(null).state()).isEqualTo(StabilityState.INSUFFICIENT);
    }

    // ---- Những lần chạy không tính ----

    @Test
    void errorsAreIgnored_connectionFailureIsNotAVerdict() {
        // 4 lần Pass xen các lần ERROR (mất kết nối): không có Pass↔Fail nào nên không phải chập chờn.
        assertThat(assess("PEPEPEPP").state()).isEqualTo(StabilityState.STABLE);
    }

    /** Test Pass khi có token, Fail khi mất token: nhạy cảm với token, KHÔNG phải chập chờn. */
    @Test
    void failsCausedByTokenOrEnvironmentAreIgnored() {
        TestStabilityAnalyzer.Assessment a = assess("PAPAPAP");

        assertThat(a.state()).isEqualTo(StabilityState.STABLE);
        assertThat(a.runs()).isEqualTo(4);
        assertThat(a.flips()).isZero();
    }

    @Test
    void flakyCauseFailsStillCountAsVerdicts() {
        // Nhãn FLAKY không thuộc nhóm "không phải do API" nên lần Fail đó vẫn là 1 lần chạy có nghĩa.
        assertThat(assess("PXPXP").state()).isEqualTo(StabilityState.FLAKY);
    }

    // ---- Thông số + phản hồi ----

    @Test
    void thresholdsAreTheDocumentedDesignValues() {
        assertThat(TestStabilityAnalyzer.MIN_RUNS_FOR_FLAKY).isEqualTo(5);
        assertThat(TestStabilityAnalyzer.MIN_FLIPS_FOR_FLAKY).isEqualTo(2);
    }

    @Test
    void response_marksMethodsThatCanChangeTargetData() {
        TestStabilityAnalyzer.Assessment a = assess("PFPFP");

        for (String method : List.of("POST", "PUT", "PATCH", "DELETE", "post")) {
            assertThat(StabilityResponse.of(a, method).mutatesData()).as(method).isTrue();
        }
        for (String method : List.of("GET", "HEAD", "OPTIONS", "get")) {
            assertThat(StabilityResponse.of(a, method).mutatesData()).as(method).isFalse();
        }
        assertThat(StabilityResponse.of(a, null).mutatesData()).as("method lạ coi là có thể ghi - an toàn hơn").isTrue();
        assertThat(StabilityResponse.of(a, "GET").passRate()).isEqualTo(0.6);
    }
}
