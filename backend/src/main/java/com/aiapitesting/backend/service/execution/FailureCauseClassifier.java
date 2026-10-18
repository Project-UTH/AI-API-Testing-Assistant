package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.entity.ErrorKind;
import com.aiapitesting.backend.entity.FailureCause;
import com.aiapitesting.backend.entity.TestCaseAuthOverride;
import com.aiapitesting.backend.entity.TestResultStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Module 16 - phân loại TẤT ĐỊNH nguyên nhân nghi ngờ của 1 kết quả FAILED/ERROR. Không gọi AI, không đụng DB
 * (đầu vào đã gom sẵn ở {@link ExecutionFailureAnalyzer}), không phụ thuộc target cụ thể nào: chỉ dựa vào mã
 * trạng thái HTTP chuẩn, loại lỗi kết nối, thời gian phản hồi và mẫu kết quả qua các lần chạy CÙNG phiên bản test.
 *
 * Luật đầu tiên khớp thắng: TEST_SETUP → ENVIRONMENT → AUTH_CONFIG → DATA_STATE → STABLE_FAILURE → UNDETERMINED.
 * Kết quả luôn là "nghi ngờ kèm bằng chứng", không khẳng định - black-box không thấy bên trong hệ thống đích.
 *
 * CÁC NGƯỠNG DƯỚI ĐÂY LÀ THAM SỐ THIẾT KẾ DO NHÓM TỰ ĐẶT, CHƯA ĐƯỢC calibrate bằng dữ liệu thực nghiệm (cùng mức
 * rigor đã ghi cho Risk Engine ở Module 15c).
 */
@Component
public class FailureCauseClassifier {

    /** Cần ít nhất chừng này lần chạy (Pass/Fail) cùng phiên bản test mới gọi là "lỗi ổn định". */
    static final int MIN_RUNS_FOR_STABLE_FAILURE = 3;
    /** "Bậc thang" Pass→Fail: cần ít nhất chừng này lần Fail liên tiếp (gồm lần hiện tại) sau chuỗi Pass. */
    static final int MIN_CONSECUTIVE_FAILS_FOR_DATA_STATE = 2;
    /** Thời gian phản hồi từ mức này của giới hạn chờ trở lên được ghi là "sát giới hạn timeout". */
    static final double NEAR_TIMEOUT_RATIO = 0.9;

    private static final Set<Integer> ENVIRONMENT_STATUSES = Set.of(502, 503, 504, 429);
    private static final Set<Integer> AUTH_STATUSES = Set.of(401, 403);
    /** Mã thường gặp khi tài nguyên/trạng thái đổi (RFC 9110) - CHỈ là tín hiệu phụ, mỗi API dùng mã lỗi một kiểu. */
    private static final Set<Integer> STATE_STATUSES = Set.of(404, 409, 410, 412);

    /** 1 lần chạy trong lịch sử cùng phiên bản test, theo thứ tự thời gian (lần hiện tại là phần tử cuối). */
    public record HistoryPoint(TestResultStatus status, Integer responseStatus, Instant at, String baseUrl) {
    }

    /**
     * Số liệu "anh em" cùng 1 lượt chạy: dùng để nói "N/M test cùng lỗi" - bằng chứng mạnh hơn 1 kết quả riêng lẻ.
     */
    public record ExecutionContext(int sameKindErrorCount, int calledCount, int defaultAuthUnauthorizedCount, int defaultAuthCalledCount) {
        public static ExecutionContext none() {
            return new ExecutionContext(0, 0, 0, 0);
        }
    }

    public record Input(
            TestResultStatus status, Integer expectedStatus, Integer responseStatus, ErrorKind errorKind, Long durationMs,
            String errorMessage, TestCaseAuthOverride authOverride, List<HistoryPoint> history, String currentBaseUrl,
            Instant authUpdatedAt, ExecutionContext context
    ) {
    }

    public record Result(FailureCause cause, List<String> evidence) {
    }

