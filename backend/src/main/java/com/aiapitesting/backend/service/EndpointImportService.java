package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.response.EndpointResponse;
import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TargetAuthType;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.exception.SwaggerParseException;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.security.AesEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EndpointImportService {

    private final ProjectService projectService;
    private final EndpointRepository endpointRepository;
    private final SafeUrlFetcher safeUrlFetcher;
    private final AesEncryptionService aesEncryptionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public List<EndpointResponse> importFromUrl(UUID projectId, String url, TargetAuthType authType, String authValue) {
        String content = safeUrlFetcher.fetch(url);
        return doImport(projectId, content, authType, authValue);
    }

    @Transactional
    public List<EndpointResponse> importFromFile(UUID projectId, MultipartFile file, TargetAuthType authType, String authValue) {
        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SwaggerParseException("Không thể đọc file đã tải lên");
        }
        return doImport(projectId, content, authType, authValue);
    }

    public PageResponse<EndpointResponse> list(UUID projectId, Pageable pageable) {
        Project project = projectService.getOwnedProject(projectId);
        Page<Endpoint> page = endpointRepository.findAllByProject(project, pageable);
        return PageResponse.from(page, EndpointResponse::from);
    }

    private List<EndpointResponse> doImport(UUID projectId, String content, TargetAuthType authType, String authValue) {
        Project project = projectService.getOwnedProject(projectId);
        applyAuthConfig(project, authType, authValue);

        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        SwaggerParseResult result = new OpenAPIV3Parser().readContents(content, null, options);
        OpenAPI openApi = result.getOpenAPI();

        if (openApi == null || openApi.getPaths() == null || openApi.getPaths().isEmpty()) {
            throw new SwaggerParseException("Không parse được nội dung OpenAPI đã cung cấp");
        }

        List<Endpoint> endpoints = new ArrayList<>();
        openApi.getPaths().forEach((path, pathItem) ->
                pathItem.readOperationsMap().forEach((httpMethod, operation) ->
                        endpoints.add(buildEndpoint(project, path, httpMethod.name(), operation))));

        if (endpoints.isEmpty()) {
            throw new SwaggerParseException("Tài liệu OpenAPI không chứa endpoint nào");
        }

        endpointRepository.deleteAllByProject(project);
        List<Endpoint> saved = endpointRepository.saveAll(endpoints);
        return saved.stream().map(EndpointResponse::from).toList();
    }

    private void applyAuthConfig(Project project, TargetAuthType authType, String authValue) {
        if (authType == null || authType == TargetAuthType.NONE) {
            return;
        }
        if (authValue == null || authValue.isBlank()) {
            throw new InvalidRequestException("Thiếu giá trị xác thực cho loại xác thực đã chọn");
        }
        project.setTargetAuthType(authType);
        project.setTargetAuthValueEncrypted(aesEncryptionService.encrypt(authValue));
    }

    private Endpoint buildEndpoint(Project project, String path, String method, Operation operation) {
        String schemaJson;
        try {
            schemaJson = objectMapper.writeValueAsString(operation);
        } catch (Exception e) {
            schemaJson = "{}";
        }

        return Endpoint.builder()
                .project(project)
                .path(path)
                .method(method)
                .summary(operation.getSummary())
                .schema(schemaJson)
                .build();
    }
}
