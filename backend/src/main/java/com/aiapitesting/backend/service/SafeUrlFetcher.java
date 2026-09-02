package com.aiapitesting.backend.service;

import com.aiapitesting.backend.exception.SwaggerParseException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

@Component
public class SafeUrlFetcher {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;

    public String fetch(String rawUrl) {
        return fetch(rawUrl, null, null);
    }

    public String fetch(String rawUrl, String authHeaderName, String authHeaderValue) {
        URI uri = parseAndValidate(rawUrl);

        try {
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            // Không theo redirect: tránh header xác thực bị gửi sang host khác ngoài ý muốn.
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            if (authHeaderName != null && authHeaderValue != null) {
                connection.setRequestProperty(authHeaderName, authHeaderValue);
            }

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new SwaggerParseException("Không thể tải nội dung OpenAPI từ URL đã cho (HTTP " + status + ")");
            }

            try (var input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new SwaggerParseException("Nội dung OpenAPI tại URL vượt quá giới hạn cho phép");
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (SwaggerParseException e) {
            throw e;
        } catch (IOException e) {
            throw new SwaggerParseException("Không thể kết nối tới URL đã cho");
        }
    }

    private URI parseAndValidate(String rawUrl) {
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (Exception e) {
            throw new SwaggerParseException("URL không hợp lệ");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new SwaggerParseException("Chỉ hỗ trợ URL http hoặc https");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SwaggerParseException("URL không hợp lệ");
        }

        return uri;
    }
}