    /** Trả null nếu kết quả không phải FAILED/ERROR (PASSED/BLOCKED/SKIPPED không có "nguyên nhân fail"). */
    public Result classify(Input in) {
        if (in.status() != TestResultStatus.FAILED && in.status() != TestResultStatus.ERROR) {
            return null;
        }
        List<HistoryPoint> history = in.history() == null ? List.of() : in.history();
        ExecutionContext ctx = in.context() == null ? ExecutionContext.none() : in.context();

        // 1. ERROR không gọi được target vì cấu hình test (thiếu giá trị tham số...) - không liên quan API/môi trường.
        if (in.status() == TestResultStatus.ERROR && in.errorKind() == null) {
            return new Result(FailureCause.TEST_SETUP, evidenceList(
                    "Test không gọi được hệ thống đích vì cấu hình test chưa đủ",
                    in.errorMessage()));
        }

        // 2. Môi trường: lỗi kết nối/timeout/DNS, hoặc target báo quá tải/cổng lỗi.
        if (in.errorKind() != null) {
            return new Result(FailureCause.ENVIRONMENT, environmentEvidence(in, history, ctx));
        }
        if (in.responseStatus() != null && ENVIRONMENT_STATUSES.contains(in.responseStatus())) {
            List<String> evidence = new ArrayList<>();
            evidence.add("Hệ thống đích trả " + in.responseStatus() + " (" + describeEnvironmentStatus(in.responseStatus())
                    + ") trong khi kỳ vọng " + in.expectedStatus());
            addBaseUrlChange(evidence, in, history);
            return new Result(FailureCause.ENVIRONMENT, evidence);
        }

        // 3. Xác thực: nhận 401/403 ngoài kỳ vọng khi test dùng token mặc định của project. Case Security cố ý
        //    bỏ/sai token (authOverride NONE/INVALID) thì 401 là CHỦ ĐÍCH - không bao giờ gắn nhãn này.
        if (isUnexpectedAuthFailure(in)) {
            return new Result(FailureCause.AUTH_CONFIG, authEvidence(in, history, ctx));
        }

        // 4-6. Phân loại theo mẫu kết quả qua các lần chạy cùng phiên bản test.
        List<HistoryPoint> verdicts = history.stream()
                .filter(h -> h.status() == TestResultStatus.PASSED || h.status() == TestResultStatus.FAILED)
                .toList();
        if (in.status() == TestResultStatus.FAILED) {
            Result dataState = dataState(in, verdicts);
            if (dataState != null) {
                return dataState;
            }
            Result stable = stableFailure(verdicts);
            if (stable != null) {
                return stable;
            }
        }
        return new Result(FailureCause.UNDETERMINED, undeterminedEvidence(in, verdicts));
    }

    // ---- Môi trường ----

    private List<String> environmentEvidence(Input in, List<HistoryPoint> history, ExecutionContext ctx) {
        List<String> evidence = new ArrayList<>();
        evidence.add("Lỗi khi gọi hệ thống đích: " + in.errorKind().label());
        if (in.errorKind() == ErrorKind.TIMEOUT && in.durationMs() != null) {
            String near = in.durationMs() >= RestAssuredTestRunner.READ_TIMEOUT_MS * NEAR_TIMEOUT_RATIO
                    ? " (sát giới hạn chờ " + RestAssuredTestRunner.READ_TIMEOUT_MS + " ms)" : "";
            evidence.add("Chờ " + in.durationMs() + " ms trước khi bỏ cuộc" + near);
        }
        if (ctx.sameKindErrorCount() >= 2) {
            evidence.add(ctx.sameKindErrorCount() + "/" + ctx.calledCount()
                    + " test trong cùng lượt chạy cũng gặp lỗi kết nối - giống hệ thống đích đang sập/không tới được, "
                    + "không phải lỗi riêng test này");
        }
        addBaseUrlChange(evidence, in, history);
        if (in.errorMessage() != null && !in.errorMessage().isBlank()) {
            evidence.add("Thông báo gốc: " + in.errorMessage());
        }
        return evidence;
    }

