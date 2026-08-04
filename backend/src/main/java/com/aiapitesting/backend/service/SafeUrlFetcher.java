package com.aiapitesting.backend.service;

import com.aiapitesting.backend.exception.SwaggerParseException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

@Component
public class SafeUrlFetcher {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;

    public String fetch(String rawUrl) {
        URI uri = parseAndValidate(rawUrl);

        try {
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            // Không theo redirect: chặn kiểu SSRF dùng redirect để né kiểm tra IP ở bước validate.
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");

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
        if (host == null || host.isBlank() || host.equalsIgnoreCase("localhost")) {
            throw new SwaggerParseException("URL trỏ tới địa chỉ nội bộ không được phép");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            throw new SwaggerParseException("Không thể phân giải tên miền của URL");
        }

        for (InetAddress address : addresses) {
            if (isDisallowed(address)) {
                throw new SwaggerParseException("URL trỏ tới địa chỉ nội bộ không được phép");
            }
        }

        return uri;
    }

    private boolean isDisallowed(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress();
    }
}
