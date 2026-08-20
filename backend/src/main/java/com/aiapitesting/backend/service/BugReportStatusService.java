package com.aiapitesting.backend.service;

import com.aiapitesting.backend.entity.BugReport;
import com.aiapitesting.backend.entity.BugStatus;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.repository.BugReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Tự động hoá HẸP về trạng thái bug report theo lần chạy mới (Module 10) - tách riêng khỏi
 * BugReportService/TestExecutionRunner để dễ unit test độc lập (chỉ cần mock BugReportRepository).
 * Gọi từ TestExecutionRunner.finish() - điểm DUY NHẤT mọi TestResult được lưu, đảm bảo không bỏ
 * sót lần chạy nào (kể cả test case được kéo theo qua Test Data Chaining).
 */
@Service
@RequiredArgsConstructor
public class BugReportStatusService {

    /** Chỉ các status "đang được theo dõi/chờ xử lý" mới nhận gợi ý đóng khi có lần chạy Pass mới. */
    private static final Set<BugStatus> ELIGIBLE_FOR_CLOSE_SUGGESTION =
            EnumSet.of(BugStatus.PENDING, BugStatus.POSTED, BugStatus.REOPENED);

    private final BugReportRepository bugReportRepository;

    public void onNewTestResult(TestResult result) {
        if (result.getStatus() != TestResultStatus.PASSED && result.getStatus() != TestResultStatus.FAILED) {
            // ERROR/BLOCKED/SKIPPED nằm ngoài phạm vi 2 quy tắc tự động đã chốt (lỗi hạ tầng/bị
            // chặn không phản ánh app có bug thật hay đã sửa xong).
            return;
        }

        List<BugReport> bugs = bugReportRepository.findAllByTestCase(result.getTestCase());
        if (bugs.isEmpty()) {
            return;
        }

        boolean changed = false;
        for (BugReport bug : bugs) {
            changed |= applyRule(bug, result.getStatus());
        }
        if (changed) {
            bugReportRepository.saveAll(bugs);
        }
    }

    private boolean applyRule(BugReport bug, TestResultStatus status) {
        if (status == TestResultStatus.PASSED) {
            if (ELIGIBLE_FOR_CLOSE_SUGGESTION.contains(bug.getStatus()) && !bug.isPendingCloseSuggestion()) {
                bug.setPendingCloseSuggestion(true);
                appendNote(bug, "[hệ thống] Gợi ý đóng do có lần chạy mới Pass, chờ xác nhận");
                return true;
            }
            return false;
        }

        // status == FAILED
        if (bug.getStatus() == BugStatus.CLOSED) {
            bug.setStatus(BugStatus.REOPENED);
            bug.setPendingCloseSuggestion(false);
            appendNote(bug, "[hệ thống] Tự động chuyển sang Reopened do có lần chạy mới Fail");
            return true;
        }
        if (bug.isPendingCloseSuggestion()) {
            // Gợi ý đóng từ 1 lần Pass trước đó nay đã lỗi thời vì có Fail mới - huỷ gợi ý, không
            // đổi status (giữ nguyên PENDING/POSTED/REOPENED để người dùng tự xử lý tiếp).
            bug.setPendingCloseSuggestion(false);
            return true;
        }
        return false;
    }

    private void appendNote(BugReport bug, String line) {
        String existing = bug.getNote();
        bug.setNote(existing == null || existing.isBlank() ? line : existing + "\n" + line);
    }
}
