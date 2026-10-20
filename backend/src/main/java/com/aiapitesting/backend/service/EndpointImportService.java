package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.response.EndpointResponse;
import com.aiapitesting.backend.dto.response.ImportDiffResponse;
import com.aiapitesting.backend.dto.response.ImportImpactResponse;
import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.entity.BugStatus;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.ImportMode;
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
import io.swagger.parser.OpenAPIParser; // tự nhận Swagger 2.0 (chuyển sang OpenAPI 3) lẫn OpenAPI 3.0/3.1
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

    /** Dạng cũ (giữ cho test/nơi gọi sẵn có): thay thế toàn bộ. Controller dùng dạng có {@link ImportMode}. */
    @Transactional
    public List<EndpointResponse> importFromUrl(
            UUID projectId, String url, TargetAuthType authType, String authValue, String targetBaseUrl
    ) {
        return importFromUrl(projectId, url, authType, authValue, targetBaseUrl, ImportMode.REPLACE);
    }

    @Transactional
    public List<EndpointResponse> importFromUrl(
            UUID projectId, String url, TargetAuthType authType, String authValue, String targetBaseUrl, ImportMode mode
    ) {
        projectService.validateTargetAuthValue(authType, authValue);
        String content = fetchUrlContent(url, authType, authValue);
        return doImport(projectId, content, authType, authValue, targetBaseUrl, mode);
    }

    @Transactional
    public List<EndpointResponse> importFromFile(
            UUID projectId, MultipartFile file, TargetAuthType authType, String authValue, String targetBaseUrl
    ) {
        return importFromFile(projectId, file, authType, authValue, targetBaseUrl, ImportMode.REPLACE);
    }

    @Transactional
    public List<EndpointResponse> importFromFile(
            UUID projectId, MultipartFile file, TargetAuthType authType, String authValue, String targetBaseUrl, ImportMode mode
    ) {
        return doImport(projectId, readFile(file), authType, authValue, targetBaseUrl, mode);
    }

    /** Module 17 (17c) - so sánh spec mới với endpoint hiện có, KHÔNG ghi gì (kể cả xác thực/targetBaseUrl). */
    public ImportDiffResponse previewFromFile(UUID projectId, MultipartFile file) {
        return preview(projectId, readFile(file));
    }

    public ImportDiffResponse previewFromUrl(UUID projectId, String url, TargetAuthType authType, String authValue) {
        projectService.validateTargetAuthValue(authType, authValue);
        return preview(projectId, fetchUrlContent(url, authType, authValue));
    }

    private String readFile(MultipartFile file) {
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SwaggerParseException("Không thể đọc file đã tải lên");
        }
    }

    private ImportDiffResponse preview(UUID projectId, String content) {
        Project project = projectService.getOwnedProject(projectId);
        List<Endpoint> incoming = parseEndpoints(project, content).endpoints();
        return diff(project, incoming).toResponse(this::testCaseCountsOf);
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

    private record Parsed(OpenAPI openApi, List<Endpoint> endpoints) {
    }

    private Parsed parseEndpoints(Project project, String content) {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        // setResolve(true) chỉ resolve $ref trỏ ra ngoài file - $ref nội bộ vẫn giữ dạng
        // {"$ref": "..."} khi serialize, khiến AI không thấy field/required của requestBody.
        // setResolveFully(true) inline hẳn properties/required/type vào Operation trước khi serialize.
        options.setResolveFully(true);
        SwaggerParseResult result = new OpenAPIParser().readContents(content, null, options);
        OpenAPI openApi = result.getOpenAPI();

        if (openApi == null || openApi.getPaths() == null || openApi.getPaths().isEmpty()) {
            throw new SwaggerParseException("Không parse được nội dung OpenAPI đã cung cấp");
        }

        List<Endpoint> endpoints = new ArrayList<>();
        boolean hasGlobalSecurity = openApi.getSecurity() != null && !openApi.getSecurity().isEmpty();
        openApi.getPaths().forEach((path, pathItem) ->
                pathItem.readOperationsMap().forEach((httpMethod, operation) ->
                        endpoints.add(buildEndpoint(project, path, httpMethod.name(), operation, hasGlobalSecurity))));

        if (endpoints.isEmpty()) {
            throw new SwaggerParseException("Tài liệu OpenAPI không chứa endpoint nào");
        }
        endpoints.forEach(e -> e.setSpecHash(specHashOf(e)));
        return new Parsed(openApi, endpoints);
    }

    private List<EndpointResponse> doImport(
            UUID projectId, String content, TargetAuthType authType, String authValue, String targetBaseUrl, ImportMode mode
    ) {
        Project project = projectService.getOwnedProject(projectId);
        boolean diffMode = mode == ImportMode.DIFF && endpointRepository.countByProject(project) > 0;
        // Thay thế xoá toàn bộ execution/kết quả của project: đang có lần chạy chưa xong thì để lại bản ghi mồ côi/lỗi FK.
        // Import theo diff không xoá gì nên không cần chặn.
        if (!diffMode && testExecutionRepository.existsByProjectAndStatusIn(project, EnumSet.of(ExecutionStatus.PENDING, ExecutionStatus.RUNNING))) {
            throw new ExecutionInProgressException("Project đang có lần chạy test chưa xong, vui lòng đợi chạy xong rồi import lại");
        }
        applyAuthConfig(project, authType, authValue);

        Parsed parsed = parseEndpoints(project, content);
        // targetBaseUrl (nơi gọi API thật) khác `content` đang parse (tài liệu OpenAPI) - ưu tiên
        // giá trị người dùng tự nhập, chỉ suy ra từ servers[] khi để trống.
        // Chế độ DIFF: người dùng để trống ô này khi chỉ cập nhật spec - KHÔNG được ghi đè URL đã cấu hình bằng giá trị suy từ servers[]
        // (hoặc null) làm mọi lần chạy sau trỏ sai đích. Chỉ ghi khi người dùng nhập, hoặc project chưa có URL nào.
        boolean userGaveBaseUrl = targetBaseUrl != null && !targetBaseUrl.isBlank();
        if (!diffMode || userGaveBaseUrl || project.getTargetBaseUrl() == null || project.getTargetBaseUrl().isBlank()) {
            project.setTargetBaseUrl(resolveTargetBaseUrl(targetBaseUrl, parsed.openApi()));
        }

        if (diffMode) {
            return applyDiff(project, parsed.endpoints());
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
        List<Endpoint> saved = endpointRepository.saveAll(parsed.endpoints());
        return saved.stream().map(EndpointResponse::from).toList();
    }

    // ---- Module 17 (17c) - import theo diff ----

    /** Khoá nhận diện 1 endpoint giữa 2 lần import: method + path (không phân biệt hoa/thường ở method). */
    private static String keyOf(Endpoint endpoint) {
        return endpoint.getMethod().toUpperCase(java.util.Locale.ROOT) + " " + endpoint.getPath();
    }

    /**
     * Phần spec làm đổi điều cần kiểm thử: schema + schema response + có yêu cầu xác thực không. Mô tả (summary) KHÔNG tính -
     * cột {@code schema} có ghi kèm "summary" nên phải bỏ ra trước khi băm, nếu không đổi 1 câu mô tả cũng thành "spec đổi".
     */
    static String specHashOf(Endpoint endpoint) {
        return sha256(schemaPart(endpoint) + "\u0000" + (endpoint.getResponseSchemas() == null ? "" : endpoint.getResponseSchemas())
                + "\u0000" + endpoint.isRequiresAuth());
    }

    /** Hash CHỈ phần schema + requiresAuth - dùng so endpoint cũ (chưa có specHash, response schema có thể chưa lưu) cho công bằng. */
    static String schemaOnlyHashOf(Endpoint endpoint) {
        return sha256(schemaPart(endpoint) + "\u0000" + endpoint.isRequiresAuth());
    }

    private static String schemaPart(Endpoint endpoint) {
        String schema = endpoint.getSchema();
        if (schema == null || schema.isBlank()) {
            return "";
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = SCHEMA_MAPPER.readTree(schema);
            if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                object.remove("summary");
            }
            return node.toString();
        } catch (Exception e) {
            return schema; // schema hỏng: băm nguyên văn (vẫn ổn định)
        }
    }

    private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();

    private static String sha256(String canonical) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 luôn có sẵn trong JDK", e);
        }
    }

    /** existing + bản mới tương ứng; unchanged/changed tách theo hash. */
    private record Pair(Endpoint existing, Endpoint incoming) {
    }

    private record Diff(List<Endpoint> added, List<Pair> changed, List<Pair> unchanged, List<Endpoint> missing) {
        ImportDiffResponse toResponse(java.util.function.Function<List<UUID>, Map<UUID, Long>> countTestCases) {
            List<UUID> changedIds = changed.stream().map(p -> p.existing().getId()).toList();
            long affected = changedIds.isEmpty() ? 0 : countTestCases.apply(changedIds).values().stream().mapToLong(Long::longValue).sum();
            return new ImportDiffResponse(added.size(), changed.size(), missing.size(), unchanged.size(), affected,
                    changed.stream().map(p -> keyOf(p.existing())).toList(),
                    missing.stream().map(EndpointImportService::keyOf).toList(),
                    added.stream().map(EndpointImportService::keyOf).toList());
        }
    }

    /** Số test case theo endpoint, truy vấn theo lô để không vượt giới hạn tham số của mệnh đề IN với project hàng nghìn endpoint. */
    private Map<UUID, Long> testCaseCountsOf(List<UUID> endpointIds) {
        Map<UUID, Long> counts = new java.util.HashMap<>();
        for (int from = 0; from < endpointIds.size(); from += 500) {
            List<UUID> batch = endpointIds.subList(from, Math.min(from + 500, endpointIds.size()));
            testCaseRepository.countByEndpointIds(batch).forEach(row -> counts.put(row.getEndpointId(), row.getCount()));
        }
        return counts;
    }

    private Diff diff(Project project, List<Endpoint> incoming) {
        Map<String, Endpoint> existingByKey = new java.util.LinkedHashMap<>();
        endpointRepository.findAllByProjectOrderByCreatedAtAsc(project).forEach(e -> existingByKey.putIfAbsent(keyOf(e), e));
        List<Endpoint> added = new ArrayList<>();
        List<Pair> changed = new ArrayList<>();
        List<Pair> unchanged = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Endpoint candidate : incoming) {
            String key = keyOf(candidate);
            if (!seen.add(key)) {
                continue; // spec khai trùng method+path: lấy bản đầu
            }
            Endpoint existing = existingByKey.get(key);
            if (existing == null) {
                added.add(candidate);
                continue;
            }
            // Endpoint cũ chưa có specHash (import trước Module 17): response schema của nó thường chưa được lưu ("cần import
            // lại") nên so cả hash sẽ báo "đổi" hàng loạt - chỉ so phần schema + requiresAuth.
            boolean same = existing.getSpecHash() != null
                    ? existing.getSpecHash().equals(candidate.getSpecHash())
                    : schemaOnlyHashOf(existing).equals(schemaOnlyHashOf(candidate));
            (same ? unchanged : changed).add(new Pair(existing, candidate));
        }
        List<Endpoint> missing = existingByKey.entrySet().stream()
                .filter(en -> !seen.contains(en.getKey())).map(Map.Entry::getValue).toList();
        return new Diff(added, changed, unchanged, missing);
    }

    private List<EndpointResponse> applyDiff(Project project, List<Endpoint> incoming) {
        Diff diff = diff(project, incoming);
        Instant now = Instant.now();
        List<Endpoint> toSave = new ArrayList<>(diff.added());
        for (Pair pair : diff.changed()) {
            Endpoint e = pair.existing();
            Endpoint n = pair.incoming();
            e.setSchema(n.getSchema());
            e.setResponseSchemas(n.getResponseSchemas());
            e.setRequiresAuth(n.isRequiresAuth());
            e.setRequestContentType(n.getRequestContentType());
            e.setSummary(n.getSummary());
            e.setSpecHash(n.getSpecHash());
            e.setSchemaChangedAt(now);
            e.setMissingFromSpec(false);
            toSave.add(e);
        }
        for (Pair pair : diff.unchanged()) {
            Endpoint e = pair.existing();
            Endpoint n = pair.incoming();
            // Không đổi nội dung kiểm thử: chỉ làm mới mô tả, bỏ cờ "không còn trong spec", và bổ sung dữ liệu endpoint cũ còn thiếu
            // (specHash, response schema) mà KHÔNG đặt schemaChangedAt.
            boolean dirty = false;
            if (!java.util.Objects.equals(e.getSummary(), n.getSummary())) {
                e.setSummary(n.getSummary());
                dirty = true;
            }
            if (e.getSpecHash() == null || e.getResponseSchemas() == null && n.getResponseSchemas() != null) {
                e.setSpecHash(n.getSpecHash());
                e.setResponseSchemas(n.getResponseSchemas());
                dirty = true;
            }
            if (Boolean.TRUE.equals(e.getMissingFromSpec())) {
                e.setMissingFromSpec(false);
                dirty = true;
            }
            if (dirty) {
                toSave.add(e);
            }
        }
        diff.missing().forEach(e -> {
            if (!Boolean.TRUE.equals(e.getMissingFromSpec())) {
                e.setMissingFromSpec(true);
                toSave.add(e);
            }
        });
        endpointRepository.saveAll(toSave);
        List<Endpoint> all = endpointRepository.findAllByProjectOrderByCreatedAtAsc(project);
        Map<UUID, Long> testCaseCounts = testCaseCountsOf(all.stream().map(Endpoint::getId).toList());
        return all.stream().map(e -> EndpointResponse.from(e, testCaseCounts.getOrDefault(e.getId(), 0L))).toList();
    }

    private String resolveTargetBaseUrl(String targetBaseUrl, OpenAPI openApi) {
        return resolveTargetBaseUrl(targetBaseUrl, openApi.getServers());
    }

    /**
     * Theo chuẩn OpenAPI, đường dẫn trong {@code servers[0].url} (hoặc {@code basePath} của Swagger 2.0, được parser đổi thành servers) là TIỀN TỐ của
     * mọi path. Người dùng thường chỉ nhập host ("http://host:8080") nên nếu không nối tiền tố này mọi lời gọi sẽ sai đường dẫn (404). Quy tắc:
     * URL người dùng nhập không có path (hoặc chỉ "/") thì nối thêm tiền tố của spec; đã có path riêng thì giữ nguyên (họ tự quyết định).
     * Để trống thì lấy nguyên URL tuyệt đối của spec (URL tương đối như "/" không dùng được).
     */
    static String resolveTargetBaseUrl(String targetBaseUrl, List<Server> servers) {
        String serverUrl = servers == null || servers.isEmpty() ? null : servers.get(0).getUrl();
        if (targetBaseUrl != null && !targetBaseUrl.isBlank()) {
            String basePath = pathOf(serverUrl);
            String given = targetBaseUrl.trim();
            if (basePath != null && pathOf(given) == null) {
                return (given.endsWith("/") ? given.substring(0, given.length() - 1) : given) + basePath;
            }
            return given;
        }
        if (serverUrl != null && (serverUrl.startsWith("http://") || serverUrl.startsWith("https://"))) {
            return serverUrl;
        }
        return null;
    }

    /** Path của URL (tuyệt đối hoặc tương đối) không có dấu "/" cuối; null nếu không có path hoặc chỉ là "/". */
    static String pathOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String path;
        if (url.startsWith("http://") || url.startsWith("https://")) {
            try {
                path = java.net.URI.create(url.trim()).getRawPath();
            } catch (IllegalArgumentException e) {
                return null;
            }
        } else if (url.startsWith("/")) {
            path = url.trim();
        } else {
            return null;
        }
        if (path == null || path.isBlank() || path.equals("/")) {
            return null;
        }
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
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
        if (header.query()) {
            // Khoá API qua query string: gắn vào URL tải tài liệu (mã hoá giá trị) thay vì header
            String separator = url.contains("?") ? "&" : "?";
            return safeUrlFetcher.fetch(url + separator + header.name() + "="
                    + java.net.URLEncoder.encode(header.value(), java.nio.charset.StandardCharsets.UTF_8));
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
                .requestContentType(preferredRequestContentType(operation))
                .build();
    }

    /** Ưu tiên JSON (kể cả +json); nếu spec chỉ khai loại khác thì lấy loại đầu tiên; không có body thì null. */
    static String preferredRequestContentType(Operation operation) {
        if (operation.getRequestBody() == null || operation.getRequestBody().getContent() == null
                || operation.getRequestBody().getContent().isEmpty()) {
            return null;
        }
        java.util.Set<String> types = operation.getRequestBody().getContent().keySet();
        String chosen = types.stream().filter(t -> t.toLowerCase(java.util.Locale.ROOT).contains("json")).findFirst()
                .orElse(types.iterator().next());
        return chosen.length() > 100 ? chosen.substring(0, 100) : chosen;
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
