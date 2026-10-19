package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.entity.AuthProfile;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TargetAuthType;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseAuthOverride;
import com.aiapitesting.backend.repository.AuthProfileRepository;
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
import java.util.Optional;
import java.util.UUID;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Test tích hợp thật với 1 HTTP server cục bộ (com.sun.net.httpserver.HttpServer, có sẵn trong JDK,
 * không thêm dependency) - kiểm header/status/body thật thay vì mock, không phụ thuộc mạng ngoài.
 */
class RestAssuredTestRunnerTest {

    private HttpServer server;
    private CapturingHandler handler;
    private RestAssuredTestRunner runner;
    private Project project;
    private final AuthProfileRepository authProfileRepository = org.mockito.Mockito.mock(AuthProfileRepository.class);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        handler = new CapturingHandler(200, "{\"id\":1}");
        server.createContext("/", handler);

        AesEncryptionService aesEncryptionService =
                new AesEncryptionService(Base64.getEncoder().encodeToString(new byte[32]));
        runner = new RestAssuredTestRunner(new TargetAuthHeaderResolver(), aesEncryptionService, new TestCasePathValidator(), authProfileRepository);
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
        runner = new RestAssuredTestRunner(new TargetAuthHeaderResolver(), aesEncryptionService, new TestCasePathValidator(), authProfileRepository);
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
        runner = new RestAssuredTestRunner(new TargetAuthHeaderResolver(), aesEncryptionService, new TestCasePathValidator(), authProfileRepository);
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
        runner = new RestAssuredTestRunner(new TargetAuthHeaderResolver(), aesEncryptionService, new TestCasePathValidator(), authProfileRepository);
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

    // ---- Module 17 (3.1) - profile xác thực (vai trò) ----

    private Project projectWithDefaultToken(AesEncryptionService aes) {
        UUID projectId = UUID.randomUUID();
        return Project.builder().id(projectId)
                .targetBaseUrl("http://localhost:" + server.getAddress().getPort())
                .targetAuthType(TargetAuthType.BEARER_TOKEN)
                .targetAuthValueEncrypted(aes.encrypt("project-default-token"))
                .build();
    }

