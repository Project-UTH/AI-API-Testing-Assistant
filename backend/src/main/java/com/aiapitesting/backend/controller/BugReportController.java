package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.request.CreateBugReportRequest;
import com.aiapitesting.backend.dto.request.GenerateBugReportsForProjectRequest;
import com.aiapitesting.backend.dto.request.GenerateBugReportsRequest;
import com.aiapitesting.backend.dto.request.UpdateBugReportRequest;
import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.dto.response.BugReportBatchGenerateResponse;
import com.aiapitesting.backend.dto.response.BugReportDraftResponse;
import com.aiapitesting.backend.dto.response.BugReportPageResponse;
import com.aiapitesting.backend.dto.response.BugReportResponse;
import com.aiapitesting.backend.dto.response.TestResultHistoryItemResponse;
import com.aiapitesting.backend.service.BugReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/bug-reports")
@RequiredArgsConstructor
public class BugReportController {

    private final BugReportService bugReportService;

    @GetMapping
    public ResponseEntity<ApiResponse<BugReportPageResponse>> getBugReports(@PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.of(bugReportService.getBugReports(projectId)));
    }

    @GetMapping("/test-cases/{testCaseId}/run-history")
    public ResponseEntity<ApiResponse<List<TestResultHistoryItemResponse>>> getRunHistory(
            @PathVariable UUID projectId,
            @PathVariable UUID testCaseId
    ) {
        return ResponseEntity.ok(ApiResponse.of(bugReportService.getRunHistory(projectId, testCaseId)));
    }

    @GetMapping("/draft")
    public ResponseEntity<ApiResponse<BugReportDraftResponse>> getDraft(
            @PathVariable UUID projectId,
            @RequestParam UUID testResultId
    ) {
        return ResponseEntity.ok(ApiResponse.of(bugReportService.getDraft(projectId, testResultId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BugReportResponse>> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateBugReportRequest request
    ) {
        BugReportResponse response = bugReportService.create(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /** testResultIds null/rỗng = "Sinh tất cả", có truyền = "Sinh theo lựa chọn". */
    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<BugReportBatchGenerateResponse>> generate(
            @PathVariable UUID projectId,
            @Valid @RequestBody GenerateBugReportsRequest request
    ) {
        BugReportBatchGenerateResponse response =
                bugReportService.generateForExecution(projectId, request.executionId(), request.testResultIds());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /** Quét toàn bộ project, không giới hạn 1 execution. testResultIds null/rỗng = "Sinh tất cả". */
    @PostMapping("/generate-batch")
    public ResponseEntity<ApiResponse<BugReportBatchGenerateResponse>> generateBatch(
            @PathVariable UUID projectId,
            @RequestBody(required = false) GenerateBugReportsForProjectRequest request
    ) {
        BugReportBatchGenerateResponse response =
                bugReportService.generateForProject(projectId, request == null ? null : request.testResultIds());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @PutMapping("/{bugReportId}")
    public ResponseEntity<ApiResponse<BugReportResponse>> update(
            @PathVariable UUID projectId,
            @PathVariable UUID bugReportId,
            @Valid @RequestBody UpdateBugReportRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.of(bugReportService.update(projectId, bugReportId, request)));
    }

    @PostMapping("/{bugReportId}/confirm-close")
    public ResponseEntity<ApiResponse<BugReportResponse>> confirmClose(
            @PathVariable UUID projectId,
            @PathVariable UUID bugReportId
    ) {
        return ResponseEntity.ok(ApiResponse.of(bugReportService.confirmClose(projectId, bugReportId)));
    }

    @PostMapping("/{bugReportId}/dismiss-close-suggestion")
    public ResponseEntity<ApiResponse<BugReportResponse>> dismissCloseSuggestion(
            @PathVariable UUID projectId,
            @PathVariable UUID bugReportId
    ) {
        return ResponseEntity.ok(ApiResponse.of(bugReportService.dismissCloseSuggestion(projectId, bugReportId)));
    }

    @DeleteMapping("/{bugReportId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID projectId,
            @PathVariable UUID bugReportId
    ) {
        bugReportService.delete(projectId, bugReportId);
        return ResponseEntity.noContent().build();
    }

    /** Binary .xlsx, không bọc envelope {data,meta} chuẩn - xem skill api-contract. */
    @GetMapping("/{bugReportId}/export")
    public ResponseEntity<byte[]> export(
            @PathVariable UUID projectId,
            @PathVariable UUID bugReportId
    ) {
        return excelFileResponse(bugReportService.exportToExcel(projectId, bugReportId));
    }

    /** Không truyền bugReportId = xuất toàn bộ; có truyền = chỉ xuất các bug đã tick. */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportAll(
            @PathVariable UUID projectId,
            @RequestParam(required = false) List<UUID> bugReportId
    ) {
        return excelFileResponse(bugReportService.exportAllToExcel(projectId, bugReportId));
    }

    private ResponseEntity<byte[]> excelFileResponse(BugReportService.ExportFile file) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .body(file.content());
    }
}
