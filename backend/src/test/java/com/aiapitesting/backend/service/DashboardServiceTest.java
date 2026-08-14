package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.response.DashboardSummaryResponse;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.ProjectRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private EndpointRepository endpointRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private TestResultRepository testResultRepository;

    @InjectMocks
    private DashboardService dashboardService;

    private User owner;

    @BeforeEach
    void setUp() {
        owner = User.builder().id(UUID.randomUUID()).build();
        when(currentUserService.getCurrentUser()).thenReturn(owner);
    }

    @Test
    void getSummary_countsAndPassRateComputedFromRepositories() {
        when(projectRepository.countByOwner(owner)).thenReturn(3L);
        when(endpointRepository.countByProjectOwner(owner)).thenReturn(12L);
        when(testCaseRepository.countByEndpointProjectOwner(owner)).thenReturn(40L);
        when(testResultRepository.countByTestCaseEndpointProjectOwner(owner)).thenReturn(20L);
        when(testResultRepository.countByTestCaseEndpointProjectOwnerAndStatus(owner, TestResultStatus.PASSED))
                .thenReturn(15L);

        DashboardSummaryResponse summary = dashboardService.getSummary();

        assertThat(summary.totalProjects()).isEqualTo(3);
        assertThat(summary.totalEndpoints()).isEqualTo(12);
        assertThat(summary.totalTestCases()).isEqualTo(40);
        assertThat(summary.totalTestResults()).isEqualTo(20);
        assertThat(summary.overallPassRate()).isEqualTo(75); // 15/20 = 75%
    }

    @Test
    void getSummary_noTestResultsYet_passRateIsNullNotDivideByZero() {
        when(projectRepository.countByOwner(owner)).thenReturn(1L);
        when(endpointRepository.countByProjectOwner(owner)).thenReturn(0L);
        when(testCaseRepository.countByEndpointProjectOwner(owner)).thenReturn(0L);
        when(testResultRepository.countByTestCaseEndpointProjectOwner(owner)).thenReturn(0L);

        DashboardSummaryResponse summary = dashboardService.getSummary();

        assertThat(summary.overallPassRate()).isNull();
    }
}
