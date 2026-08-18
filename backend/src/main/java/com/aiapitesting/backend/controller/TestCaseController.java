package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.request.GenerateTestCasesRequest;
import com.aiapitesting.backend.dto.request.SetTestCaseLockedRequest;
import com.aiapitesting.backend.dto.request.TestCaseRequest;
import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.dto.response.DependencySuggestionResponse;
import com.aiapitesting.backend.dto.response.TestCaseResponse;
import com.aiapitesting.backend.service.DependencySuggestionService;
import com.aiapitesting.backend.service.TestCaseService;
import com.aiapitesting.backend.service.ai.TestCaseGenerationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final DependencySuggestionService dependencySuggestionService;

    @PostMapping("/generate-tests")
    public CompletableFuture<ResponseEntity<ApiResponse<List<TestCaseResponse>>>> generate(
            @PathVariable UUID projectId,
            @PathVariable UUID endpointId,
            @RequestBody(required = false) GenerateTestCasesRequest request
    ) {
        return testCaseGenerationService.generate(projectId, endpointId, request)
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

    @PutMapping("/test-cases/{testCaseId}/lock")
    public ResponseEntity<ApiResponse<TestCaseResponse>> setLocked(
            @PathVariable UUID projectId,
            @PathVariable UUID endpointId,
            @PathVariable UUID testCaseId,
            @RequestBody SetTestCaseLockedRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.of(
                testCaseService.setLocked(projectId, endpointId, testCaseId, request.locked())));
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

    @GetMapping("/test-cases/{testCaseId}/dependency-suggestions")
    public ResponseEntity<ApiResponse<List<DependencySuggestionResponse>>> dependencySuggestions(
            @PathVariable UUID projectId,
            @PathVariable UUID endpointId,
            @PathVariable UUID testCaseId
    ) {
        return ResponseEntity.ok(ApiResponse.of(dependencySuggestionService.suggest(projectId, endpointId, testCaseId)));
    }
}
