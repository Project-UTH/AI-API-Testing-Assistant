package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.response.AdminDashboardSummaryResponse;
import com.aiapitesting.backend.dto.response.AiUsageResponse;
import com.aiapitesting.backend.entity.BugStatus;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.entity.UserRole;
import com.aiapitesting.backend.repository.BugReportRepository;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.ProjectRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestGenerationEventRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.aiapitesting.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Số liệu toàn hệ thống cho trang Admin (Module 11) - khác hẳn DashboardService (Module 8) vốn
 * luôn giới hạn theo owner đang đăng nhập. Dùng count() có sẵn của JpaRepository cho mọi tổng
 * không cần lọc điều kiện (đã đếm toàn bộ bảng), chỉ thêm method repository mới cho phần cần lọc
 * theo status (countByStatus/countByStatusNot, xem TestResultRepository/BugReportRepository).
 */
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final EndpointRepository endpointRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestResultRepository testResultRepository;
    private final BugReportRepository bugReportRepository;
    private final TestGenerationEventRepository testGenerationEventRepository;
    private final AiUsageService aiUsageService;

    @Value("${ai.quota.daily-token-limit}")
    private long dailyTokenLimit;

    public AdminDashboardSummaryResponse getSystemSummary() {
        // Chỉ đếm role USER - tài khoản ADMIN không tính vào số liệu "Người dùng".
        long totalUsers = userRepository.countByRole(UserRole.USER);
        long totalProjects = projectRepository.count();
        long totalEndpoints = endpointRepository.count();
        long totalTestCases = testCaseRepository.count();
        long executedTestCaseCount = testResultRepository.countDistinctTestCase();
        long totalTestResults = testResultRepository.count();
        long passedTestResults = testResultRepository.countByStatus(TestResultStatus.PASSED);
        long totalOpenBugs = bugReportRepository.countByStatusNot(BugStatus.CLOSED);
        long totalGenerationEvents = testGenerationEventRepository.count();

        Instant startOfToday = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant startOfTomorrow = startOfToday.plus(1, ChronoUnit.DAYS);
        long totalAiTokensToday =
                testGenerationEventRepository.sumTotalTokensByCreatedAtBetween(startOfToday, startOfTomorrow);

        Integer overallPassRate = totalTestResults == 0
                ? null
                : (int) Math.round(passedTestResults * 100.0 / totalTestResults);

        return new AdminDashboardSummaryResponse(
                totalUsers, totalProjects, totalEndpoints, totalTestCases, executedTestCaseCount, totalTestResults,
                passedTestResults, overallPassRate, totalOpenBugs, totalGenerationEvents, totalAiTokensToday,
                dailyTokenLimit);
    }

    public AiUsageResponse getSystemAiUsage() {
        return aiUsageService.getSystemUsage();
    }
}
