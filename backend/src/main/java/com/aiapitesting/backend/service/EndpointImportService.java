package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.response.EndpointResponse;
import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TargetAuthType;
import com.aiapitesting.backend.exception.SwaggerParseException;
import com.aiapitesting.backend.repository.BugReportEventRepository;
import com.aiapitesting.backend.repository.BugReportRepository;
import com.aiapitesting.backend.repository.EndpointRepository;
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
import java.util.ArrayList;
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
        applyAuthConfig(project, authType, authValue);

        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        // setResolve(true) chỉ resolve $ref TRỎ RA NGOÀI file (multi-file spec) - $ref nội bộ dạng
        // "#/components/schemas/..." vẫn giữ nguyên dạng {"$ref": "..."} khi serialize Operation,
        // khiến AI sinh test case KHÔNG THẤY được field/required nào của requestBody (chỉ thấy 1
        // chuỗi $ref vô nghĩa với nó) - đây là nguyên nhân AI hay thiếu field bắt buộc. setResolveFully
        // mới thật sự inline properties/required/type vào ngay trong cây Operation trước khi serialize.
        options.setResolveFully(true);
        SwaggerParseResult result = new OpenAPIV3Parser().readContents(content, null, options);
        OpenAPI openApi = result.getOpenAPI();

        if (openApi == null || openApi.getPaths() == null || openApi.getPaths().isEmpty()) {
            throw new SwaggerParseException("Không parse được nội dung OpenAPI đã cung cấp");
        }

        // targetBaseUrl (nơi gọi API thật lúc thực thi test, Module 6) khác hoàn toàn với `content`
        // đang parse ở đây (tài liệu OpenAPI, có thể host ở domain khác) - ưu tiên giá trị người
        // dùng tự nhập, chỉ suy ra từ servers[] của chính spec khi người dùng để trống.
        project.setTargetBaseUrl(resolveTargetBaseUrl(targetBaseUrl, openApi));

        List<Endpoint> endpoints = new ArrayList<>();
        openApi.getPaths().forEach((path, pathItem) ->
                pathItem.readOperationsMap().forEach((httpMethod, operation) ->
                        endpoints.add(buildEndpoint(project, path, httpMethod.name(), operation))));

        if (endpoints.isEmpty()) {
            throw new SwaggerParseException("Tài liệu OpenAPI không chứa endpoint nào");
        }

        // Dọn BugReport (Module 10)/BugReportEvent/TestExecutionEndpoint/TestResult/TestExecution/
        // TestCaseDependency/TestCaseAssertion/TestGenerationEvent/test case của các endpoint cũ
        // trước khi xoá endpoint - đều là khoá ngoại NOT NULL, xoá endpoint trước khi còn bị tham
        // chiếu sẽ vi phạm khoá ngoại (lỗi MySQL 1451, đã gặp nhiều lần trong dự án này).
        bugReportRepository.deleteAllByProject(project);
        bugReportEventRepository.deleteAllByEndpointProject(project);
        testExecutionEndpointRepository.deleteAllByExecutionProject(project);
        testResultRepository.deleteAllByTestCaseEndpointProject(project);
        testExecutionRepository.deleteAllByProject(project);
        testCaseDependencyRepository.deleteAllByProject(project);
        testCaseAssertionRepository.deleteAllByTestCaseEndpointProject(project);
        testCaseRepository.deleteAllByEndpointProject(project);
        testGenerationEventRepository.deleteAllByEndpointProject(project);
        endpointRepository.deleteAllByProject(project);
        List<Endpoint> saved = endpointRepository.saveAll(endpoints);
        return saved.stream().map(EndpointResponse::from).toList();
    }

    private String resolveTargetBaseUrl(String targetBaseUrl, OpenAPI openApi) {
        if (targetBaseUrl != null && !targetBaseUrl.isBlank()) {
            return targetBaseUrl;
        }
        // Theo chuẩn OpenAPI 3.0, spec không khai báo `servers` thì swagger-parser tự điền 1 server
        // mặc định url "/" (relative) - không dùng được làm base URL gọi API thật, chỉ nhận URL
        // tuyệt đối (http/https) làm gợi ý.
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
    }

    /**
     * Nếu URL nguồn OpenAPI bị chặn sau login, dùng chính auth đã nhập (vốn được lưu lại
     * cho Module 6) để gắn header khi tải — không phải mọi API bị chặn login đều có cách
     * xác thực nào khác ngoài giá trị người dùng đã cung cấp.
     */
    private String fetchUrlContent(String url, TargetAuthType authType, String authValue) {
        TargetAuthHeaderResolver.AuthHeader header = targetAuthHeaderResolver.resolve(authType, authValue);
        if (header == null) {
            return safeUrlFetcher.fetch(url);
        }
        return safeUrlFetcher.fetch(url, header.name(), header.value());
    }

    private Endpoint buildEndpoint(Project project, String path, String method, Operation operation) {
        return Endpoint.builder()
                .project(project)
                .path(path)
                .method(method)
                .summary(operation.getSummary())
                .schema(buildSchemaJson(operation))
                .build();
    }

    /**
     * Chỉ giữ phần schema AI thực sự cần để sinh test case (parameters, requestBody, danh sách mã
     * trạng thái đã document) - bỏ hẳn full schema body của từng response code. swagger-parser với
     * setResolveFully(true) inline nguyên schema (thường trùng lặp gần như y hệt request schema)
     * vào MỖI response code documented, khiến field "responses" chiếm phần lớn dung lượng schema
     * (đã đo thực tế: 18191/26519 ký tự - 68% - cho 1 endpoint chỉ có 2 response code) mà không
     * mang thêm giá trị cho AI ngoài việc biết mã đó có tồn tại - đây là nguyên nhân chính khiến
     * endpoint có nhiều response code document (PUT/PATCH/GET theo id) hay bị Groq từ chối 413 dù
     * endpoint đơn giản hơn (POST) vẫn sinh được bình thường.
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
