package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.request.TestExecutionRequest;
import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.dto.response.TestExecutionResponse;
import com.aiapitesting.backend.service.execution.TestExecutionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/executions")
@RequiredArgsConstructor
public class TestExecutionController {

    private final TestExecutionService testExecutionService;

    @PostMapping
    public ResponseEntity<ApiResponse<TestExecutionResponse>> trigger(
            @PathVariable UUID projectId,
            @Valid @RequestBody TestExecutionRequest request
    ) {
        TestExecutionResponse response = testExecutionService.trigger(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @GetMapping("/{executionId}")
    public ResponseEntity<ApiResponse<TestExecutionResponse>> getExecution(
            @PathVariable UUID projectId,
            @PathVariable UUID executionId
    ) {
        return ResponseEntity.ok(ApiResponse.of(testExecutionService.getExecution(projectId, executionId)));
    }
}