    private String describeEnvironmentStatus(int status) {
        return switch (status) {
            case 429 -> "quá nhiều yêu cầu, bị giới hạn tốc độ";
            case 502 -> "cổng trung gian nhận phản hồi lỗi";
            case 503 -> "dịch vụ tạm thời không sẵn sàng";
            default -> "cổng trung gian hết thời gian chờ";
        };
    }

    /** Target Base URL lúc chạy khác lần chạy trước (cùng phiên bản test) là bằng chứng đáng chú ý khi mất kết nối. */
    private void addBaseUrlChange(List<String> evidence, Input in, List<HistoryPoint> history) {
        String current = in.currentBaseUrl();
        if (current == null || history.size() < 2) {
            return;
        }
        for (int i = history.size() - 2; i >= 0; i--) {
            String previous = history.get(i).baseUrl();
            if (previous != null) {
                if (!previous.equals(current)) {
                    evidence.add("Target Base URL khác lần chạy trước: " + previous + " → " + current);
                }
                return;
            }
        }
    }

    // ---- Xác thực ----

    private boolean isUnexpectedAuthFailure(Input in) {
        boolean usesDefaultAuth = in.authOverride() == null || in.authOverride() == TestCaseAuthOverride.DEFAULT;
        return in.status() == TestResultStatus.FAILED
                && usesDefaultAuth
                && in.responseStatus() != null && AUTH_STATUSES.contains(in.responseStatus())
                && (in.expectedStatus() == null || !AUTH_STATUSES.contains(in.expectedStatus()));
    }

    private List<String> authEvidence(Input in, List<HistoryPoint> history, ExecutionContext ctx) {
        List<String> evidence = new ArrayList<>();
        evidence.add("Nhận " + in.responseStatus() + " trong khi kỳ vọng " + in.expectedStatus()
                + "; test dùng token mặc định của project (không phải case Security cố ý bỏ/sai token)");
        if (ctx.defaultAuthUnauthorizedCount() >= 2) {
            evidence.add(ctx.defaultAuthUnauthorizedCount() + "/" + ctx.defaultAuthCalledCount()
                    + " test dùng token mặc định trong cùng lượt chạy cũng bị từ chối xác thực - nghi token sai/hết hạn "
                    + "hoặc hệ thống đích đổi cách xác thực");
        }
        HistoryPoint lastPass = null;
        for (HistoryPoint point : history) {
            if (point.status() == TestResultStatus.PASSED) {
                lastPass = point;
            }
        }
        if (lastPass != null && lastPass.at() != null) {
            evidence.add("Lần Pass gần nhất của test này lúc " + lastPass.at());
            if (in.authUpdatedAt() != null) {
                evidence.add(in.authUpdatedAt().isAfter(lastPass.at())
                        ? "Token của hệ thống đích được đặt/đổi lúc " + in.authUpdatedAt() + ", SAU lần Pass gần nhất"
                        : "Token của hệ thống đích được đặt/đổi lúc " + in.authUpdatedAt() + ", TRƯỚC lần Pass gần nhất "
                                + "(token không đổi kể từ đó - có thể token đã hết hạn tự nhiên)");
            }
        }
        return evidence;
    }

    // ---- Mẫu kết quả qua các lần chạy ----

