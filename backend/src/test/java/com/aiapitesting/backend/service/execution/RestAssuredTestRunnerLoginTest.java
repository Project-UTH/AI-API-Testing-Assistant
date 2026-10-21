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
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Module 17 (G5, P1-4) - profile chế độ "Đăng nhập": tự lấy token, cache, đăng nhập lại 1 lần khi 401, không chạm token ở case Security. */
class RestAssuredTestRunnerLoginTest {

    private HttpServer server;
    private final AtomicInteger loginCalls = new AtomicInteger();
    private volatile int loginStatus = 200;
    private volatile String loginResponse = "{\"data\":{\"accessToken\":\"tok-1\"}}";
    private volatile String lastLoginBody;
    private final List<String> authHeadersSeen = new ArrayList<>();
    /** Token mà /pets chấp nhận; sai thì trả 401. */
    private volatile String acceptedToken = "tok-1";

    private final AuthProfileRepository authProfileRepository = mock(AuthProfileRepository.class);
    private final AesEncryptionService aes = new AesEncryptionService(Base64.getEncoder().encodeToString(new byte[32]));
    private RestAssuredTestRunner runner;
    private Project project;
    private AuthProfile profile;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/auth/login", exchange -> {
            loginCalls.incrementAndGet();
            lastLoginBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] out = loginResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(loginStatus, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.createContext("/pets", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            synchronized (authHeadersSeen) {
                authHeadersSeen.add(auth);
            }
            int status = ("Bearer " + acceptedToken).equals(auth) ? 200 : 401;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        project = Project.builder().id(UUID.randomUUID()).targetBaseUrl("http://localhost:" + server.getAddress().getPort()).build();
        runner = new RestAssuredTestRunner(new TargetAuthHeaderResolver(), aes, new TestCasePathValidator(), authProfileRepository);
        profile = AuthProfile.builder().id(UUID.randomUUID()).roleKey("HR").authType(TargetAuthType.BEARER_TOKEN)
                .loginPath("/auth/login").tokenJsonPath("data.accessToken")
                .loginBodyEncrypted(aes.encrypt("{\"email\":\"hr@x.com\",\"password\":\"Secret123!\"}")).build();
        when(authProfileRepository.findByIdAndProjectId(profile.getId(), project.getId())).thenReturn(Optional.of(profile));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private TestCase testCase(TestCaseAuthOverride override) {
        Endpoint endpoint = Endpoint.builder().method("GET").path("/pets").build();
        return TestCase.builder().endpoint(endpoint).resolvedPath("/pets").expectedStatus(200)
                .authProfileId(profile.getId()).authOverride(override).build();
    }

    @Test
    void logsInOnceAndCachesTokenAcrossTests() {
        assertThat(runner.run(project, testCase(TestCaseAuthOverride.DEFAULT), Map.of()).statusCode()).isEqualTo(200);
        assertThat(runner.run(project, testCase(TestCaseAuthOverride.DEFAULT), Map.of()).statusCode()).isEqualTo(200);

        assertThat(loginCalls.get()).isEqualTo(1);
        assertThat(lastLoginBody).contains("hr@x.com");
        assertThat(authHeadersSeen).containsOnly("Bearer tok-1");
    }

    @Test
    void on401_logsInAgainExactlyOnceAndRetries() {
        runner.run(project, testCase(TestCaseAuthOverride.DEFAULT), Map.of());
        // Server đổi token: lần gọi kế tiếp bị 401 -> đăng nhập lại, nhận token mới, thử lại thành công.
        acceptedToken = "tok-2";
        loginResponse = "{\"data\":{\"accessToken\":\"tok-2\"}}";

        assertThat(runner.run(project, testCase(TestCaseAuthOverride.DEFAULT), Map.of()).statusCode()).isEqualTo(200);
        assertThat(loginCalls.get()).isEqualTo(2);

        // Token vẫn sai sau khi đăng nhập lại: chỉ thử lại 1 lần rồi trả 401 thật (không lặp vô hạn).
        acceptedToken = "never";
        int loginsBefore = loginCalls.get();
        assertThat(runner.run(project, testCase(TestCaseAuthOverride.DEFAULT), Map.of()).statusCode()).isEqualTo(401);
        assertThat(loginCalls.get() - loginsBefore).isEqualTo(1);
    }

    @Test
    void securityOverrides_neverLogInNorRetry() {
        assertThat(runner.run(project, testCase(TestCaseAuthOverride.NONE), Map.of()).statusCode()).isEqualTo(401);
        assertThat(runner.run(project, testCase(TestCaseAuthOverride.INVALID), Map.of()).statusCode()).isEqualTo(401);

        assertThat(loginCalls.get()).isZero();
        assertThat(authHeadersSeen.get(0)).isNull();
    }

    @Test
    void loginRejected_throwsConfigErrorWithoutLeakingCredentialsOrBody() {
        loginStatus = 401;
        loginResponse = "{\"token\":\"leaky-secret\"}";

        assertThatThrownBy(() -> runner.run(project, testCase(TestCaseAuthOverride.DEFAULT), Map.of()))
                .isInstanceOfSatisfying(AuthLoginException.class, e -> {
                    assertThat(e.isConfigProblem()).isTrue();
                    assertThat(e.getMessage()).contains("401").doesNotContain("Secret123").doesNotContain("hr@x.com").doesNotContain("leaky-secret");
                });
    }

    @Test
    void expectedDenial_isNotRetried() {
        runner.run(project, testCase(TestCaseAuthOverride.DEFAULT), Map.of());
        acceptedToken = "other";
        int before = loginCalls.get();
        TestCase denial = testCase(TestCaseAuthOverride.DEFAULT);
        denial.setExpectedStatus(403);

        assertThat(runner.run(project, denial, Map.of()).statusCode()).isEqualTo(401);
        assertThat(loginCalls.get()).isEqualTo(before);
    }

    @Test
    void tokenPathSupportsArrayIndex() {
        profile.setTokenJsonPath("tokens[0].value");
        loginResponse = "{\"tokens\":[{\"value\":\"tok-1\"}]}";

        assertThat(runner.run(project, testCase(TestCaseAuthOverride.DEFAULT), Map.of()).statusCode()).isEqualTo(200);
    }

    @Test
    void tokenMissingAtJsonPath_throwsConfigError() {
        loginResponse = "{\"other\":1}";

        assertThatThrownBy(() -> runner.run(project, testCase(TestCaseAuthOverride.DEFAULT), Map.of()))
                .isInstanceOfSatisfying(AuthLoginException.class, e -> assertThat(e.isConfigProblem()).isTrue());
    }

    @Test
    void tokenWithNewline_isRejectedToPreventHeaderInjection() {
        loginResponse = "{\"data\":{\"accessToken\":\"a\\r\\nX-Evil: 1\"}}";

        assertThatThrownBy(() -> runner.run(project, testCase(TestCaseAuthOverride.DEFAULT), Map.of()))
                .isInstanceOf(AuthLoginException.class);
    }
}
