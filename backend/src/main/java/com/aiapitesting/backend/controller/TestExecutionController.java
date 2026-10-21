package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.response.TestExecutionPageResponse;
import com.aiapitesting.backend.entity.FailureCause;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.dto.request.TestExecutionRequest;
import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.dto.response.ExecutionCompareResponse;
import com.aiapitesting.backend.dto.response.ExecutionDiffCategory;
import com.aiapitesting.backend.dto.response.TestExecutionResponse;
import com.aiapitesting.backend.service.execution.ExecutionCompareService;
import com.aiapitesting.backend.service.execution.TestExecutionService;
import com.aiapitesting.backend.service.report.ExecutionReportService;
import com.aiapitesting.backend.service.report.ReportFormat;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/executions")
@RequiredArgsConstructor
public class TestExecutionController {

    private final TestExecutionService testExecutionService;
    private final ExecutionCompareService executionCompareService;
    private final ExecutionReportService executionReportService;

    /** Module 17 (17c) - 50 lượt chạy gần nhất của project (không kèm kết quả từng test), để chọn lượt so sánh. */
    @GetMapping
    public ResponseEntity<ApiResponse<java.util.List<com.aiapitesting.backend.dto.response.ExecutionSummaryResponse>>> listRecent(
            @PathVariable UUID projectId
    ) {
        return ResponseEntity.ok(ApiResponse.of(executionCompareService.listRecent(projectId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TestExecutionResponse>> trigger(
            @PathVariable UUID projectId,
            @Valid @RequestBody TestExecutionRequest request
    ) {
        TestExecutionResponse response = testExecutionService.trigger(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * Module 17 (17c) - so sánh 2 lượt chạy (baseId = lượt trước, targetId = lượt sau). category bỏ trống = mọi nhóm trừ STILL_PASSING.
     * Đường dẫn cố định "compare" luôn thắng "/{executionId}" nên không đụng route bên dưới.
     */
    @GetMapping("/compare")
    public ResponseEntity<ApiResponse<ExecutionCompareResponse>> compare(
            @PathVariable UUID projectId,
            @RequestParam UUID baseId,
            @RequestParam UUID targetId,
            @RequestParam(required = false) ExecutionDiffCategory category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return ResponseEntity.ok(ApiResponse.of(executionCompareService.compare(projectId, baseId, targetId, category, page, size)));
    }

    /**
     * Module 17 (17d) - xuất báo cáo 1 lượt chạy (format=junit|html). Chỉ gồm dữ liệu đã làm sạch (không token/response body).
     * Trả dạng tải về (attachment) + nosniff: HTML không bao giờ được hiển thị inline trên origin của ứng dụng.
     */
    @GetMapping("/{executionId}/report")
    public ResponseEntity<byte[]> report(
            @PathVariable UUID projectId,
            @PathVariable UUID executionId,
            @RequestParam(defaultValue = "junit") String format
    ) {
        ExecutionReportService.ReportFile file = executionReportService.render(projectId, executionId, ReportFormat.parse(format));
        return ResponseEntity.ok()
                .header("Content-Type", file.contentType())
                .header("Content-Disposition", "attachment; filename=\"" + file.filename() + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(file.content());
    }

    /** Có tham số {@code page} thì trả kết quả theo trang (có lọc + tổng hợp); không có thì giữ hành vi cũ ở method dưới. */
    @GetMapping(value = "/{executionId}", params = "page")
    public ResponseEntity<ApiResponse<TestExecutionPageResponse>> getExecutionPage(
            @PathVariable UUID projectId,
            @PathVariable UUID executionId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) TestResultStatus status,
            @RequestParam(required = false) FailureCause failureCause,
            @RequestParam(required = false) UUID endpointId,
            @RequestParam(required = false) String q
    ) {
        return ResponseEntity.ok(ApiResponse.of(
                testExecutionService.getExecutionPage(projectId, executionId, page, size, status, failureCause, endpointId, q)));
    }

    @GetMapping("/{executionId}")
    public ResponseEntity<ApiResponse<TestExecutionResponse>> getExecution(
            @PathVariable UUID projectId,
            @PathVariable UUID executionId
    ) {
        return ResponseEntity.ok(ApiResponse.of(testExecutionService.getExecution(projectId, executionId)));
    }
}
