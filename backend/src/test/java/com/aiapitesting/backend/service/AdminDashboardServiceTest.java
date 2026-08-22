package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.response.AdminDashboardSummaryResponse;
import com.aiapitesting.backend.entity.BugStatus;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.repository.BugReportRepository;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.ProjectRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestGenerationEventRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.aiapitesting.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminDashboardServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private EndpointRepository endpointRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private TestResultRepository testResultRepository;

    @Mock
    private BugReportRepository bugReportRepository;

    @Mock
    private TestGenerationEventRepository testGenerationEventRepository;

    @InjectMocks
    private AdminDashboardService adminDashboardService;

    @BeforeEach
    void setUp() {
        // @Value không được @InjectMocks tự set - xem lý do ở TestCaseGenerationServiceTest.
        ReflectionTestUtils.setField(adminDashboardService, "dailyTokenLimit", 100_000L);
    }

    @Test
    void getSystemSummary_countsAcrossAllUsers_notLimitedToOneOwner() {
        when(userRepository.count()).thenReturn(5L);
        when(projectRepository.count()).thenReturn(8L);
        when(endpointRepository.count()).thenReturn(30L);
        when(testCaseRepository.count()).thenReturn(120L);
        when(testResultRepository.count()).thenReturn(60L);
        when(testResultRepository.countByStatus(TestResultStatus.PASSED)).thenReturn(45L);
        when(bugReportRepository.countByStatusNot(BugStatus.CLOSED)).thenReturn(7L);
        when(testGenerationEventRepository.count()).thenReturn(20L);
        when(testGenerationEventRepository.sumTotalTokensByCreatedAtBetween(any(), any())).thenReturn(4200L);

        AdminDashboardSummaryResponse summary = adminDashboardService.getSystemSummary();

        assertThat(summary.totalUsers()).isEqualTo(5);
        assertThat(summary.totalProjects()).isEqualTo(8);
        assertThat(summary.totalEndpoints()).isEqualTo(30);
        assertThat(summary.totalTestCases()).isEqualTo(120);
        assertThat(summary.totalTestResults()).isEqualTo(60);
        assertThat(summary.overallPassRate()).isEqualTo(75); // 45/60 = 75%
        assertThat(summary.totalOpenBugs()).isEqualTo(7);
        assertThat(summary.totalGenerationEvents()).isEqualTo(20);
        assertThat(summary.totalAiTokensToday()).isEqualTo(4200);
        assertThat(summary.aiDailyTokenLimit()).isEqualTo(100_000);
    }

    @Test
    void getSystemSummary_noTestResultsYet_passRateIsNullNotDivideByZero() {
        when(testResultRepository.count()).thenReturn(0L);

        AdminDashboardSummaryResponse summary = adminDashboardService.getSystemSummary();

        assertThat(summary.overallPassRate()).isNull();
    }
}
