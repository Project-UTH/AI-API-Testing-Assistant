package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.dto.response.TestCaseResponse;
import com.aiapitesting.backend.service.TestCaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/test-cases")
@RequiredArgsConstructor
public class ProjectTestCaseController {

    private final TestCaseService testCaseService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<TestCaseResponse>>> list(@PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.of(testCaseService.listByProject(projectId)));
    }
}
