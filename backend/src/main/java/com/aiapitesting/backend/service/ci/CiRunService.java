package com.aiapitesting.backend.service.ci;

import com.aiapitesting.backend.dto.request.CiRunRequest;
import com.aiapitesting.backend.dto.response.CiExecutionResponse;
import com.aiapitesting.backend.dto.response.CiRunResponse;
import com.aiapitesting.backend.dto.response.TestExecutionResponse;
import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.exception.ExecutionInProgressException;
import com.aiapitesting.backend.exception.ForbiddenException;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.exception.ProjectNotFoundException;
import com.aiapitesting.backend.exception.TestExecutionNotFoundException;
import com.aiapitesting.backend.exception.TooManyRequestsException;
import com.aiapitesting.backend.repository.ProjectRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestExecutionRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.aiapitesting.backend.security.CiPrincipal;
import com.aiapitesting.backend.service.execution.TestExecutionService;
import com.aiapitesting.backend.service.report.ExecutionConclusionCalculator;
import com.aiapitesting.backend.service.report.ExecutionConclusionCalculator.FailOn;
import com.aiapitesting.backend.service.report.ExecutionReportService;
import com.aiapitesting.backend.service.report.ReportFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Module 17 (17d) - chạy suite + lấy kết quả cho CI bằng token API. QUYỀN: token chỉ gắn với 1 project; mọi hàm đều đối chiếu
 * {@code principal.projectId()} (từ token đã xác thực) với project trong URL/execution - KHÔNG dùng SecurityContext của người dùng.
 * Execution không thuộc project của token trả 404 (không 403) để không lộ sự tồn tại.
 */
@Service
@RequiredArgsConstructor
public class CiRunService {

    static final int MAX_RUNS_PER_MINUTE = 10;
    static final int MAX_READS_PER_MINUTE = 120;

    private final ProjectRepository projectRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestExecutionRepository testExecutionRepository;
    private final TestResultRepository testResultRepository;
    private final TestExecutionService testExecutionService;
    private final ExecutionReportService executionReportService;
    private final CiRateLimiter rateLimiter;

    public CiRunResponse start(CiPrincipal principal, UUID projectId, CiRunRequest request) {
        Project project = requireOwnProject(principal, projectId);
        if (!rateLimiter.tryAcquire("ci-run:" + principal.tokenId(), MAX_RUNS_PER_MINUTE)) {
            throw new TooManyRequestsException("Khởi chạy quá nhanh (tối đa " + MAX_RUNS_PER_MINUTE + " lần/phút/token)");
        }
        // Chặn chạy chồng: CI lỗi vòng lặp sẽ không dồn tải lên hệ thống đích thật của người dùng.
        if (testExecutionRepository.existsByProjectAndStatusIn(project, EnumSet.of(ExecutionStatus.PENDING, ExecutionStatus.RUNNING))) {
            throw new ExecutionInProgressException("Project đang có lần chạy chưa xong, đợi chạy xong rồi gọi lại");
        }

        CiRunRequest.Scope scope = request == null ? CiRunRequest.Scope.ALL : request.scopeOrDefault();
        FailOn failOn = request == null ? FailOn.API_FAIL : request.failOnOrDefault();
        List<TestCase> all = testCaseRepository.findAllByEndpointProject(project);
        int excluded = 0;
        List<UUID> selectedIds;
        switch (scope) {
            case TEST_CASES -> {
                if (request.testCaseIds() == null || request.testCaseIds().isEmpty()) {
                    throw new InvalidRequestException("scope TEST_CASES cần danh sách testCaseIds");
                }
                selectedIds = request.testCaseIds().stream().distinct().toList(); // TestExecutionService kiểm thuộc project
            }
            case ENDPOINTS -> {
                if (request.endpointIds() == null || request.endpointIds().isEmpty()) {
                    throw new InvalidRequestException("scope ENDPOINTS cần danh sách endpointIds");
                }
                Set<UUID> endpointIds = new HashSet<>(request.endpointIds());
                selectedIds = all.stream().filter(tc -> endpointIds.contains(tc.getEndpoint().getId())).map(TestCase::getId).toList();
            }
            default -> {
                // Endpoint đã không còn trong spec: test của nó chắc chắn 404 - loại khỏi suite mặc định để không làm nhiễu kết quả.
                selectedIds = all.stream().filter(tc -> !Boolean.TRUE.equals(tc.getEndpoint().getMissingFromSpec())).map(TestCase::getId).toList();
                excluded = all.size() - selectedIds.size();
            }
        }
        if (selectedIds.isEmpty()) {
            throw new InvalidRequestException("Không có test case nào để chạy");
        }

        TestExecutionResponse started = testExecutionService.triggerForProject(project, selectedIds, principal.tokenId(), failOn.name());
        return new CiRunResponse(started.id(), started.status(), executionUrl(started.id()), executionUrl(started.id()) + "/report",
                selectedIds.size(), excluded);
    }

