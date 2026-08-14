package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.dto.response.EndpointHistoryResponse;
import com.aiapitesting.backend.service.TestHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/history")
@RequiredArgsConstructor
public class TestHistoryController {

    private final TestHistoryService testHistoryService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<EndpointHistoryResponse>>> list(@PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.of(testHistoryService.getHistory(projectId)));
    }
}
