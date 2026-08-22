package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.response.DashboardSummaryResponse;
import com.aiapitesting.backend.entity.BugStatus;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.repository.BugReportRepository;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.ProjectRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestGenerationEventRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Số liệu tổng quan trang Tổng quan (Module 8/11) - đọc-tổng hợp, không phải logic AI/engine thực thi. */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final CurrentUserService currentUserService;
    private final ProjectRepository projectRepository;
    private final EndpointRepository endpointRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestResultRepository testResultRepository;
    private final BugReportRepository bugReportRepository;
    private final TestGenerationEventRepository testGenerationEventRepository;

    @Value("${ai.quota.daily-token-limit}")
    private long dailyTokenLimit;

    public DashboardSummaryResponse getSummary() {
        User owner = currentUserService.getCurrentUser();

        long totalProjects = projectRepository.countByOwner(owner);
        long totalEndpoints = endpointRepository.countByProjectOwner(owner);
        long totalTestCases = testCaseRepository.countByEndpointProjectOwner(owner);
        long totalTestResults = testResultRepository.countByTestCaseEndpointProjectOwner(owner);
        long passedTestResults = testResultRepository.countByTestCaseEndpointProjectOwnerAndStatus(
                owner, TestResultStatus.PASSED);
        long totalOpenBugs = bugReportRepository.countByProjectOwnerAndStatusNot(owner, BugStatus.CLOSED);

        Instant startOfToday = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant startOfTomorrow = startOfToday.plus(1, ChronoUnit.DAYS);
        long aiTokensToday = testGenerationEventRepository.sumTotalTokensByOwnerAndCreatedAtBetween(
                owner, startOfToday, startOfTomorrow);
        Integer override = owner.getAiDailyTokenLimitOverride();
        long aiDailyTokenLimit = override != null ? override : dailyTokenLimit;

        Integer overallPassRate = totalTestResults == 0
                ? null
                : (int) Math.round(passedTestResults * 100.0 / totalTestResults);

        return new DashboardSummaryResponse(
                totalProjects, totalEndpoints, totalTestCases, totalTestResults, overallPassRate, totalOpenBugs,
                aiTokensToday, aiDailyTokenLimit);
    }
}
