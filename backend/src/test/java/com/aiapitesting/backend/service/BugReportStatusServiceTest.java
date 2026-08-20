package com.aiapitesting.backend.service;

import com.aiapitesting.backend.entity.BugReport;
import com.aiapitesting.backend.entity.BugStatus;
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
