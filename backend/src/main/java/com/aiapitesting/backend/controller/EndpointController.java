package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.request.BulkRequiresAuthRequest;
import com.aiapitesting.backend.dto.request.SetRequiresAuthRequest;
import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.dto.response.AuthSuggestionResponse;
import com.aiapitesting.backend.dto.response.BulkUpdateResponse;
import com.aiapitesting.backend.dto.response.EndpointResponse;
import com.aiapitesting.backend.dto.response.ImportDiffResponse;
import com.aiapitesting.backend.dto.response.ImportImpactResponse;
import com.aiapitesting.backend.entity.ImportMode;
import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.entity.TargetAuthType;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.service.AuthSuggestionService;
import com.aiapitesting.backend.service.EndpointAuthRequirementService;
import com.aiapitesting.backend.service.EndpointImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/endpoints")
@RequiredArgsConstructor
public class EndpointController {

    private final EndpointImportService endpointImportService;
    private final EndpointAuthRequirementService endpointAuthRequirementService;
    private final AuthSuggestionService authSuggestionService;

    @GetMapping("/import-impact")
    public ResponseEntity<ApiResponse<ImportImpactResponse>> getImportImpact(@PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.of(endpointImportService.getImportImpact(projectId)));
    }

    /** Module 17 (1.3) - khai endpoint có yêu cầu xác thực hay không (null = theo spec). */
    @PutMapping("/{endpointId}/requires-auth")
    public ResponseEntity<ApiResponse<EndpointResponse>> setRequiresAuth(
            @PathVariable UUID projectId, @PathVariable UUID endpointId, @RequestBody SetRequiresAuthRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.of(
                endpointAuthRequirementService.setRequiresAuth(projectId, endpointId, request == null ? null : request.requiresAuth())));
    }

    /** Module 17 (S4) - gợi ý (không tự áp dụng) endpoint nào nhiều khả năng cần xác thực khi spec không khai báo xác thực. */
    @GetMapping("/auth-suggestion")
    public ResponseEntity<ApiResponse<AuthSuggestionResponse>> authSuggestion(@PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.of(authSuggestionService.suggest(projectId)));
    }

    @PutMapping("/requires-auth/bulk")
    public ResponseEntity<ApiResponse<BulkUpdateResponse>> bulkSetRequiresAuth(
            @PathVariable UUID projectId, @RequestBody BulkRequiresAuthRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.of(endpointAuthRequirementService.bulkSetRequiresAuth(projectId, request)));
    }

    @PostMapping("/import")
    public ResponseEntity<ApiResponse<List<EndpointResponse>>> importOpenApi(
            @PathVariable UUID projectId,
            @RequestParam(required = false) String url,
            @RequestParam(required = false) MultipartFile file,
            @RequestParam(required = false) TargetAuthType authType,
            @RequestParam(required = false) String authValue,
            @RequestParam(required = false) String targetBaseUrl,
            @RequestParam(defaultValue = "DIFF") ImportMode mode
    ) {
        boolean hasUrl = url != null && !url.isBlank();
        boolean hasFile = file != null && !file.isEmpty();

        if (hasUrl == hasFile) {
            throw new InvalidRequestException("Phải cung cấp đúng một trong hai: url hoặc file");
        }

        List<EndpointResponse> imported = hasUrl
                ? endpointImportService.importFromUrl(projectId, url, authType, authValue, targetBaseUrl, mode)
                : endpointImportService.importFromFile(projectId, file, authType, authValue, targetBaseUrl, mode);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(imported));
    }

    /** Module 17 (17c) - so sánh spec mới với endpoint hiện có, không ghi gì. Cùng tham số với /import. */
    @PostMapping("/import-preview")
    public ResponseEntity<ApiResponse<ImportDiffResponse>> previewImport(
            @PathVariable UUID projectId,
            @RequestParam(required = false) String url,
            @RequestParam(required = false) MultipartFile file,
            @RequestParam(required = false) TargetAuthType authType,
            @RequestParam(required = false) String authValue
    ) {
        boolean hasUrl = url != null && !url.isBlank();
        boolean hasFile = file != null && !file.isEmpty();
        if (hasUrl == hasFile) {
            throw new InvalidRequestException("Phải cung cấp đúng một trong hai: url hoặc file");
        }
        ImportDiffResponse diff = hasUrl
                ? endpointImportService.previewFromUrl(projectId, url, authType, authValue)
                : endpointImportService.previewFromFile(projectId, file);
        return ResponseEntity.ok(ApiResponse.of(diff));
    }

    @GetMapping
    public PageResponse<EndpointResponse> list(
            @PathVariable UUID projectId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        return endpointImportService.list(projectId, pageable);
    }
}
