package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.entity.AuthProfile;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TargetAuthType;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseAuthOverride;
import com.aiapitesting.backend.repository.AuthProfileRepository;
import com.aiapitesting.backend.security.AesEncryptionService;
import com.aiapitesting.backend.security.TargetAuthHeaderResolver;
import com.aiapitesting.backend.service.DynamicPlaceholderResolver;
import com.aiapitesting.backend.service.TestCasePathValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.http.Method;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;

/**
 * Gọi target API thật cho 1 TestCase - chỉ trả về status/response thật, không tự quyết
 * PASSED/FAILED (TestExecutionRunner so sánh với expectedStatus).
 */
@Component
@RequiredArgsConstructor
public class RestAssuredTestRunner {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    /** Package-private: FailureCauseClassifier (Module 16) so thời gian phản hồi với giới hạn này làm bằng chứng "chờ sát timeout". */
    static final int READ_TIMEOUT_MS = 30_000;
    /** Giá trị cố định, cố tình sai - dùng cho case Security authOverride=INVALID (Module 9a). */
    private static final String INVALID_AUTH_VALUE = "invalid-token-security-test-case";

    private final TargetAuthHeaderResolver targetAuthHeaderResolver;
    private final AesEncryptionService aesEncryptionService;
    private final TestCasePathValidator testCasePathValidator;
    private final AuthProfileRepository authProfileRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuthTokenService authTokenService = new AuthTokenService();

    public record RunResult(int statusCode, String responseBody) {
    }

    public RunResult run(Project project, TestCase testCase, Map<String, String> resolvedValues) {
        return run(project, testCase, resolvedValues, new DynamicPlaceholderResolver.RunScope());
    }

    /** Chạy với scope cho trước: {{uuid:a}} dùng lại cùng giá trị giữa các lần gọi cùng scope (test "gửi 2 lần" của luật UNIQUE). */
    public RunResult run(Project project, TestCase testCase, Map<String, String> resolvedValues, DynamicPlaceholderResolver.RunScope scope) {
        AuthProfile loginProfile = loginProfileInUse(project, testCase);
        java.time.Instant tokenBefore = loginProfile == null ? null : authTokenService.fetchedAt(loginProfile.getId());
        RunResult first = execute(project, testCase, resolvedValues, false, scope);
        // Token tự lấy (profile chế độ Đăng nhập) có thể hết hạn giữa lượt: gặp 401 thì đăng nhập lại và thử ĐÚNG 1 lần.
        // Chỉ thử lại khi token đến từ cache (token vừa đăng nhập xong mà vẫn 401 thì không phải do hết hạn) và test không CHỦ ĐÍCH
        // kỳ vọng 401/403 - tránh gửi lại request không idempotent vô ích. Test Security NONE/INVALID không có loginProfile nên bỏ qua.
        Integer expected = testCase.getExpectedStatus();
        boolean expectsDenial = expected != null && (expected == 401 || expected == 403);
        if (first.statusCode() == 401 && !expectsDenial && loginProfile != null
                && java.util.Objects.equals(tokenBefore, authTokenService.fetchedAt(loginProfile.getId()))) {
            authTokenService.evict(loginProfile.getId());
            return execute(project, testCase, resolvedValues, true, scope);
        }
        return first;
    }

    private AuthProfile loginProfileInUse(Project project, TestCase testCase) {
        if (testCase.getAuthProfileId() == null || testCase.getAuthOverride() == TestCaseAuthOverride.NONE
                || testCase.getAuthOverride() == TestCaseAuthOverride.INVALID) {
            return null;
        }
        return authProfileRepository.findByIdAndProjectId(testCase.getAuthProfileId(), project.getId())
                .filter(AuthProfile::isLoginMode).orElse(null);
    }

    private RunResult execute(Project project, TestCase testCase, Map<String, String> resolvedValues, boolean refreshToken,
                              DynamicPlaceholderResolver.RunScope scope) {
        String path = testCasePathValidator.substitute(testCase.getResolvedPath(), resolvedValues, scope);

        RequestSpecification request = RestAssured.given()
                .baseUri(project.getTargetBaseUrl())
                .config(RestAssuredConfig.config().httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", CONNECT_TIMEOUT_MS)
                        .setParam("http.socket.timeout", READ_TIMEOUT_MS)));

