package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.dto.response.TestCaseDependentResponse;
import com.aiapitesting.backend.service.DependencySuggestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Quan hệ phụ thuộc giữa các test case của project (test nào đang dùng dữ liệu của test nào). Controller mỏng. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/test-case-dependents")
@RequiredArgsConstructor
public class TestCaseDependentsController {

    private final DependencySuggestionService dependencySuggestionService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<TestCaseDependentResponse>>> list(@PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.of(dependencySuggestionService.listDependents(projectId)));
    }
}