    /** "Bậc thang": đã từng Pass, rồi Fail liên tiếp cùng 1 mã, KHÔNG xen kẽ (đúng 1 lần đổi trạng thái Pass→Fail). */
    private Result dataState(Input in, List<HistoryPoint> verdicts) {
        if (verdicts.size() < MIN_CONSECUTIVE_FAILS_FOR_DATA_STATE + 1 || changes(verdicts) != 1
                || verdicts.get(0).status() != TestResultStatus.PASSED) {
            return null;
        }
        int trailingFails = 0;
        Integer failStatus = verdicts.get(verdicts.size() - 1).responseStatus();
        for (int i = verdicts.size() - 1; i >= 0 && verdicts.get(i).status() == TestResultStatus.FAILED; i--) {
            if (!Objects.equals(verdicts.get(i).responseStatus(), failStatus)) {
                return null;
            }
            trailingFails++;
        }
        if (trailingFails < MIN_CONSECUTIVE_FAILS_FOR_DATA_STATE) {
            return null;
        }
        List<String> evidence = new ArrayList<>();
        evidence.add("Cùng phiên bản test: " + pattern(verdicts) + " - Pass rồi Fail liên tiếp " + trailingFails
                + " lần cùng mã " + failStatus + ", không xen kẽ");
        evidence.add("Giống dữ liệu/trạng thái của hệ thống đích đã đổi giữa các lần chạy (tài nguyên bị xoá/tạo, "
                + "dữ liệu trùng...), hơn là test chập chờn");
        if (failStatus != null && STATE_STATUSES.contains(failStatus)) {
            evidence.add("Mã " + failStatus + " thường gặp khi tài nguyên/trạng thái đổi (tín hiệu phụ, không bắt buộc)");
        }
        return new Result(FailureCause.DATA_STATE, evidence);
    }

    private Result stableFailure(List<HistoryPoint> verdicts) {
        if (verdicts.size() < MIN_RUNS_FOR_STABLE_FAILURE) {
            return null;
        }
        Integer status = verdicts.get(verdicts.size() - 1).responseStatus();
        boolean allSame = verdicts.stream()
                .allMatch(h -> h.status() == TestResultStatus.FAILED && Objects.equals(h.responseStatus(), status));
        if (!allSame) {
            return null;
        }
        return new Result(FailureCause.STABLE_FAILURE, List.of(
                "Cùng phiên bản test: " + pattern(verdicts) + " - " + verdicts.size() + " lần chạy đều Fail cùng mã " + status
                        + ", chưa từng Pass",
                "Có thể là bug thật của API, cũng có thể là kỳ vọng của test chưa đúng - kiểm thử black-box không phân "
                        + "biệt được 2 trường hợp này"));
    }

    private List<String> undeterminedEvidence(Input in, List<HistoryPoint> verdicts) {
        List<String> evidence = new ArrayList<>();
        if (in.status() == TestResultStatus.ERROR) {
            evidence.add("Lỗi khi gọi hệ thống đích nhưng chưa phân loại được nguyên nhân cụ thể");
            return evidence;
        }
        if (verdicts.size() <= 1) {
            evidence.add("Mới có 1 lần chạy cho phiên bản test này - 1 lần Fail chưa đủ để kết luận");
        } else {
            evidence.add("Cùng phiên bản test: " + pattern(verdicts) + " - chưa đủ mẫu rõ ràng để kết luận "
                    + "(cần " + MIN_RUNS_FOR_STABLE_FAILURE + " lần Fail cùng mã để gọi là lỗi ổn định)");
        }
        if (verdicts.stream().anyMatch(h -> h.status() == TestResultStatus.PASSED)) {
            evidence.add("Test này từng Pass ở cùng phiên bản - nên chạy lại để xác nhận lần Fail này");
        }
        return evidence;
    }

    // ---- Tiện ích ----

    private int changes(List<HistoryPoint> verdicts) {
        int changes = 0;
        for (int i = 1; i < verdicts.size(); i++) {
            if (verdicts.get(i).status() != verdicts.get(i - 1).status()) {
                changes++;
            }
        }
        return changes;
    }

    /** "Pass → Pass → Fail(404) → Fail(404)" - chữ thuần, không emoji (lưu vào cột TEXT). */
    private String pattern(List<HistoryPoint> verdicts) {
        List<String> parts = new ArrayList<>();
        for (HistoryPoint h : verdicts) {
            parts.add(h.status() == TestResultStatus.PASSED ? "Pass" : "Fail(" + h.responseStatus() + ")");
        }
        return String.join(" → ", parts);
    }

    private List<String> evidenceList(String first, String second) {
        List<String> evidence = new ArrayList<>();
        evidence.add(first);
        if (second != null && !second.isBlank()) {
            evidence.add("Thông báo gốc: " + second);
        }
        return evidence;
    }
}
