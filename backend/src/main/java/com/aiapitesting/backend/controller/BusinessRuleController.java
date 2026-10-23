package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.ApproveRequest;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.CreateRequest;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.RejectRequest;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.RuleResponse;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.UpdateRequest;
import com.aiapitesting.backend.entity.RuleSource;
import com.aiapitesting.backend.entity.RuleStatus;
import com.aiapitesting.backend.entity.RuleTemplate;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.GenerateTestsRequest;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.GenerateTestsResponse;
import com.aiapitesting.backend.service.rule.BusinessRuleService;
import com.aiapitesting.backend.service.rule.RuleTestService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 17q G3 - luật nghiệp vụ theo project (CRUD + duyệt/loại). Controller mỏng: mọi nghiệp vụ ở {@link BusinessRuleService}. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/business-rules")
@RequiredArgsConstructor
public class BusinessRuleController {

    private final BusinessRuleService businessRuleService;
    private final RuleTestService ruleTestService;

    @GetMapping
    public PageResponse<RuleResponse> list(
            @PathVariable UUID projectId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) UUID endpointId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String template
    ) {
        return businessRuleService.list(projectId, page, size, endpointId,
                parse(RuleStatus.class, status, "status"), parse(RuleSource.class, source, "source"),
                parse(RuleTemplate.class, template, "template"));
    }

    @GetMapping("/{ruleId}")
    public ResponseEntity<ApiResponse<RuleResponse>> get(@PathVariable UUID projectId, @PathVariable UUID ruleId) {
        return ResponseEntity.ok(ApiResponse.of(businessRuleService.get(projectId, ruleId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<RuleResponse>> create(@PathVariable UUID projectId, @RequestBody CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(businessRuleService.create(projectId, request)));
    }

    @PutMapping("/{ruleId}")
    public ResponseEntity<ApiResponse<RuleResponse>> update(
            @PathVariable UUID projectId, @PathVariable UUID ruleId, @RequestBody UpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.of(businessRuleService.update(projectId, ruleId, request)));
    }

    @PostMapping("/{ruleId}/approve")
    public ResponseEntity<ApiResponse<RuleResponse>> approve(
            @PathVariable UUID projectId, @PathVariable UUID ruleId, @RequestBody(required = false) ApproveRequest request) {
        return ResponseEntity.ok(ApiResponse.of(businessRuleService.approve(projectId, ruleId, request)));
    }

    @PostMapping("/{ruleId}/reject")
    public ResponseEntity<ApiResponse<RuleResponse>> reject(
            @PathVariable UUID projectId, @PathVariable UUID ruleId, @RequestBody RejectRequest request) {
        return ResponseEntity.ok(ApiResponse.of(businessRuleService.reject(projectId, ruleId, request)));
    }

    /** Sinh test tất định (0 token) từ các luật ĐÃ DUYỆT (body rỗng = mọi luật đã duyệt của project). Chạy đồng bộ như coverage/fill. */
    @PostMapping("/generate-tests")
    public ResponseEntity<ApiResponse<GenerateTestsResponse>> generateTests(
            @PathVariable UUID projectId, @RequestBody(required = false) GenerateTestsRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(ruleTestService.generate(projectId, request)));
    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Void> delete(@PathVariable UUID projectId, @PathVariable UUID ruleId) {
        businessRuleService.delete(projectId, ruleId);
        return ResponseEntity.noContent().build();
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Giá trị '" + value + "' không hợp lệ cho " + name);
        }
    }
}
