package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.dto.response.EndpointResponse;
import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.entity.TargetAuthType;
import com.aiapitesting.backend.exception.InvalidRequestException;
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

    @PostMapping("/import")
    public ResponseEntity<ApiResponse<List<EndpointResponse>>> importOpenApi(
            @PathVariable UUID projectId,
            @RequestParam(required = false) String url,
            @RequestParam(required = false) MultipartFile file,
            @RequestParam(required = false) TargetAuthType authType,
            @RequestParam(required = false) String authValue,
            @RequestParam(required = false) String targetBaseUrl
    ) {
        boolean hasUrl = url != null && !url.isBlank();
        boolean hasFile = file != null && !file.isEmpty();

        if (hasUrl == hasFile) {
            throw new InvalidRequestException("Phải cung cấp đúng một trong hai: url hoặc file");
        }

        List<EndpointResponse> imported = hasUrl
                ? endpointImportService.importFromUrl(projectId, url, authType, authValue, targetBaseUrl)
                : endpointImportService.importFromFile(projectId, file, authType, authValue, targetBaseUrl);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(imported));
    }

    @GetMapping
    public PageResponse<EndpointResponse> list(
            @PathVariable UUID projectId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        return endpointImportService.list(projectId, pageable);
    }
}