    private AesEncryptionService aes() {
        return new AesEncryptionService(Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Test
    void run_testCaseWithAuthProfile_sendsThatProfilesTokenNotTheProjectDefault() {
        server.start();
        AesEncryptionService aes = aes();
        runner = new RestAssuredTestRunner(new TargetAuthHeaderResolver(), aes, new TestCasePathValidator(), authProfileRepository);
        project = projectWithDefaultToken(aes);
        UUID profileId = UUID.randomUUID();
        when(authProfileRepository.findByIdAndProjectId(profileId, project.getId())).thenReturn(Optional.of(AuthProfile.builder()
                .id(profileId).roleKey("CANDIDATE").authType(TargetAuthType.BEARER_TOKEN)
                .authValueEncrypted(aes.encrypt("candidate-token")).build()));
        Endpoint endpoint = Endpoint.builder().method("GET").path("/pets").build();
        TestCase testCase = TestCase.builder().endpoint(endpoint).resolvedPath("/pets").expectedStatus(200).authProfileId(profileId).build();

        runner.run(project, testCase, Map.of());

        assertThat(handler.headers.getFirst("Authorization")).isEqualTo("Bearer candidate-token");
    }

    @Test
    void run_authOverrideNoneBeatsProfile_noAuthorizationHeader() {
        server.start();
        AesEncryptionService aes = aes();
        runner = new RestAssuredTestRunner(new TargetAuthHeaderResolver(), aes, new TestCasePathValidator(), authProfileRepository);
        project = projectWithDefaultToken(aes);
        UUID profileId = UUID.randomUUID();
        when(authProfileRepository.findByIdAndProjectId(profileId, project.getId())).thenReturn(Optional.of(AuthProfile.builder()
                .id(profileId).roleKey("HR").authType(TargetAuthType.BEARER_TOKEN).authValueEncrypted(aes.encrypt("hr-token")).build()));
        Endpoint endpoint = Endpoint.builder().method("GET").path("/pets").build();
        TestCase testCase = TestCase.builder().endpoint(endpoint).resolvedPath("/pets").expectedStatus(401)
                .authProfileId(profileId).authOverride(TestCaseAuthOverride.NONE).build();

        runner.run(project, testCase, Map.of());

        assertThat(handler.headers.getFirst("Authorization")).isNull();
    }

    @Test
    void run_authOverrideInvalidWithProfile_neverSendsTheRealProfileToken() {
        server.start();
        AesEncryptionService aes = aes();
        runner = new RestAssuredTestRunner(new TargetAuthHeaderResolver(), aes, new TestCasePathValidator(), authProfileRepository);
        project = projectWithDefaultToken(aes);
        UUID profileId = UUID.randomUUID();
        when(authProfileRepository.findByIdAndProjectId(profileId, project.getId())).thenReturn(Optional.of(AuthProfile.builder()
                .id(profileId).roleKey("HR").authType(TargetAuthType.BEARER_TOKEN).authValueEncrypted(aes.encrypt("hr-token")).build()));
        Endpoint endpoint = Endpoint.builder().method("GET").path("/pets").build();
        TestCase testCase = TestCase.builder().endpoint(endpoint).resolvedPath("/pets").expectedStatus(401)
                .authProfileId(profileId).authOverride(TestCaseAuthOverride.INVALID).build();

        runner.run(project, testCase, Map.of());

        assertThat(handler.headers.getFirst("Authorization")).startsWith("Bearer ").doesNotContain("hr-token");
    }

    @Test
    void run_profileNoneType_sendsNoAuthorizationHeaderEvenWhenProjectHasDefaultToken() {
        // Vai trò "ẩn danh": gọi không token dù project có token mặc định.
        server.start();
        AesEncryptionService aes = aes();
        runner = new RestAssuredTestRunner(new TargetAuthHeaderResolver(), aes, new TestCasePathValidator(), authProfileRepository);
        project = projectWithDefaultToken(aes);
        UUID profileId = UUID.randomUUID();
        when(authProfileRepository.findByIdAndProjectId(profileId, project.getId())).thenReturn(Optional.of(AuthProfile.builder()
                .id(profileId).roleKey("ANONYMOUS").authType(TargetAuthType.NONE).build()));
        Endpoint endpoint = Endpoint.builder().method("GET").path("/pets").build();
        TestCase testCase = TestCase.builder().endpoint(endpoint).resolvedPath("/pets").expectedStatus(401).authProfileId(profileId).build();

        runner.run(project, testCase, Map.of());

        assertThat(handler.headers.getFirst("Authorization")).isNull();
    }

    @Test
    void run_profileMissingOrFromAnotherProject_failsInsteadOfFallingBackToDefaultToken() {
        server.start();
        AesEncryptionService aes = aes();
        runner = new RestAssuredTestRunner(new TargetAuthHeaderResolver(), aes, new TestCasePathValidator(), authProfileRepository);
        project = projectWithDefaultToken(aes);
        UUID profileId = UUID.randomUUID();
        when(authProfileRepository.findByIdAndProjectId(profileId, project.getId())).thenReturn(Optional.empty());
        Endpoint endpoint = Endpoint.builder().method("GET").path("/pets").build();
        TestCase testCase = TestCase.builder().endpoint(endpoint).resolvedPath("/pets").expectedStatus(200).authProfileId(profileId).build();

        assertThatThrownBy(() -> runner.run(project, testCase, Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Profile xác thực");
        assertThat(handler.headers).isNull(); // không có request nào tới target
    }

    // ---- Tổng quát hoá: không chỉ JSON, không chỉ Bearer ----

    private TestCase writeCase(String contentTypeFromSpec, String body, String headersJson) {
        Endpoint endpoint = Endpoint.builder().method("POST").path("/submit").requestContentType(contentTypeFromSpec).build();
        return TestCase.builder().endpoint(endpoint).resolvedPath("/submit").requestBody(body).requestHeaders(headersJson).expectedStatus(200).build();
    }

    @Test
    void run_specSaysFormUrlEncoded_sendsFormFieldsInsteadOfJson() {
        server.start();

        runner.run(project, writeCase("application/x-www-form-urlencoded", "{\"name\":\"An Binh\",\"qty\":3,\"tags\":[1,2]}", null), Map.of());

        assertThat(handler.headers.getFirst("Content-Type")).startsWith("application/x-www-form-urlencoded");
        assertThat(handler.body).contains("name=An%20Binh").contains("qty=3").contains("tags=");
        assertThat(handler.body).doesNotContain("{");
    }

    @Test
    void run_specSaysMultipart_sendsTextPartsAsMultipartFormData() {
        server.start();

        runner.run(project, writeCase("multipart/form-data", "{\"title\":\"hello\"}", null), Map.of());

        assertThat(handler.headers.getFirst("Content-Type")).startsWith("multipart/form-data");
        assertThat(handler.body).contains("name=\"title\"").contains("hello");
    }

    @Test
    void run_specSaysXmlOrText_sendsBodyVerbatimWithThatContentType() {
        server.start();

        runner.run(project, writeCase("application/xml", "<order><id>7</id></order>", null), Map.of());

        assertThat(handler.headers.getFirst("Content-Type")).startsWith("application/xml");
        assertThat(handler.body).isEqualTo("<order><id>7</id></order>");
    }

    @Test
    void run_contentTypeHeaderOfTheTestCaseBeatsTheSpec_andIsSentExactlyOnce() {
        server.start();

        runner.run(project, writeCase("application/json", "a=1&b=2", "{\"Content-Type\":\"application/x-www-form-urlencoded\"}"), Map.of());

        assertThat(handler.headers.get("Content-Type")).hasSize(1);
        assertThat(handler.headers.getFirst("Content-Type")).startsWith("application/x-www-form-urlencoded");
        assertThat(handler.body).isEqualTo("a=1&b=2");
    }

    @Test
    void run_noSpecContentType_defaultsToJsonAsBefore() {
        server.start();

        runner.run(project, writeCase(null, "{\"a\":1}", null), Map.of());

        assertThat(handler.headers.getFirst("Content-Type")).startsWith("application/json");
        assertThat(handler.body).isEqualTo("{\"a\":1}");
    }

    @Test
    void run_basicAndCustomHeaderAuth_sendTheRightHeaders_andInvalidKeepsTheHeaderName() {
        server.start();
        AesEncryptionService aes = new AesEncryptionService(Base64.getEncoder().encodeToString(new byte[32]));
        runner = new RestAssuredTestRunner(new TargetAuthHeaderResolver(), aes, new TestCasePathValidator(), authProfileRepository);
        Endpoint endpoint = Endpoint.builder().method("GET").path("/pets").build();
        TestCase real = TestCase.builder().endpoint(endpoint).resolvedPath("/pets").expectedStatus(200).build();

        project = Project.builder().targetBaseUrl("http://localhost:" + server.getAddress().getPort())
                .targetAuthType(TargetAuthType.BASIC_AUTH).targetAuthValueEncrypted(aes.encrypt("admin:s3cret")).build();
        runner.run(project, real, Map.of());
        assertThat(handler.headers.getFirst("Authorization")).isEqualTo("Basic YWRtaW46czNjcmV0");

        project = Project.builder().targetBaseUrl("http://localhost:" + server.getAddress().getPort())
                .targetAuthType(TargetAuthType.CUSTOM_HEADER).targetAuthValueEncrypted(aes.encrypt("X-Auth-Token: tok-123")).build();
        runner.run(project, real, Map.of());
        assertThat(handler.headers.getFirst("X-Auth-Token")).isEqualTo("tok-123");
        assertThat(handler.headers.getFirst("Authorization")).isNull();

        TestCase invalid = TestCase.builder().endpoint(endpoint).resolvedPath("/pets").expectedStatus(401)
                .authOverride(com.aiapitesting.backend.entity.TestCaseAuthOverride.INVALID).build();
        runner.run(project, invalid, Map.of());
        assertThat(handler.headers.getFirst("X-Auth-Token")).isNotEqualTo("tok-123").isNotNull();
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
