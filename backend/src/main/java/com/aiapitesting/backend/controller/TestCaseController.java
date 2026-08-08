package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.request.TestCaseRequest;
import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.dto.response.TestCaseResponse;
import com.aiapitesting.backend.service.TestCaseService;
import com.aiapitesting.backend.service.ai.TestCaseGenerationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/endpoints/{endpointId}")
@RequiredArgsConstructor
public class TestCaseController {

    private final TestCaseGenerationService testCaseGenerationService;
    private final TestCaseService testCaseService;

    @PostMapping("/generate-tests")
    public CompletableFuture<ResponseEntity<ApiResponse<List<TestCaseResponse>>>> generate(
            @PathVariable UUID projectId,
            @PathVariable UUID endpointId
    ) {
        return testCaseGenerationService.generate(projectId, endpointId)
                .thenApply(list -> ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(list)));
    }

    @PostMapping("/test-cases")
    public ResponseEntity<ApiResponse<TestCaseResponse>> create(
            @PathVariable UUID projectId,
            @PathVariable UUID endpointId,
            @Valid @RequestBody TestCaseRequest request
    ) {
        TestCaseResponse response = testCaseService.create(projectId, endpointId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @PutMapping("/test-cases/{testCaseId}")
    public ResponseEntity<ApiResponse<TestCaseResponse>> update(
            @PathVariable UUID projectId,
            @PathVariable UUID endpointId,
            @PathVariable UUID testCaseId,
            @Valid @RequestBody TestCaseRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.of(testCaseService.update(projectId, endpointId, testCaseId, request)));
    }

    @DeleteMapping("/test-cases/{testCaseId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID projectId,
            @PathVariable UUID endpointId,
            @PathVariable UUID testCaseId
    ) {
        testCaseService.delete(projectId, endpointId, testCaseId);
        return ResponseEntity.noContent().build();
    }
}