    public CiExecutionResponse status(CiPrincipal principal, UUID projectId, UUID executionId, FailOn failOnOverride) {
        requireReadBudget(principal);
        TestExecution execution = requireOwnExecution(principal, projectId, executionId);
        FailOn failOn = failOnOverride != null ? failOnOverride : storedFailOn(execution);
        List<TestResult> results = testResultRepository.findAllByExecutionOrderByTestCaseCreatedAt(execution);
        ExecutionConclusionCalculator.Result result = ExecutionConclusionCalculator.calculate(execution.getStatus(), results, failOn);
        return new CiExecutionResponse(execution.getId(), execution.getStatus(), result.conclusion(), failOn, execution.getStartedAt(),
                execution.getFinishedAt(), result.summary(), result.warnings());
    }

    public ExecutionReportService.ReportFile report(CiPrincipal principal, UUID projectId, UUID executionId, ReportFormat format, FailOn failOnOverride) {
        requireReadBudget(principal);
        TestExecution execution = requireOwnExecution(principal, projectId, executionId);
        Project project = requireOwnProject(principal, projectId);
        return executionReportService.render(project, execution.getId(), format, failOnOverride != null ? failOnOverride : storedFailOn(execution));
    }

    // ---- quyền ----

    /** {pid} trong URL phải đúng project của token; khác → 403. */
    private Project requireOwnProject(CiPrincipal principal, UUID projectId) {
        if (!principal.projectId().equals(projectId)) {
            throw new ForbiddenException("Token API này không có quyền với project đã cho");
        }
        return projectRepository.findById(projectId).orElseThrow(() -> new ProjectNotFoundException("Không tìm thấy project với id đã cho"));
    }

    private TestExecution requireOwnExecution(CiPrincipal principal, UUID projectId, UUID executionId) {
        if (!principal.projectId().equals(projectId)) {
            throw new ForbiddenException("Token API này không có quyền với project đã cho");
        }
        return testExecutionRepository.findByIdAndProjectId(executionId, principal.projectId())
                .orElseThrow(() -> new TestExecutionNotFoundException("Không tìm thấy lần thực thi với id đã cho"));
    }

    private void requireReadBudget(CiPrincipal principal) {
        if (!rateLimiter.tryAcquire("ci-read:" + principal.tokenId(), MAX_READS_PER_MINUTE)) {
            throw new TooManyRequestsException("Gọi quá nhanh (tối đa " + MAX_READS_PER_MINUTE + " lần/phút/token)");
        }
    }

    private FailOn storedFailOn(TestExecution execution) {
        try {
            return execution.getCiFailOn() == null ? FailOn.API_FAIL : FailOn.valueOf(execution.getCiFailOn());
        } catch (IllegalArgumentException e) {
            return FailOn.API_FAIL;
        }
    }

    private String executionUrl(UUID executionId) {
        return "/api/v1/ci/executions/" + executionId;
    }
}
