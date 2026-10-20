package com.aiapitesting.backend.security;

import com.aiapitesting.backend.entity.TargetAuthType;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Map TargetAuthType sang header HTTP thật sự gửi cho target API - dùng chung giữa
 * EndpointImportService (tải tài liệu OpenAPI bị chặn login) và engine thực thi test (Module 6).
 * Hỗ trợ: Bearer, X-API-Key, HTTP Basic ("tên:mật khẩu") và header tuỳ ý ("Tên-Header: giá trị").
 */
@Component
public class TargetAuthHeaderResolver {

    /** @param query true = gửi dưới dạng tham số query string (QUERY_PARAM) thay vì header HTTP. */
    public record AuthHeader(String name, String value, boolean query) {
        public AuthHeader(String name, String value) {
            this(name, value, false);
        }
    }

    /** Tên tham số query hợp lệ: chữ, số, _ . - (không cho ký tự điều khiển/phân tách URL). */
    private static final Pattern QUERY_NAME = Pattern.compile("[A-Za-z0-9_.\\-]{1,100}");

    /** Tên header HTTP hợp lệ (RFC 7230 token, rút gọn) - chặn ký tự xuống dòng/dấu cách để không chèn header. */
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,100}");

    public AuthHeader resolve(TargetAuthType authType, String rawValue) {
        if (authType == null || authType == TargetAuthType.NONE) {
            return null;
        }
        return switch (authType) {
            case BEARER_TOKEN -> new AuthHeader("Authorization", "Bearer " + rawValue);
            case API_KEY -> new AuthHeader("X-API-Key", rawValue);
            case BASIC_AUTH -> new AuthHeader("Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(rawValue.getBytes(StandardCharsets.UTF_8)));
            case CUSTOM_HEADER -> parseCustomHeader(rawValue);
            case QUERY_PARAM -> parseQueryParam(rawValue);
            case NONE -> null;
        };
    }

    /** Giá trị "sai" cùng dạng với giá trị thật (dùng cho test Security 'token sai'): giữ nguyên tên header của CUSTOM_HEADER. */
    public AuthHeader resolveInvalid(TargetAuthType authType, String realValueOrNull, String invalidValue) {
        if (authType == TargetAuthType.CUSTOM_HEADER) {
            AuthHeader real = realValueOrNull == null ? null : parseCustomHeader(realValueOrNull);
            return new AuthHeader(real == null ? "X-Auth-Token" : real.name(), invalidValue);
        }
        if (authType == TargetAuthType.QUERY_PARAM) {
            AuthHeader real = realValueOrNull == null ? null : parseQueryParam(realValueOrNull);
            return new AuthHeader(real == null ? "api_key" : real.name(), invalidValue, true);
        }
        if (authType == TargetAuthType.BASIC_AUTH) {
            return resolve(authType, "invalid-user:" + invalidValue);
        }
        return resolve(authType, invalidValue);
    }

    /** Giá trị cho CUSTOM_HEADER hợp lệ khi có dạng "Tên-Header: giá trị" với tên là token HTTP hợp lệ. */
    public static boolean isValidCustomHeaderValue(String raw) {
        return parseCustomHeader(raw) != null;
    }

    /** Giá trị cho QUERY_PARAM hợp lệ khi có dạng "tên=giá trị". */
    public static boolean isValidQueryParamValue(String raw) {
        return parseQueryParam(raw) != null;
    }

    private static AuthHeader parseQueryParam(String raw) {
        if (raw == null) {
            return null;
        }
        int eq = raw.indexOf('=');
        if (eq <= 0) {
            return null;
        }
        String name = raw.substring(0, eq).trim();
        String value = raw.substring(eq + 1).trim();
        if (!QUERY_NAME.matcher(name).matches() || value.isEmpty() || value.contains("\r") || value.contains("\n")) {
            return null;
        }
        return new AuthHeader(name, value, true);
    }

    private static AuthHeader parseCustomHeader(String raw) {
        if (raw == null) {
            return null;
        }
        int colon = raw.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String name = raw.substring(0, colon).trim();
        String value = raw.substring(colon + 1).trim();
        if (!HEADER_NAME.matcher(name).matches() || value.isEmpty() || value.contains("\r") || value.contains("\n")) {
            return null;
        }
        return new AuthHeader(name, value);
    }
}
