package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.request.StartGenerationJobRequest;
import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.dto.response.GenerationJobResponse;
import com.aiapitesting.backend.service.job.GenerationJobService;
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

import java.util.List;
import java.util.UUID;

/** Job sinh test chạy nền: bắt đầu, xem tiến độ từng bước, huỷ. Mọi route kiểm tra project thuộc người gọi. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/generation-jobs")
@RequiredArgsConstructor
public class GenerationJobController {

    private final GenerationJobService generationJobService;

    @PostMapping
    public ResponseEntity<ApiResponse<GenerationJobResponse>> start(
            @PathVariable UUID projectId, @RequestBody(required = false) StartGenerationJobRequest request
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(generationJobService.start(projectId, request)));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<ApiResponse<GenerationJobResponse>> get(
            @PathVariable UUID projectId, @PathVariable UUID jobId, @RequestParam(defaultValue = "0") int afterSeq
    ) {
        return ResponseEntity.ok(ApiResponse.of(generationJobService.get(projectId, jobId, afterSeq)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<GenerationJobResponse>>> list(
            @PathVariable UUID projectId, @RequestParam(defaultValue = "false") boolean active
    ) {
        return ResponseEntity.ok(ApiResponse.of(generationJobService.list(projectId, active)));
    }

    @PostMapping("/{jobId}/cancel")
    public ResponseEntity<ApiResponse<GenerationJobResponse>> cancel(@PathVariable UUID projectId, @PathVariable UUID jobId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(generationJobService.cancel(projectId, jobId)));
    }
}
