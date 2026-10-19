package com.aiapitesting.backend.service.report;

import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.exception.TestExecutionNotFoundException;
import com.aiapitesting.backend.repository.TestExecutionRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.aiapitesting.backend.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionReportServiceTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private TestExecutionRepository testExecutionRepository;

    @Mock
    private TestResultRepository testResultRepository;

    private ExecutionReportService service() {
        return new ExecutionReportService(projectService, testExecutionRepository, testResultRepository, new ReportModelFactory());
    }

    @Test
    void render_junit_returnsAttachmentFileWithXmlContentType() {
        Project project = ReportTestSupport.project("P");
        TestExecution execution = ReportTestSupport.execution(ExecutionStatus.COMPLETED);
        when(projectService.getOwnedProject(project.getId())).thenReturn(project);
        when(testExecutionRepository.findByIdAndProject(execution.getId(), project)).thenReturn(Optional.of(execution));
        when(testResultRepository.findAllByExecutionOrderByTestCaseCreatedAt(execution)).thenReturn(
                List.of(ReportTestSupport.result("GET", "/a", "t", TestResultStatus.PASSED, 200, 200, null, null)));

        ExecutionReportService.ReportFile file = service().render(project.getId(), execution.getId(), ReportFormat.JUNIT);

        assertThat(file.contentType()).startsWith("application/xml");
        assertThat(file.filename()).startsWith("execution-").endsWith(".xml");
        assertThat(new String(file.content(), StandardCharsets.UTF_8)).contains("<testsuites");
    }

    @Test
    void render_unknownExecution_notFound() {
        Project project = ReportTestSupport.project("P");
        UUID missing = UUID.randomUUID();
        when(projectService.getOwnedProject(project.getId())).thenReturn(project);
        when(testExecutionRepository.findByIdAndProject(missing, project)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().render(project.getId(), missing, ReportFormat.HTML)).isInstanceOf(TestExecutionNotFoundException.class);
    }

    @Test
    void format_parse_acceptsAliasesCaseInsensitively_andRejectsUnknown() {
        assertThat(ReportFormat.parse("JUnit")).isEqualTo(ReportFormat.JUNIT);
        assertThat(ReportFormat.parse("xml")).isEqualTo(ReportFormat.JUNIT);
        assertThat(ReportFormat.parse(" HTML ")).isEqualTo(ReportFormat.HTML);
        assertThatThrownBy(() -> ReportFormat.parse("pdf")).isInstanceOf(InvalidRequestException.class);
    }
}
