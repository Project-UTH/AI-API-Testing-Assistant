package com.aiapitesting.backend.service.report;

import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.exception.TestExecutionNotFoundException;
import com.aiapitesting.backend.repository.TestExecutionRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.aiapitesting.backend.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Module 17 (17d) - xuất báo cáo 1 lượt chạy (JUnit XML / HTML). Dùng chung cho nút Xuất ở giao diện và endpoint CI. */
@Service
@RequiredArgsConstructor
public class ExecutionReportService {

    private final ProjectService projectService;
    private final TestExecutionRepository testExecutionRepository;
    private final TestResultRepository testResultRepository;
    private final ReportModelFactory reportModelFactory;

    public record ReportFile(byte[] content, String contentType, String filename) {
    }

    /** Dành cho người dùng đăng nhập (JWT): kiểm quyền sở hữu project. */
    public ReportFile render(UUID projectId, UUID executionId, ReportFormat format) {
        Project project = projectService.getOwnedProject(projectId);
        return render(project, executionId, format, ExecutionConclusionCalculator.FailOn.API_FAIL);
    }

    /** Dành cho CI (token API): project đã được xác thực bởi caller. */
    public ReportFile render(Project project, UUID executionId, ReportFormat format, ExecutionConclusionCalculator.FailOn failOn) {
        TestExecution execution = testExecutionRepository.findByIdAndProject(executionId, project)
                .orElseThrow(() -> new TestExecutionNotFoundException("Không tìm thấy lần thực thi với id đã cho"));
        List<TestResult> results = testResultRepository.findAllByExecutionOrderByTestCaseCreatedAt(execution);
        ReportModel model = reportModelFactory.build(project, execution, results, failOn);
        String body = format == ReportFormat.JUNIT ? JUnitXmlReportBuilder.build(model) : HtmlReportBuilder.build(model);
        String filename = "execution-" + executionId.toString().substring(0, 8) + "." + format.extension();
        return new ReportFile(body.getBytes(StandardCharsets.UTF_8), format.contentType() + ";charset=UTF-8", filename);
    }
}
