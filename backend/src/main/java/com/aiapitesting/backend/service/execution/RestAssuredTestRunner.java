package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.entity.AuthProfile;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TargetAuthType;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseAuthOverride;
import com.aiapitesting.backend.repository.AuthProfileRepository;
import com.aiapitesting.backend.security.AesEncryptionService;
import com.aiapitesting.backend.security.TargetAuthHeaderResolver;
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

    public record RunResult(int statusCode, String responseBody) {
    }

    public RunResult run(Project project, TestCase testCase, Map<String, String> resolvedValues) {
        String path = testCasePathValidator.substitute(testCase.getResolvedPath(), resolvedValues);

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
        String headersJson = testCasePathValidator.substitute(testCase.getRequestHeaders(), resolvedValues);
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(parseHeaders(headersJson));

        TargetAuthHeaderResolver.AuthHeader authHeader = resolveAuthHeader(project, testCase);
        if (authHeader != null) {
            headers.put(authHeader.name(), authHeader.value());
        }
        // Content-Type do RestAssured gắn theo cách mã hoá body (json/form/multipart/raw): bỏ khỏi map để không gửi trùng 2 header.
        String headerContentType = headers.remove("Content-Type");
        if (!headers.isEmpty()) {
            request = request.headers(headers);
        }

        String body = testCasePathValidator.substitute(testCase.getRequestBody(), resolvedValues);
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
    private TargetAuthHeaderResolver.AuthHeader resolveAuthHeader(Project project, TestCase testCase) {
        TargetAuthType authType = project.getTargetAuthType();
        String encryptedValue = project.getTargetAuthValueEncrypted();
        if (testCase.getAuthProfileId() != null) {
            AuthProfile profile = authProfileRepository.findByIdAndProjectId(testCase.getAuthProfileId(), project.getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Profile xác thực của test case không còn tồn tại trong project - chọn lại profile ở Sửa test case"));
            authType = profile.getAuthType();
            encryptedValue = profile.getAuthValueEncrypted();
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
        String decrypted = aesEncryptionService.decrypt(encryptedValue);
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
