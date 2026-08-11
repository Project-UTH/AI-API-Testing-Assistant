package com.aiapitesting.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validate cú pháp resolvedPath/pathParamFallbacks của TestCase - dùng chung cho cả đường AI sinh
 * (TestCaseGenerationService) lẫn CRUD thủ công (TestCaseService), thuần cú pháp, không cần biết
 * test case thuộc nhóm Positive/Negative/Boundary (hệ thống không lưu nhãn nhóm này ở đâu cả).
 */
@Component
public class TestCasePathValidator {

    private static final Pattern DOUBLE_BRACE_TOKEN = Pattern.compile("\\{\\{(\\w+)}}");
    private static final Pattern SINGLE_BRACE_TOKEN = Pattern.compile("(?<!\\{)\\{(\\w+)}(?!})");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param exceptionFactory tạo exception đúng loại theo ngữ cảnh gọi (AiGenerationFailedException
     *                         cho đường AI, InvalidRequestException cho đường CRUD thủ công)
     */
    public void validate(String resolvedPath, String pathParamFallbacksJson,
                          Function<String, ? extends RuntimeException> exceptionFactory) {
        if (resolvedPath == null || resolvedPath.isBlank()) {
            return;
        }
        if (SINGLE_BRACE_TOKEN.matcher(resolvedPath).find()) {
            throw exceptionFactory.apply(
                    "resolvedPath còn sót tham số OpenAPI gốc chưa được thay (dạng {tenThamSo}) - phải dùng {{tenThamSo}} hoặc giá trị cụ thể");
        }

        Set<String> placeholders = extractPlaceholders(resolvedPath);
        if (placeholders.isEmpty()) {
            return;
        }
        Map<String, String> fallbacks = parseFallbacks(pathParamFallbacksJson, exceptionFactory);
        for (String paramName : placeholders) {
            if (!fallbacks.containsKey(paramName)) {
                throw exceptionFactory.apply(
                        "Thiếu giá trị dự phòng (pathParamFallbacks) cho tham số " + paramName);
            }
        }
    }

    /** Trích danh sách tên tham số từ các token {{tenThamSo}} trong 1 chuỗi - dùng lại ở engine thực thi (Module 6/7) và gợi ý liên kết (Module 7). */
    public Set<String> extractPlaceholders(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null) {
            return result;
        }
        Matcher matcher = DOUBLE_BRACE_TOKEN.matcher(text);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    /** Thay mọi token {{tenThamSo}} trong text bằng giá trị thật tương ứng trong values. */
    public String substitute(String text, Map<String, String> values) {
        if (text == null) {
            return null;
        }
        Matcher matcher = DOUBLE_BRACE_TOKEN.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private Map<String, String> parseFallbacks(String json, Function<String, ? extends RuntimeException> exceptionFactory) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            throw exceptionFactory.apply("pathParamFallbacks không phải JSON hợp lệ");
        }
    }
}
