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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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

    public AdminDashboardSummaryResponse getSystemSummary() {
        long totalUsers = userRepository.count();
        long totalProjects = projectRepository.count();
        long totalEndpoints = endpointRepository.count();
        long totalTestCases = testCaseRepository.count();
        long totalTestResults = testResultRepository.count();
        long passedTestResults = testResultRepository.countByStatus(TestResultStatus.PASSED);
        long totalOpenBugs = bugReportRepository.countByStatusNot(BugStatus.CLOSED);
        long totalGenerationEvents = testGenerationEventRepository.count();

        Integer overallPassRate = totalTestResults == 0
                ? null
                : (int) Math.round(passedTestResults * 100.0 / totalTestResults);

        return new AdminDashboardSummaryResponse(
                totalUsers, totalProjects, totalEndpoints, totalTestCases, totalTestResults,
                overallPassRate, totalOpenBugs, totalGenerationEvents);
    }
}
