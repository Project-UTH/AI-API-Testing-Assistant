package com.aiapitesting.backend.service;

import com.aiapitesting.backend.entity.BugReport;
import com.aiapitesting.backend.entity.BugStatus;
import com.aiapitesting.backend.entity.FailureCause;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.repository.BugReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BugReportStatusServiceTest {

    @Mock
    private BugReportRepository bugReportRepository;

    @InjectMocks
    private BugReportStatusService bugReportStatusService;

    private TestCase testCase;

    @BeforeEach
    void setUp() {
        testCase = TestCase.builder().id(UUID.randomUUID()).build();
    }

    @Test
    void onNewTestResult_passedWhilePending_setsPendingCloseSuggestionButKeepsStatus() {
        BugReport bug = BugReport.builder().id(UUID.randomUUID()).status(BugStatus.PENDING).pendingCloseSuggestion(false).build();
        when(bugReportRepository.findAllByTestCase(testCase)).thenReturn(List.of(bug));
        TestResult result = TestResult.builder().testCase(testCase).status(TestResultStatus.PASSED).build();

        bugReportStatusService.onNewTestResult(result);

        assertThat(bug.getStatus()).isEqualTo(BugStatus.PENDING);
        assertThat(bug.isPendingCloseSuggestion()).isTrue();
        assertThat(bug.getNote()).contains("Gợi ý đóng");
        verify(bugReportRepository).saveAll(List.of(bug));
    }

    @Test
    void onNewTestResult_passedWhileNew_doesNothing() {
        BugReport bug = BugReport.builder().id(UUID.randomUUID()).status(BugStatus.NEW).pendingCloseSuggestion(false).build();
        when(bugReportRepository.findAllByTestCase(testCase)).thenReturn(List.of(bug));
        TestResult result = TestResult.builder().testCase(testCase).status(TestResultStatus.PASSED).build();

        bugReportStatusService.onNewTestResult(result);

        assertThat(bug.isPendingCloseSuggestion()).isFalse();
        verify(bugReportRepository, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void onNewTestResult_failedWhileClosed_autoReopensWithoutConfirmation() {
        BugReport bug = BugReport.builder().id(UUID.randomUUID()).status(BugStatus.CLOSED).pendingCloseSuggestion(false).build();
        when(bugReportRepository.findAllByTestCase(testCase)).thenReturn(List.of(bug));
        TestResult result = TestResult.builder().testCase(testCase).status(TestResultStatus.FAILED).build();

        bugReportStatusService.onNewTestResult(result);

        assertThat(bug.getStatus()).isEqualTo(BugStatus.REOPENED);
        assertThat(bug.isPendingCloseSuggestion()).isFalse();
        assertThat(bug.getNote()).contains("Reopened");
        verify(bugReportRepository).saveAll(List.of(bug));
    }

    @Test
    void onNewTestResult_failedClearsStalePendingCloseSuggestion() {
        BugReport bug = BugReport.builder().id(UUID.randomUUID()).status(BugStatus.PENDING).pendingCloseSuggestion(true).build();
        when(bugReportRepository.findAllByTestCase(testCase)).thenReturn(List.of(bug));
        TestResult result = TestResult.builder().testCase(testCase).status(TestResultStatus.FAILED).build();

        bugReportStatusService.onNewTestResult(result);

        assertThat(bug.getStatus()).isEqualTo(BugStatus.PENDING);
        assertThat(bug.isPendingCloseSuggestion()).isFalse();
        verify(bugReportRepository).saveAll(List.of(bug));
    }

    /** Lỗi thật đã xảy ra: token hết hạn làm cả lượt fail thì bug đã đóng KHÔNG được tự mở lại. */
    @Test
    void onNewTestResult_failedBecauseOfEnvironmentOrToken_doesNotReopenClosedBug() {
        for (FailureCause cause : List.of(FailureCause.AUTH_CONFIG, FailureCause.ENVIRONMENT, FailureCause.TEST_SETUP)) {
            BugReport bug = BugReport.builder().id(UUID.randomUUID()).status(BugStatus.CLOSED).pendingCloseSuggestion(false).build();
            TestResult result = TestResult.builder().testCase(testCase).status(TestResultStatus.FAILED).failureCause(cause).build();

            bugReportStatusService.onNewTestResult(result);

            assertThat(bug.getStatus()).as(cause.name()).isEqualTo(BugStatus.CLOSED);
        }
        verify(bugReportRepository, never()).findAllByTestCase(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void onNewTestResult_failedWithApiSideCause_stillReopensClosedBug() {
        for (FailureCause cause : List.of(FailureCause.STABLE_FAILURE, FailureCause.DATA_STATE, FailureCause.UNDETERMINED)) {
            BugReport bug = BugReport.builder().id(UUID.randomUUID()).status(BugStatus.CLOSED).pendingCloseSuggestion(false).build();
            when(bugReportRepository.findAllByTestCase(testCase)).thenReturn(List.of(bug));
            TestResult result = TestResult.builder().testCase(testCase).status(TestResultStatus.FAILED).failureCause(cause).build();

            bugReportStatusService.onNewTestResult(result);

            assertThat(bug.getStatus()).as(cause.name()).isEqualTo(BugStatus.REOPENED);
        }
    }

    /** Giai đoạn 2 - kết quả của test không ổn định (kể cả Pass) không đủ tin để gợi ý đóng hay mở lại bug. */
    @Test
    void onNewTestResult_unstableTest_neitherSuggestsClosingNorReopening() {
        for (TestResultStatus status : List.of(TestResultStatus.PASSED, TestResultStatus.FAILED)) {
            TestResult result = TestResult.builder().testCase(testCase).status(status).unstable(true).build();

            bugReportStatusService.onNewTestResult(result);
        }

        verify(bugReportRepository, never()).findAllByTestCase(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void onNewTestResult_flakyCauseOnFail_doesNotReopenButStableTestStillDoes() {
        TestResult flaky = TestResult.builder().testCase(testCase).status(TestResultStatus.FAILED).failureCause(FailureCause.FLAKY).build();
        bugReportStatusService.onNewTestResult(flaky);
        verify(bugReportRepository, never()).findAllByTestCase(org.mockito.ArgumentMatchers.any());

        BugReport closed = BugReport.builder().id(UUID.randomUUID()).status(BugStatus.CLOSED).pendingCloseSuggestion(false).build();
        when(bugReportRepository.findAllByTestCase(testCase)).thenReturn(List.of(closed));
        bugReportStatusService.onNewTestResult(
                TestResult.builder().testCase(testCase).status(TestResultStatus.FAILED).unstable(false).build());
        assertThat(closed.getStatus()).isEqualTo(BugStatus.REOPENED);
    }

    @Test
    void onNewTestResult_failedAfterTestWasEdited_doesNotReopenClosedBugButNotesIt() {
        TestResult source = TestResult.builder().testCase(testCase).status(TestResultStatus.FAILED).testCaseFingerprint("v1").build();
        BugReport bug = BugReport.builder().id(UUID.randomUUID()).status(BugStatus.CLOSED).sourceTestResult(source).build();
        when(bugReportRepository.findAllByTestCase(testCase)).thenReturn(List.of(bug));
        TestResult result = TestResult.builder().testCase(testCase).status(TestResultStatus.FAILED).testCaseFingerprint("v2").build();

        bugReportStatusService.onNewTestResult(result);

        assertThat(bug.getStatus()).isEqualTo(BugStatus.CLOSED);
        assertThat(bug.getNote()).contains("đã được sửa");
        verify(bugReportRepository).saveAll(List.of(bug));
    }

    @Test
    void onNewTestResult_failedAfterTestWasEdited_doesNotRepeatTheNoteOnLaterRuns() {
        TestResult source = TestResult.builder().testCase(testCase).status(TestResultStatus.FAILED).testCaseFingerprint("v1").build();
        BugReport bug = BugReport.builder().id(UUID.randomUUID()).status(BugStatus.CLOSED).sourceTestResult(source).build();
        when(bugReportRepository.findAllByTestCase(testCase)).thenReturn(List.of(bug));
        TestResult result = TestResult.builder().testCase(testCase).status(TestResultStatus.FAILED).testCaseFingerprint("v2").build();

        bugReportStatusService.onNewTestResult(result);
        String noteAfterFirstRun = bug.getNote();
        bugReportStatusService.onNewTestResult(result);

        assertThat(bug.getNote()).isEqualTo(noteAfterFirstRun);
    }

    @Test
    void onNewTestResult_failedWithSameFingerprintAsBugSource_stillReopensClosedBug() {
        TestResult source = TestResult.builder().testCase(testCase).status(TestResultStatus.FAILED).testCaseFingerprint("v1").build();
        BugReport bug = BugReport.builder().id(UUID.randomUUID()).status(BugStatus.CLOSED).sourceTestResult(source).build();
        when(bugReportRepository.findAllByTestCase(testCase)).thenReturn(List.of(bug));
        TestResult result = TestResult.builder().testCase(testCase).status(TestResultStatus.FAILED).testCaseFingerprint("v1").build();

        bugReportStatusService.onNewTestResult(result);

        assertThat(bug.getStatus()).isEqualTo(BugStatus.REOPENED);
    }

    @Test
    void onNewTestResult_errorStatus_ignoredEntirely() {
        TestResult result = TestResult.builder().testCase(testCase).status(TestResultStatus.ERROR).build();

        bugReportStatusService.onNewTestResult(result);

        verify(bugReportRepository, never()).findAllByTestCase(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void onNewTestResult_noBugsForTestCase_doesNothing() {
        when(bugReportRepository.findAllByTestCase(testCase)).thenReturn(List.of());
        TestResult result = TestResult.builder().testCase(testCase).status(TestResultStatus.PASSED).build();

        bugReportStatusService.onNewTestResult(result);

        verify(bugReportRepository, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }
}
