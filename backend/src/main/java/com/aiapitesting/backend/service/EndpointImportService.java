package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.response.EndpointResponse;
import com.aiapitesting.backend.dto.response.ImportImpactResponse;
import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.entity.BugStatus;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TargetAuthType;
import com.aiapitesting.backend.entity.TestCaseSource;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.exception.ExecutionInProgressException;
import com.aiapitesting.backend.exception.SwaggerParseException;
import com.aiapitesting.backend.repository.BugReportEventRepository;
import com.aiapitesting.backend.repository.BugReportRepository;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.EndpointRoleRepository;
import com.aiapitesting.backend.repository.TestCaseAssertionRepository;
import com.aiapitesting.backend.repository.TestCaseDependencyRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestExecutionEndpointRepository;
import com.aiapitesting.backend.repository.TestExecutionRepository;
import com.aiapitesting.backend.repository.TestGenerationEventRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.aiapitesting.backend.security.AesEncryptionService;
import com.aiapitesting.backend.security.TargetAuthHeaderResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.servers.Server;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EndpointImportService {

    private final ProjectService projectService;
    private final EndpointRepository endpointRepository;
    private final EndpointRoleRepository endpointRoleRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestResultRepository testResultRepository;
    private final TestExecutionRepository testExecutionRepository;
    private final TestExecutionEndpointRepository testExecutionEndpointRepository;
    private final TestCaseDependencyRepository testCaseDependencyRepository;
    private final TestCaseAssertionRepository testCaseAssertionRepository;
    private final TestGenerationEventRepository testGenerationEventRepository;
    private final BugReportRepository bugReportRepository;
    private final BugReportEventRepository bugReportEventRepository;
    private final SafeUrlFetcher safeUrlFetcher;
    private final AesEncryptionService aesEncryptionService;
    private final TargetAuthHeaderResolver targetAuthHeaderResolver;
    /** Spec quá lớn thì bỏ qua đối chiếu schema (tránh phình DB) - chỉ mất cảnh báo, không ảnh hưởng gì khác. */
    private static final int MAX_RESPONSE_SCHEMAS_LENGTH = 200_000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public List<EndpointResponse> importFromUrl(
            UUID projectId, String url, TargetAuthType authType, String authValue, String targetBaseUrl
    ) {
        projectService.validateTargetAuthValue(authType, authValue);
        String content = fetchUrlContent(url, authType, authValue);
        return doImport(projectId, content, authType, authValue, targetBaseUrl);
    }

    @Transactional
    public List<EndpointResponse> importFromFile(
            UUID projectId, MultipartFile file, TargetAuthType authType, String authValue, String targetBaseUrl
    ) {
        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SwaggerParseException("Không thể đọc file đã tải lên");
        }
        return doImport(projectId, content, authType, authValue, targetBaseUrl);
    }

    /** Số liệu của dữ liệu sẽ bị xoá nếu import lại - giao diện dùng để cảnh báo trước khi xác nhận. */
    @Transactional(readOnly = true)
    public ImportImpactResponse getImportImpact(UUID projectId) {
        Project project = projectService.getOwnedProject(projectId);
        return new ImportImpactResponse(
                endpointRepository.countByProject(project),
                testCaseRepository.countByEndpointProject(project),
                testCaseRepository.countByEndpointProjectAndSource(project, TestCaseSource.MANUAL),
                testCaseRepository.countByEndpointProjectAndLockedTrue(project),
                testExecutionRepository.countByProject(project),
                testResultRepository.countByTestCaseEndpointProject(project),
                bugReportRepository.countByProject(project),
                bugReportRepository.countByProjectAndStatusNot(project, BugStatus.CLOSED),
                testGenerationEventRepository.countByEndpointProject(project),
                testResultRepository.countByTestCaseEndpointProjectAndStatus(project, TestResultStatus.FAILED));
    }

    public PageResponse<EndpointResponse> list(UUID projectId, Pageable pageable) {
        Project project = projectService.getOwnedProject(projectId);
        Page<Endpoint> page = endpointRepository.findAllByProject(project, pageable);

        List<UUID> endpointIds = page.getContent().stream().map(Endpoint::getId).toList();
        Map<UUID, Long> testCaseCountByEndpointId = testCaseRepository.countByEndpointIds(endpointIds).stream()
                .collect(Collectors.toMap(
                        TestCaseRepository.EndpointTestCaseCount::getEndpointId,
                        TestCaseRepository.EndpointTestCaseCount::getCount));

        return PageResponse.from(page, endpoint ->
                EndpointResponse.from(endpoint, testCaseCountByEndpointId.getOrDefault(endpoint.getId(), 0L)));
    }

    private List<EndpointResponse> doImport(
            UUID projectId, String content, TargetAuthType authType, String authValue, String targetBaseUrl
    ) {
        Project project = projectService.getOwnedProject(projectId);
        // Import xoá toàn bộ execution/kết quả của project: đang có lần chạy chưa xong thì để lại bản ghi mồ côi/lỗi FK.
        if (testExecutionRepository.existsByProjectAndStatusIn(project, EnumSet.of(ExecutionStatus.PENDING, ExecutionStatus.RUNNING))) {
            throw new ExecutionInProgressException("Project đang có lần chạy test chưa xong, vui lòng đợi chạy xong rồi import lại");
        }
        applyAuthConfig(project, authType, authValue);

        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        // setResolve(true) chỉ resolve $ref trỏ ra ngoài file - $ref nội bộ vẫn giữ dạng
        // {"$ref": "..."} khi serialize, khiến AI không thấy field/required của requestBody.
        // setResolveFully(true) inline hẳn properties/required/type vào Operation trước khi serialize.
        options.setResolveFully(true);
        SwaggerParseResult result = new OpenAPIV3Parser().readContents(content, null, options);
        OpenAPI openApi = result.getOpenAPI();

        if (openApi == null || openApi.getPaths() == null || openApi.getPaths().isEmpty()) {
            throw new SwaggerParseException("Không parse được nội dung OpenAPI đã cung cấp");
        }

        // targetBaseUrl (nơi gọi API thật) khác `content` đang parse (tài liệu OpenAPI) - ưu tiên
        // giá trị người dùng tự nhập, chỉ suy ra từ servers[] khi để trống.
        project.setTargetBaseUrl(resolveTargetBaseUrl(targetBaseUrl, openApi));

        List<Endpoint> endpoints = new ArrayList<>();
        boolean hasGlobalSecurity = openApi.getSecurity() != null && !openApi.getSecurity().isEmpty();
        openApi.getPaths().forEach((path, pathItem) ->
                pathItem.readOperationsMap().forEach((httpMethod, operation) ->
                        endpoints.add(buildEndpoint(project, path, httpMethod.name(), operation, hasGlobalSecurity))));

        if (endpoints.isEmpty()) {
            throw new SwaggerParseException("Tài liệu OpenAPI không chứa endpoint nào");
        }

        // Dọn hết bảng phụ thuộc endpoint cũ trước khi xoá - đều là khoá ngoại NOT NULL.
        bugReportRepository.deleteAllByProject(project);
        bugReportEventRepository.deleteAllByEndpointProject(project);
        testExecutionEndpointRepository.deleteAllByExecutionProject(project);
        testResultRepository.deleteAllByTestCaseEndpointProject(project);
        testExecutionRepository.deleteAllByProject(project);
        testCaseDependencyRepository.deleteAllByProject(project);
        testCaseAssertionRepository.deleteAllByTestCaseEndpointProject(project);
        testCaseRepository.deleteAllByEndpointProject(project);
        testGenerationEventRepository.deleteAllByEndpointProject(project);
        endpointRoleRepository.deleteAllByEndpointProject(project);
        endpointRepository.deleteAllByProject(project);
        List<Endpoint> saved = endpointRepository.saveAll(endpoints);
        return saved.stream().map(EndpointResponse::from).toList();
    }

    private String resolveTargetBaseUrl(String targetBaseUrl, OpenAPI openApi) {
        if (targetBaseUrl != null && !targetBaseUrl.isBlank()) {
            return targetBaseUrl;
        }
        // Spec không khai báo `servers` thì swagger-parser tự điền server mặc định url "/"
        // (relative) - không dùng được, chỉ nhận URL tuyệt đối làm gợi ý.
        List<Server> servers = openApi.getServers();
        if (servers != null && !servers.isEmpty()) {
            String url = servers.get(0).getUrl();
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                return url;
            }
        }
        return null;
    }

    /** authType NONE nghĩa là "giữ nguyên auth hiện có" ở đây (khác updateTargetAuth - nơi NONE là xoá thật). */
    private void applyAuthConfig(Project project, TargetAuthType authType, String authValue) {
        projectService.validateTargetAuthValue(authType, authValue);
        if (authType == null || authType == TargetAuthType.NONE) {
            return;
        }
        project.setTargetAuthType(authType);
        project.setTargetAuthValueEncrypted(aesEncryptionService.encrypt(authValue));
        project.setTargetAuthUpdatedAt(Instant.now()); // Module 16 - chỉ mốc thời gian, không lưu token
    }

    /** Nếu URL nguồn OpenAPI bị chặn sau login, dùng chính auth đã nhập để gắn header khi tải. */
    private String fetchUrlContent(String url, TargetAuthType authType, String authValue) {
        TargetAuthHeaderResolver.AuthHeader header = targetAuthHeaderResolver.resolve(authType, authValue);
        if (header == null) {
            return safeUrlFetcher.fetch(url);
        }
        return safeUrlFetcher.fetch(url, header.name(), header.value());
    }

    private Endpoint buildEndpoint(
            Project project, String path, String method, Operation operation, boolean hasGlobalSecurity
    ) {
        return Endpoint.builder()
                .project(project)
                .path(path)
                .method(method)
                .summary(operation.getSummary())
                .schema(buildSchemaJson(operation))
                .responseSchemas(buildResponseSchemasJson(operation))
                .requiresAuth(resolveRequiresAuth(operation, hasGlobalSecurity))
                .build();
    }

    /**
     * operation.getSecurity() == null nghĩa là "kế thừa security toàn cục" (OpenAPI spec); danh
     * sách RỖNG (không null) là operation CHỦ ĐỘNG khai báo "không cần auth", override lại global -
     * phải phân biệt rõ 2 trường hợp null vs rỗng, không coi cả 2 là "không yêu cầu" như nhau.
     */
    private boolean resolveRequiresAuth(Operation operation, boolean hasGlobalSecurity) {
        if (operation.getSecurity() == null) {
            return hasGlobalSecurity;
        }
        return !operation.getSecurity().isEmpty();
    }

    /** Schema response 2xx có nội dung JSON, để đối chiếu phản hồi thật lúc chạy test (không gửi cho AI). */
    private String buildResponseSchemasJson(Operation operation) {
        if (operation.getResponses() == null) {
            return null;
        }
        Map<String, Object> schemas = new LinkedHashMap<>();
        operation.getResponses().forEach((code, response) -> {
            if (code == null || !code.matches("2\\d\\d") || response == null || response.getContent() == null) {
                return;
            }
            response.getContent().forEach((mediaType, content) -> {
                // "*/*" là mặc định của springdoc khi controller không khai produces - vẫn là JSON trong thực tế.
                if (mediaType != null && (mediaType.toLowerCase().contains("json") || "*/*".equals(mediaType))
                        && content.getSchema() != null && !schemas.containsKey(code)) {
                    schemas.put(code, content.getSchema());
                }
            });
        });
        if (schemas.isEmpty()) {
            return null;
        }
        try {
            String json = io.swagger.v3.core.util.Json.mapper().writeValueAsString(schemas);
            return json.length() > MAX_RESPONSE_SCHEMAS_LENGTH ? null : json;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Chỉ giữ phần schema AI cần để sinh test case (parameters, requestBody, mã trạng thái) - bỏ
     * full schema body từng response code. setResolveFully(true) inline schema trùng lặp vào MỖI
     * response code, chiếm phần lớn dung lượng (đã đo ~68% cho 1 endpoint 2 response code) và từng
     * khiến AI provider từ chối request quá lớn.
     */
    private String buildSchemaJson(Operation operation) {
        Map<String, Object> trimmed = new LinkedHashMap<>();
        trimmed.put("summary", operation.getSummary());
        if (operation.getParameters() != null && !operation.getParameters().isEmpty()) {
            trimmed.put("parameters", operation.getParameters());
        }
        if (operation.getRequestBody() != null) {
            trimmed.put("requestBody", operation.getRequestBody());
        }
        if (operation.getResponses() != null && !operation.getResponses().isEmpty()) {
            trimmed.put("responseStatusCodes", new ArrayList<>(operation.getResponses().keySet()));
        }
        try {
            return objectMapper.writeValueAsString(trimmed);
        } catch (Exception e) {
            return "{}";
        }
    }
}
