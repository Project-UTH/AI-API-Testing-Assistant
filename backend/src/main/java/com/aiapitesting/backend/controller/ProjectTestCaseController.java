package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.dto.response.TestCaseResponse;
import com.aiapitesting.backend.entity.TestCaseGenerationOrigin;
import com.aiapitesting.backend.entity.TestCaseReliability;
import com.aiapitesting.backend.entity.TestCaseSource;
import com.aiapitesting.backend.service.TestCaseListQuery;
import com.aiapitesting.backend.service.TestCaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /** Có tham số {@code page} thì trả theo trang (có lọc/sắp xếp); không có thì giữ hành vi cũ ở method trên. */
    @GetMapping(params = "page")
    public ResponseEntity<PageResponse<TestCaseResponse>> listPaged(
            @PathVariable UUID projectId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) UUID endpointId,
            @RequestParam(required = false) TestCaseSource source,
            @RequestParam(required = false) TestCaseGenerationOrigin generationOrigin,
            @RequestParam(required = false) Boolean locked,
            @RequestParam(required = false) TestCaseReliability reliability,
            @RequestParam(required = false) String sort) {
        return ResponseEntity.ok(testCaseService.listByProjectPaged(projectId,
                new TestCaseListQuery(page, size, q, method, endpointId, source, generationOrigin, locked, reliability, sort)));
    }
}
