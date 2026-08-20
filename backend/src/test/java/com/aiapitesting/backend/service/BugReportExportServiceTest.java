package com.aiapitesting.backend.service;

import com.aiapitesting.backend.entity.BugPriority;
import com.aiapitesting.backend.entity.BugReport;
import com.aiapitesting.backend.entity.BugSeverity;
import com.aiapitesting.backend.entity.BugStatus;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.entity.User;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BugReportExportServiceTest {

    private final BugReportExportService service = new BugReportExportService();

    @Test
    void exportSingleBugToExcel_producesReadableWorkbookWithBugFields() throws IOException {
        User owner = User.builder().id(UUID.randomUUID()).email("qa@example.com").build();
        Project project = Project.builder().id(UUID.randomUUID()).name("Shop API").owner(owner).build();
        Endpoint endpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("POST").path("/api/products").build();
        TestCase testCase = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint)
                .name("Negative - sai kieu du lieu cho price").expectedStatus(400).build();
        TestExecution execution = TestExecution.builder().id(UUID.randomUUID()).startedAt(Instant.parse("2026-08-19T18:15:59Z")).build();
        TestResult sourceResult = TestResult.builder().id(UUID.randomUUID()).testCase(testCase).execution(execution)
                .status(TestResultStatus.FAILED).responseStatus(403).build();
        BugReport bug = BugReport.builder().id(UUID.randomUUID()).project(project).endpoint(endpoint)
                .testCase(testCase).sourceTestResult(sourceResult).bugId("B1_007")
                .status(BugStatus.NEW).severity(BugSeverity.CRITICAL).priority(BugPriority.MAJOR)
                .summary("Fail: Negative - sai kieu du lieu cho price")
                .stepsToReproduce("Dòng 1\nDòng 2\nDòng 3")
                .reporter(owner).createdAt(Instant.parse("2026-08-19T18:17:23Z")).updatedAt(Instant.parse("2026-08-19T18:17:23Z"))
                .build();

        byte[] xlsx = service.exportSingleBugToExcel(bug);

        assertThat(xlsx).isNotEmpty();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("BugID");
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("Summary");
            assertThat(header.getCell(4).getStringCellValue()).isEqualTo("Description");

            Row data = sheet.getRow(1);
            assertThat(data.getCell(0).getStringCellValue()).isEqualTo("B1_007");
            assertThat(data.getCell(1).getStringCellValue()).isEqualTo("Mới");
            assertThat(data.getCell(2).getStringCellValue()).isEqualTo("POST /api/products");
            assertThat(data.getCell(3).getStringCellValue()).isEqualTo(bug.getSummary());
            assertThat(data.getCell(4).getStringCellValue()).isEqualTo(bug.getStepsToReproduce());
            assertThat(data.getCell(5).getStringCellValue()).isEqualTo("Chí mạng");
            assertThat(data.getCell(11).getStringCellValue()).contains("2026"); // Từ lần chạy lúc
        }
    }

    @Test
    void exportBugsToExcel_multipleBugs_writesOneRowPerBugInOrder() throws IOException {
        User owner = User.builder().id(UUID.randomUUID()).email("qa@example.com").build();
        Project project = Project.builder().id(UUID.randomUUID()).name("Shop API").owner(owner).build();
        Endpoint endpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("GET").path("/api/products").build();
        TestCase testCase = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint).name("TC").expectedStatus(200).build();
        TestExecution execution = TestExecution.builder().id(UUID.randomUUID()).startedAt(Instant.parse("2026-08-19T18:15:59Z")).build();
        TestResult sourceResult = TestResult.builder().id(UUID.randomUUID()).testCase(testCase).execution(execution)
                .status(TestResultStatus.FAILED).responseStatus(500).build();
        BugReport bug1 = BugReport.builder().id(UUID.randomUUID()).project(project).endpoint(endpoint)
                .testCase(testCase).sourceTestResult(sourceResult).bugId("B1_001").status(BugStatus.NEW)
                .reporter(owner).summary("Bug 1").createdAt(Instant.now()).updatedAt(Instant.now()).build();
        BugReport bug2 = BugReport.builder().id(UUID.randomUUID()).project(project).endpoint(endpoint)
                .testCase(testCase).sourceTestResult(sourceResult).bugId("B1_002").status(BugStatus.CLOSED)
                .reporter(owner).summary("Bug 2").createdAt(Instant.now()).updatedAt(Instant.now()).build();

        byte[] xlsx = service.exportBugsToExcel(List.of(bug1, bug2));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("B1_001");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("B1_002");
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("Đã đóng");
        }
    }
}
