package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TargetAuthType;
import com.aiapitesting.backend.entity.TestCase;
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

/**
 * Gọi target API thật cho 1 TestCase - chỉ trả về status/response thật, không tự quyết
 * PASSED/FAILED (TestExecutionRunner so sánh với expectedStatus).
 */
@Component
@RequiredArgsConstructor
public class RestAssuredTestRunner {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private final TargetAuthHeaderResolver targetAuthHeaderResolver;
    private final AesEncryptionService aesEncryptionService;
    private final TestCasePathValidator testCasePathValidator;
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
        String headersJson = testCasePathValidator.substitute(testCase.getRequestHeaders(), resolvedValues);
        Map<String, String> headers = parseHeaders(headersJson);
        if (!headers.isEmpty()) {
            request = request.headers(headers);
        }

        TargetAuthHeaderResolver.AuthHeader authHeader = resolveAuthHeader(project);
        if (authHeader != null) {
            request = request.header(authHeader.name(), authHeader.value());
        }

        String body = testCasePathValidator.substitute(testCase.getRequestBody(), resolvedValues);
        if (body != null && !body.isBlank()) {
            request = request.contentType(ContentType.JSON).body(body);
        }

        Response response = request.request(Method.valueOf(testCase.getEndpoint().getMethod()), path);
        return new RunResult(response.statusCode(), response.getBody().asString());
    }

    /**
     * Giải mã targetAuthValueEncrypted và gắn header thật đúng lúc gọi target API - không giải mã
     * hay dùng ở bất kỳ chỗ nào khác (đúng quy tắc CLAUDE.md "giải mã chỉ khi thực thi test").
     */
    private TargetAuthHeaderResolver.AuthHeader resolveAuthHeader(Project project) {
        if (project.getTargetAuthType() == null || project.getTargetAuthType() == TargetAuthType.NONE) {
            return null;
        }
        String decrypted = aesEncryptionService.decrypt(project.getTargetAuthValueEncrypted());
        return targetAuthHeaderResolver.resolve(project.getTargetAuthType(), decrypted);
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