        // Thay {{tenThamSo}} cả trong header/body - dependency (Module 7) không chỉ nhắm vào path.
        // Gộp vào 1 Map (case-insensitive) rồi gọi .headers() đúng 1 lần - RestAssured's .header()
        // gọi SAU .headers() sẽ THÊM header trùng tên thay vì thay thế, nên nếu tách 2 lệnh như
        // trước, 1 test case Security (Module 9a) tự sinh sẵn header "Authorization" giả trong
        // requestBody sẽ khiến request có 2 header Authorization cùng lúc (giả + auth header thật ở
        // dưới) - target API nhận 2 giá trị Authorization sẽ từ chối luôn (403) dù giá trị thật vẫn
        // có mặt, làm sai lệch hẳn kết quả test case authOverride=DEFAULT (đã gặp thật khi verify).
        String headersJson = testCasePathValidator.substitute(testCase.getRequestHeaders(), resolvedValues, scope);
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(parseHeaders(headersJson));

        TargetAuthHeaderResolver.AuthHeader authHeader = resolveAuthHeader(project, testCase, refreshToken);
        if (authHeader != null && authHeader.query()) {
            request = request.queryParam(authHeader.name(), authHeader.value());
        } else if (authHeader != null) {
            headers.put(authHeader.name(), authHeader.value());
        }
        // Content-Type do RestAssured gắn theo cách mã hoá body (json/form/multipart/raw): bỏ khỏi map để không gửi trùng 2 header.
        String headerContentType = headers.remove("Content-Type");
        if (!headers.isEmpty()) {
            request = request.headers(headers);
        }

        String body = testCasePathValidator.substitute(testCase.getRequestBody(), resolvedValues, scope);
        if (body != null && !body.isBlank()) {
            RequestBodyEncoder.Plan plan = RequestBodyEncoder.plan(headerContentType, testCase.getEndpoint().getRequestContentType(), body);
            request = switch (plan.kind()) {
                case FORM -> request.contentType(plan.mediaType()).formParams(plan.fields());
                case MULTIPART -> {
                    RequestSpecification multipart = request.contentType(plan.mediaType());
                    for (Map.Entry<String, String> field : plan.fields().entrySet()) {
                        multipart = multipart.multiPart(field.getKey(), field.getValue());
                    }
                    yield multipart;
                }
                case JSON, RAW -> request.contentType(plan.mediaType()).body(body);
            };
        } else if (headerContentType != null) {
            request = request.contentType(headerContentType);
        }

        Response response = request.request(Method.valueOf(testCase.getEndpoint().getMethod()), path);
        return new RunResult(response.statusCode(), response.getBody().asString());
    }

    /**
     * Giải mã token và gắn header thật đúng lúc gọi target API - không giải mã hay dùng ở bất kỳ chỗ nào khác (đúng quy tắc
     * CLAUDE.md "giải mã chỉ khi thực thi test"). Thứ tự ưu tiên (Module 17 - 3.1): authOverride NONE/INVALID (case Security cố
     * tình bỏ/sai token - không bao giờ chạm tới token thật) → profile xác thực của test case → token mặc định của Project.
     * Profile không còn/không thuộc project thì BÁO LỖI thay vì âm thầm rơi về token mặc định (sẽ cho kết quả sai vai trò).
     */
    private TargetAuthHeaderResolver.AuthHeader resolveAuthHeader(Project project, TestCase testCase, boolean refreshToken) {
        TargetAuthType authType = project.getTargetAuthType();
        String encryptedValue = project.getTargetAuthValueEncrypted();
        AuthProfile loginProfile = null;
        if (testCase.getAuthProfileId() != null) {
            AuthProfile profile = authProfileRepository.findByIdAndProjectId(testCase.getAuthProfileId(), project.getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Profile xác thực của test case không còn tồn tại trong project - chọn lại profile ở Sửa test case"));
            authType = profile.getAuthType();
            encryptedValue = profile.getAuthValueEncrypted();
            loginProfile = profile.isLoginMode() ? profile : null;
        }
        if (authType == null || authType == TargetAuthType.NONE) {
            return null;
        }
        TestCaseAuthOverride authOverride = testCase.getAuthOverride();
        if (authOverride == TestCaseAuthOverride.NONE) {
            return null;
        }
        if (authOverride == TestCaseAuthOverride.INVALID) {
            String real = authType == TargetAuthType.CUSTOM_HEADER ? aesEncryptionService.decrypt(encryptedValue) : null;
            return targetAuthHeaderResolver.resolveInvalid(authType, real, INVALID_AUTH_VALUE);
        }
        String decrypted = loginProfile != null
                ? authTokenService.token(project, loginProfile, aesEncryptionService.decrypt(loginProfile.getLoginBodyEncrypted()), refreshToken)
                : aesEncryptionService.decrypt(encryptedValue);
        return targetAuthHeaderResolver.resolve(authType, decrypted);
    }

    private Map<String, String> parseHeaders(String requestHeadersJson) {
        if (requestHeadersJson == null || requestHeadersJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(requestHeadersJson, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
