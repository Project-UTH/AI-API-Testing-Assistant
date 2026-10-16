package com.aiapitesting.backend.service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Module 15h (sửa lỗi có sẵn) - đọc giá trị tham số path/query mà 1 test case THẬT SỰ gửi đi.
 *
 * Prompt dặn AI: khi test sai/biên của chính 1 tham số thì ghi thẳng giá trị vào resolvedPath (vd
 * "/api/products?minPrice=-1", pathParamFallbacks để trống). Trước đây Tier 1 và Coverage Matrix
 * chỉ đọc pathParamFallbacks nên không thấy giá trị này - Tier 1 gắn "Cần xem lại" oan, Coverage
 * báo thiếu biên dù đã có case. Engine thực thi chỉ thay token {{ten}}, nên giá trị literal trong
 * URL luôn là giá trị thật được gửi - ưu tiên nó hơn pathParamFallbacks khi cả 2 cùng có.
 */
public final class TestCaseParamValues {

    private static final Pattern PATH_TEMPLATE_PARAM = Pattern.compile("^\\{(\\w+)}$");
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\{\\{(\\w+)}}$");

    private TestCaseParamValues() {
    }

    /**
     * Bắt đầu từ pathParamFallbacks (giữ đúng hành vi cũ cho case chỉ có giá trị ở đó), rồi ghi đè
     * bằng giá trị literal tìm được trong resolvedPath. Vị trí là token {{ten}} thì giữ giá trị
     * fallback của token đó. Segment path chỉ đối chiếu được khi số segment khớp endpointPath.
     */
    public static Map<String, String> resolve(String endpointPath, String resolvedPath, Map<String, String> fallbacks) {
        Map<String, String> values = new LinkedHashMap<>(fallbacks == null ? Map.of() : fallbacks);
        if (resolvedPath == null || resolvedPath.isBlank()) {
            return values;
        }
        int queryStart = resolvedPath.indexOf('?');
        String pathPart = queryStart < 0 ? resolvedPath : resolvedPath.substring(0, queryStart);
        String query = queryStart < 0 ? null : resolvedPath.substring(queryStart + 1);

        if (endpointPath != null) {
            String[] templateSegments = endpointPath.split("/", -1);
            String[] actualSegments = pathPart.split("/", -1);
            if (templateSegments.length == actualSegments.length) {
                for (int i = 0; i < templateSegments.length; i++) {
                    Matcher m = PATH_TEMPLATE_PARAM.matcher(templateSegments[i]);
                    if (m.matches()) {
                        applyRaw(values, fallbacks, m.group(1), decode(actualSegments[i]));
                    }
                }
            }
        }
        if (query != null && !query.isBlank()) {
            for (String pair : query.split("&")) {
                if (pair.isBlank()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String name = decode(eq < 0 ? pair : pair.substring(0, eq));
                String raw = eq < 0 ? "" : decode(pair.substring(eq + 1));
                applyRaw(values, fallbacks, name, raw);
            }
        }
        return values;
    }

    private static void applyRaw(Map<String, String> values, Map<String, String> fallbacks, String name, String raw) {
        Matcher placeholder = PLACEHOLDER.matcher(raw);
        if (placeholder.matches()) {
            // Token có thể mang tên khác tên tham số - lấy đúng fallback của token đó.
            String fallback = fallbacks == null ? null : fallbacks.get(placeholder.group(1));
            if (fallback != null) {
                values.put(name, fallback);
            }
            return;
        }
        if (raw.contains("{{")) {
            return; // ghép token với chữ thường - không suy được giá trị thật, giữ nguyên fallback
        }
        values.put(name, raw);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
