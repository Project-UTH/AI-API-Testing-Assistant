package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TargetAuthType;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseAuthOverride;
import com.aiapitesting.backend.security.AesEncryptionService;
import com.aiapitesting.backend.security.TargetAuthHeaderResolver;
import com.aiapitesting.backend.service.TestCasePathValidator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test tích hợp thật với 1 HTTP server cục bộ (com.sun.net.httpserver.HttpServer, có sẵn trong JDK,
 * không thêm dependency) - kiểm header/status/body thật thay vì mock, không phụ thuộc mạng ngoài.
 */
class RestAssuredTestRunnerTest {

    private HttpServer server;
    private CapturingHandler handler;
    private RestAssuredTestRunner runner;
    private Project project;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        handler = new CapturingHandler(200, "{\"id\":1}");
        server.createContext("/", handler);

        AesEncryptionService aesEncryptionService =
                new AesEncryptionService(Base64.getEncoder().encodeToString(new byte[32]));
        runner = new RestAssuredTestRunner(new TargetAuthHeaderResolver(), aesEncryptionService, new TestCasePathValidator());
        project = Project.builder().targetBaseUrl("http://localhost:" + server.getAddress().getPort()).build();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void run_substitutesPathTokenAndReturnsRealStatusAndBody() {
        server.start();
        Endpoint endpoint = Endpoint.builder().method("GET").path("/pet/{petId}").build();
        TestCase testCase = TestCase.builder().endpoint(endpoint).resolvedPath("/pet/{{petId}}").expectedStatus(200).build();

        RestAssuredTestRunner.RunResult result = runner.run(project, testCase, Map.of("petId", "1"));

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.responseBody()).isEqualTo("{\"id\":1}");
        assertThat(handler.method).isEqualTo("GET");
        assertThat(handler.path).isEqualTo("/pet/1");
    }

    @Test
    void run_bearerAuthConfigured_sendsAuthorizationHeaderDecrypted() {
        server.start();
        AesEncryptionService aesEncryptionService =
                new AesEncryptionService(Base64.getEncoder().encodeToString(new byte[32]));
        runner = new RestAssuredTestRunner(new TargetAuthHeaderResolver(), aesEncryptionService, new TestCasePathValidator());
        project = Project.builder()
                .targetBaseUrl("http://localhost:" + server.getAddress().getPort())
                .targetAuthType(TargetAuthType.BEARER_TOKEN)
                .targetAuthValueEncrypted(aesEncryptionService.encrypt("secret-token"))
                .build();
        Endpoint endpoint = Endpoint.builder().method("GET").path("/pets").build();
        TestCase testCase = TestCase.builder().endpoint(endpoint).resolvedPath("/pets").expectedStatus(200).build();

        runner.run(project, testCase, Map.of());

        assertThat(handler.headers.getFirst("Authorization")).isEqualTo("Bearer secret-token");
    }

    @Test
    void run_authOverrideNone_sendsNoAuthorizationHeaderEvenIfProjectHasAuthConfigured() {
        server.start();
        AesEncryptionService aesEncryptionService =
                new AesEncryptionService(Base64.getEncoder().encodeToString(new byte[32]));
        runner = new RestAssuredTestRunner(new TargetAuthHeaderResolver(), aesEncryptionService, new TestCasePathValidator());
        project = Project.builder()
                .targetBaseUrl("http://localhost:" + server.getAddress().getPort())
                .targetAuthType(TargetAuthType.BEARER_TOKEN)
                .targetAuthValueEncrypted(aesEncryptionService.encrypt("secret-token"))
                .build();
        Endpoint endpoint = Endpoint.builder().method("GET").path("/pets").build();
        TestCase testCase = TestCase.builder().endpoint(endpoint).resolvedPath("/pets").expectedStatus(401)
                .authOverride(TestCaseAuthOverride.NONE).build();

        runner.run(project, testCase, Map.of());

        assertThat(handler.headers.getFirst("Authorization")).isNull();
    }

    @Test
    void run_authOverrideInvalid_sendsWrongAuthorizationHeaderNotRealSecret() {
        server.start();
        AesEncryptionService aesEncryptionService =
                new AesEncryptionService(Base64.getEncoder().encodeToString(new byte[32]));
        runner = new RestAssuredTestRunner(new TargetAuthHeaderResolver(), aesEncryptionService, new TestCasePathValidator());
        project = Project.builder()
                .targetBaseUrl("http://localhost:" + server.getAddress().getPort())
                .targetAuthType(TargetAuthType.BEARER_TOKEN)
                .targetAuthValueEncrypted(aesEncryptionService.encrypt("secret-token"))
                .build();
        Endpoint endpoint = Endpoint.builder().method("GET").path("/pets").build();
        TestCase testCase = TestCase.builder().endpoint(endpoint).resolvedPath("/pets").expectedStatus(401)
                .authOverride(TestCaseAuthOverride.INVALID).build();

        runner.run(project, testCase, Map.of());

        String sentHeader = handler.headers.getFirst("Authorization");
        assertThat(sentHeader).isNotNull();
        assertThat(sentHeader).doesNotContain("secret-token");
        assertThat(sentHeader).startsWith("Bearer ");
    }

    @Test
    void run_substitutesPlaceholderInRequestBodyAndHeaders() {
        server.start();
        Endpoint endpoint = Endpoint.builder().method("POST").path("/pet").build();
        TestCase testCase = TestCase.builder().endpoint(endpoint).resolvedPath("/pet")
                .requestBody("{\"ownerId\":\"{{ownerId}}\"}")
                .requestHeaders("{\"X-Owner\":\"{{ownerId}}\"}")
                .expectedStatus(200).build();

        runner.run(project, testCase, Map.of("ownerId", "42"));

        assertThat(handler.body).isEqualTo("{\"ownerId\":\"42\"}");
        assertThat(handler.headers.getFirst("X-Owner")).isEqualTo("42");
    }

    @Test
    void run_patchMethodWithTrailingStaticSegmentAndQueryParams_sendsRealPatchVerbToCorrectPath() {
        server.start();
        Endpoint endpoint = Endpoint.builder().method("PATCH").path("/pet/{petId}/stock").build();
        TestCase testCase = TestCase.builder().endpoint(endpoint)
                .resolvedPath("/pet/{{petId}}/stock?quantity={{quantity}}&operation={{operation}}")
                .expectedStatus(200).build();

        RestAssuredTestRunner.RunResult result = runner.run(project, testCase,
                Map.of("petId", "1", "quantity", "10", "operation", "increase"));

        assertThat(handler.method).isEqualTo("PATCH");
        assertThat(handler.path).isEqualTo("/pet/1/stock");
        assertThat(result.statusCode()).isEqualTo(200);
    }

    @Test
    void run_targetReturnsErrorStatus_stillReturnsRealStatusNotException() {
        handler.statusCode = 404;
        handler.responseBody = "not found";
        server.start();
        Endpoint endpoint = Endpoint.builder().method("GET").path("/pet/{petId}").build();
        TestCase testCase = TestCase.builder().endpoint(endpoint).resolvedPath("/pet/{{petId}}").expectedStatus(200).build();

        RestAssuredTestRunner.RunResult result = runner.run(project, testCase, Map.of("petId", "999"));

        assertThat(result.statusCode()).isEqualTo(404);
        assertThat(result.responseBody()).isEqualTo("not found");
    }

    private static class CapturingHandler implements HttpHandler {
        volatile String method;
        volatile String path;
        volatile com.sun.net.httpserver.Headers headers;
        volatile String body;
        volatile int statusCode;
        volatile String responseBody;

        CapturingHandler(int statusCode, String responseBody) {
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            method = exchange.getRequestMethod();
            path = exchange.getRequestURI().getPath();
            headers = exchange.getRequestHeaders();
            body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }
}
