package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.dto.response.TestCaseResponse;
import com.aiapitesting.backend.service.ai.TestCaseGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    @PostMapping("/generate-tests")
    public CompletableFuture<ResponseEntity<ApiResponse<List<TestCaseResponse>>>> generate(
            @PathVariable UUID projectId,
            @PathVariable UUID endpointId
    ) {
        return testCaseGenerationService.generate(projectId, endpointId)
                .thenApply(list -> ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(list)));
    }
}
