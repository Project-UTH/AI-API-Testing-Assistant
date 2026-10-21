package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.entity.AuthProfile;
import com.aiapitesting.backend.entity.Project;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.response.Response;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Module 17 (G5, P1-4) - lấy token bằng cách gọi endpoint đăng nhập của target (profile chế độ "Đăng nhập"). Token chỉ nằm trong bộ nhớ
 * (cache ngắn hạn theo profile), không ghi DB, không log. Luôn gọi vào base URL của project (loginPath chỉ là đường dẫn tương đối) nên
 * không thể bị lái sang host khác.
 */
public class AuthTokenService {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final int TIMEOUT_MS = 10_000;

    private record Cached(String token, Instant fetchedAt, String stamp) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConcurrentHashMap<UUID, Cached> cache = new ConcurrentHashMap<>();

    /** Token còn hạn trong cache, hoặc đăng nhập mới. force bỏ qua cache (sau khi gặp 401). */
    public String token(Project project, AuthProfile profile, String loginBodyPlain, boolean force) {
        String stamp = project.getTargetBaseUrl() + "|" + profile.getLoginPath() + "|" + profile.getTokenJsonPath()
                + "|" + profile.getAuthUpdatedAt();
        Cached cached = cache.get(profile.getId());
        if (!force && cached != null && Objects.equals(cached.stamp(), stamp)
                && Duration.between(cached.fetchedAt(), Instant.now()).compareTo(TTL) < 0) {
            return cached.token();
        }
        String token = login(project, profile, loginBodyPlain);
        cache.put(profile.getId(), new Cached(token, Instant.now(), stamp));
        return token;
    }

    /** Thời điểm lấy token đang cache (null nếu chưa có) - runner so trước/sau lời gọi để biết token vừa lấy mới hay đến từ cache. */
    public Instant fetchedAt(UUID profileId) {
        Cached cached = cache.get(profileId);
        return cached == null ? null : cached.fetchedAt();
    }

    /** JSONPath đơn giản: a.b.c và a[0].b (có hoặc không có tiền tố $.) - KHÔNG dùng GPath vì nó thực thi biểu thức Groovy. */
    public static boolean isSafePath(String path) {
        return path != null && path.length() <= 200 && path.matches("^\\$?\\.?[A-Za-z0-9_-]+(\\[[0-9]{1,3}\\])*(\\.[A-Za-z0-9_-]+(\\[[0-9]{1,3}\\])*)*$");
    }

    public void evict(UUID profileId) {
        cache.remove(profileId);
    }

    private String login(Project project, AuthProfile profile, String loginBodyPlain) {
        if (loginBodyPlain == null || loginBodyPlain.isBlank() || profile.getLoginPath() == null || profile.getTokenJsonPath() == null) {
            throw new AuthLoginException("Đăng nhập tự động: profile thiếu cấu hình đăng nhập", true, null);
        }
        Response response;
        try {
            response = RestAssured.given()
                    .baseUri(project.getTargetBaseUrl())
                    .config(RestAssuredConfig.config().httpClient(HttpClientConfig.httpClientConfig()
                            .setParam("http.connection.timeout", TIMEOUT_MS)
                            .setParam("http.socket.timeout", TIMEOUT_MS)))
                    .contentType(ContentType.JSON)
                    .body(loginBodyPlain)
                    .post(profile.getLoginPath());
        } catch (RuntimeException e) {
            throw new AuthLoginException("Đăng nhập tự động: không gọi được endpoint đăng nhập", false, e);
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new AuthLoginException("Đăng nhập tự động thất bại: endpoint đăng nhập trả " + status
                    + " - kiểm tra đường dẫn đăng nhập và tài khoản của profile", true, null);
        }
        String token = extract(response.getBody().asString(), profile.getTokenJsonPath());
        if (token == null || token.isBlank() || token.length() > 8192 || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
            throw new AuthLoginException("Đăng nhập tự động: không lấy được token theo JSONPath '" + profile.getTokenJsonPath() + "'", true, null);
        }
        return token.trim();
    }

    private static String extract(String body, String jsonPath) {
        if (body == null || body.isBlank()) {
            return null;
        }
        if (!isSafePath(jsonPath)) {
            return null;
        }
        String path = jsonPath.startsWith("$.") ? jsonPath.substring(2) : jsonPath.startsWith("$") ? jsonPath.substring(1) : jsonPath;
        try {
            JsonNode node = MAPPER.readTree(body);
            for (String part : path.split("[.]")) {
                int open = part.indexOf('[');
                String key = open < 0 ? part : part.substring(0, open);
                node = node.path(key);
                while (open >= 0) {
                    int close = part.indexOf(']', open);
                    node = node.path(Integer.parseInt(part.substring(open + 1, close)));
                    open = part.indexOf('[', close);
                }
            }
            return node.isValueNode() && !node.isNull() ? node.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
