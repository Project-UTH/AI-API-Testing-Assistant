package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.TestCaseReliability;
import com.aiapitesting.backend.entity.TestCaseSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Module 15b (Tier 1) - đối chiếu TẤT ĐỊNH giữa giá trị test case AI vừa sinh với ràng buộc
 * required/enum/min-max khai báo trong {@code Endpoint.schema} - KHÔNG gọi AI. Case kỳ vọng thành
 * công (expectedStatus &lt; 400): có vi phạm → NEEDS_REVIEW. Case kỳ vọng lỗi nhóm Security KHÔNG bị
 * đối chiếu (payload injection cố ý sai định dạng là chủ đích). Case kỳ vọng lỗi KHÔNG thuộc Security
 * (Negative/Boundary-vượt-biên, mở rộng ở Module 15f): KHÔNG vi phạm gì cả → NEEDS_REVIEW (dấu hiệu
 * case có thể không khớp dữ liệu thật đã sinh). Phân biệt "lỗi AI thật" khỏi "false positive" ở mức
 * sâu hơn (ngữ nghĩa, vd case Security cố ý sai) là việc của Tier 2 (Module 15d).
 *
 * Dùng chung field constraint cho cả requestBody VÀ parameter (path/query/header) - khác
 * {@code EndpointCoverageService} (chỉ cần biết field nào CÓ ràng buộc để tính coverage), ở đây cần
 * biết CHÍNH XÁC giá trị ràng buộc (enum/min/max) để đối chiếu với giá trị AI đã điền.
 */
@Component
@Slf4j
public class TestCaseReliabilityValidator {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public record Result(TestCaseReliability status, List<String> notes) {
        public static Result reliable() {
            return new Result(TestCaseReliability.RELIABLE, List.of());
        }
    }

    private enum Location { BODY, PATH_OR_QUERY, HEADER }

    /** type/nullAllowed - quy tắc kiểu dữ liệu + null dùng chung với bộ chấm checklist 15h, xem SpecValueRules. */
    private record FieldRule(
            String name, Location location, boolean required, String type, boolean nullAllowed,
            List<String> enumValues, BigDecimal minimum, BigDecimal maximum,
            Integer minLength, Integer maxLength, boolean minimumExclusive, boolean maximumExclusive
    ) {
    }

    private static class StoredSchema {
        public List<Parameter> parameters;
        public RequestBody requestBody;
    }

    /**
     * Case kỳ vọng thành công (expectedStatus &lt; 400): có vi phạm ràng buộc nào tìm được trong
     * spec → NEEDS_REVIEW (dấu hiệu mạnh AI sinh dữ liệu sai).
     * <p>
     * Module 15f (mở rộng, trước đây case kỳ vọng lỗi KHÔNG bị đối chiếu gì cả - lỗ hổng không bắt
     * được case tên/mô tả nói "Negative"/"Boundary" nhưng dữ liệu thực ra vẫn hợp lệ): case kỳ vọng
     * lỗi (expectedStatus &gt;= 400) thuộc nhóm Security vẫn GIỮ NGUYÊN hành vi cũ - luôn RELIABLE,
     * không đối chiếu, vì payload injection/tấn công cố ý sai định dạng chính là chủ đích, rule tất
     * định không phân biệt được ý đồ. Case kỳ vọng lỗi KHÔNG thuộc Security (Negative/Boundary-vượt-
     * biên) thì ngược lại: KHÔNG tìm được vi phạm nào → NEEDS_REVIEW (tín hiệu một chiều, có thể vẫn
     * là lỗi hợp lệ vì lý do ngoài phạm vi rule này kiểm được - xem note cụ thể trả về).
     */
    public Result validate(Endpoint endpoint, GeneratedTestCase candidate, TestCaseSource source) {
        List<FieldRule> rules = extractFieldRules(endpoint);
        if (rules.isEmpty()) {
            return Result.reliable();
        }

        Map<String, Object> bodyValues = parseJsonObject(candidate.requestBody());
        // Module 15h - đọc cả giá trị ghi thẳng trong resolvedPath (ưu tiên), không chỉ pathParamFallbacks.
        Map<String, String> fallbackValues = TestCaseParamValues.resolve(
                endpoint.getPath(), candidate.resolvedPath(), candidate.pathParamFallbacks());
        Map<String, String> headerValues = candidate.requestHeaders() == null ? Map.of() : candidate.requestHeaders();

        List<String> violations = findViolations(rules, bodyValues, fallbackValues, headerValues);

        boolean expectsSuccess = candidate.expectedStatus() == null || candidate.expectedStatus() < 400;
        if (expectsSuccess) {
            return violations.isEmpty() ? Result.reliable() : new Result(TestCaseReliability.NEEDS_REVIEW, violations);
        }

        if (source == TestCaseSource.SECURITY || source == TestCaseSource.BUSINESS) {
            return Result.reliable(); // test nghiệp vụ kỳ vọng lỗi vì luật ngoài spec: không có gì để đối chiếu nên không gắn cờ
        }
        if (violations.isEmpty()) {
            return new Result(TestCaseReliability.NEEDS_REVIEW, List.of(
                    "Case kỳ vọng lỗi (status " + candidate.expectedStatus() + ") nhưng dữ liệu không vi phạm bất kỳ ràng "
                            + "buộc required/enum/min/max nào tìm được trong spec - request này có thể thực ra hợp lệ, hoặc "
                            + "lỗi kỳ vọng xảy ra vì lý do ngoài phạm vi rule này kiểm được (vd trùng dữ liệu, quy tắc nghiệp "
                            + "vụ, trạng thái tài nguyên)."));
        }
        return Result.reliable();
    }

    private List<String> findViolations(
            List<FieldRule> rules, Map<String, Object> bodyValues,
            Map<String, String> fallbackValues, Map<String, String> headerValues
    ) {
        List<String> notes = new ArrayList<>();
        for (FieldRule rule : rules) {
            if (rule.location() == Location.BODY) {
                checkBodyField(rule, bodyValues, notes);
            } else {
                Map<String, String> source = rule.location() == Location.HEADER ? headerValues : fallbackValues;
                String rawValue = source.get(rule.name());
                if (rawValue != null) {
                    checkRawValue(rule, rawValue, rawValue, false, notes);
                }
                // Không tự suy "thiếu" cho path/query/header param vắng mặt - param path gần như
                // luôn bắt buộc theo đúng bản chất URL, còn query/header tuỳ chọn không gửi là hợp
                // lệ; tránh báo sai khi AI cố ý không gửi 1 query param tuỳ chọn không liên quan.
            }
        }
        return notes;
    }

    private void checkBodyField(FieldRule rule, Map<String, Object> bodyValues, List<String> notes) {
        if (!bodyValues.containsKey(rule.name())) {
            if (rule.required()) {
                notes.add("Thiếu field bắt buộc '" + rule.name() + "' trong requestBody dù case kỳ vọng thành công");
            }
            return;
        }
        Object value = bodyValues.get(rule.name());
        if (value == null) {
            // OpenAPI 3.0: field không khai nullable: true thì null là không hợp lệ (required chỉ
            // nói field phải CÓ MẶT, không nói được phép null hay không).
            if (!rule.nullAllowed()) {
                notes.add("Field '" + rule.name() + "' = null nhưng spec không cho phép null (không khai nullable)");
            }
            return;
        }
        checkRawValue(rule, value, String.valueOf(value), true, notes);
    }

    private void checkRawValue(FieldRule rule, Object rawValue, String value, boolean fromJsonBody, List<String> notes) {
        if (value == null) {
            return;
        }
        if (rule.enumValues() != null && !rule.enumValues().isEmpty() && !rule.enumValues().contains(value)) {
            notes.add("Field '" + rule.name() + "' = \"" + value + "\" không khớp enum khai báo trong spec ("
                    + String.join(", ", rule.enumValues()) + ") dù case kỳ vọng thành công");
            return;
        }
        if (!SpecValueRules.matchesType(rule.type(), rawValue, fromJsonBody)) {
            notes.add("Field '" + rule.name() + "' = " + (rawValue instanceof String ? "\"" + value + "\"" : value)
                    + " sai kiểu dữ liệu so với spec (khai báo " + rule.type() + ")");
            return;
        }
        if (rule.minLength() != null && value.length() < rule.minLength()) {
            notes.add("Field '" + rule.name() + "' có độ dài " + value.length() + " < minLength=" + rule.minLength()
                    + " trong spec dù case kỳ vọng thành công");
        }
        if (rule.maxLength() != null && value.length() > rule.maxLength()) {
            notes.add("Field '" + rule.name() + "' có độ dài " + value.length() + " > maxLength=" + rule.maxLength()
                    + " trong spec dù case kỳ vọng thành công");
        }
        if (rule.minimum() != null || rule.maximum() != null) {
            BigDecimal numeric = parseNumeric(value);
            if (numeric != null) {
                // Exclusive (Module 15i): giá trị đúng bằng biên cũng không hợp lệ.
                int vsMin = rule.minimum() == null ? 1 : numeric.compareTo(rule.minimum());
                if (vsMin < 0 || (vsMin == 0 && rule.minimumExclusive())) {
                    notes.add("Field '" + rule.name() + "' = " + numeric + (rule.minimumExclusive() ? " <= " : " < ")
                            + "minimum=" + rule.minimum() + (rule.minimumExclusive() ? " (exclusive)" : "")
                            + " trong spec dù case kỳ vọng thành công");
                }
                int vsMax = rule.maximum() == null ? -1 : numeric.compareTo(rule.maximum());
                if (vsMax > 0 || (vsMax == 0 && rule.maximumExclusive())) {
                    notes.add("Field '" + rule.name() + "' = " + numeric + (rule.maximumExclusive() ? " >= " : " > ")
                            + "maximum=" + rule.maximum() + (rule.maximumExclusive() ? " (exclusive)" : "")
                            + " trong spec dù case kỳ vọng thành công");
                }
            }
            // Không parse được số chỉ còn xảy ra khi spec không khai type (đã kiểm kiểu ở trên) -
            // bỏ qua min/max, không suy diễn thêm.
        }
    }

    private BigDecimal parseNumeric(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            return parsed instanceof Map ? (Map<String, Object>) parsed : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<FieldRule> extractFieldRules(Endpoint endpoint) {
        if (endpoint.getSchema() == null || endpoint.getSchema().isBlank()) {
            return List.of();
        }
        StoredSchema stored;
        try {
            stored = objectMapper.readValue(endpoint.getSchema(), StoredSchema.class);
        } catch (Exception e) {
            log.warn("Khong parse duoc schema cua endpoint {} de kiem tra reliability: {}", endpoint.getId(), e.toString());
            return List.of();
        }

        List<FieldRule> rules = new ArrayList<>();
        if (stored.parameters != null) {
            for (Parameter p : stored.parameters) {
                if (p.getName() == null) {
                    continue;
                }
                Location location = "header".equalsIgnoreCase(p.getIn()) ? Location.HEADER : Location.PATH_OR_QUERY;
                rules.add(toFieldRule(p.getName(), location, Boolean.TRUE.equals(p.getRequired()), p.getSchema()));
            }
        }
        if (stored.requestBody != null && stored.requestBody.getContent() != null) {
            for (MediaType mediaType : stored.requestBody.getContent().values()) {
                Schema<?> bodySchema = mediaType.getSchema();
                if (bodySchema == null || bodySchema.getProperties() == null) {
                    continue;
                }
                Set<String> required = bodySchema.getRequired() == null ? Set.of() : Set.copyOf(bodySchema.getRequired());
                bodySchema.getProperties().forEach((name, propSchema) ->
                        rules.add(toFieldRule(name, Location.BODY, required.contains(name), propSchema)));
            }
        }
        return rules;
    }

    private FieldRule toFieldRule(String name, Location location, boolean required, Schema<?> schema) {
        if (schema == null) {
            return new FieldRule(name, location, required, null, true, null, null, null, null, null, false, false);
        }
        List<String> enumValues = schema.getEnum() == null ? null
                : schema.getEnum().stream().map(String::valueOf).toList();
        // Module 15i - gộp giới hạn ngầm của int32/int64 vào min/max để case "vượt int32" (checklist
        // Boundary) không bị gắn "kỳ vọng lỗi nhưng không vi phạm gì" oan.
        return new FieldRule(name, location, required, SpecValueRules.typeOf(schema), SpecValueRules.allowsNull(schema),
                enumValues, tighter(SpecValueRules.effectiveMinimum(schema), SpecValueRules.formatMinimum(schema), true),
                tighter(SpecValueRules.effectiveMaximum(schema), SpecValueRules.formatMaximum(schema), false),
                schema.getMinLength(), schema.getMaxLength(),
                SpecValueRules.minimumExclusive(schema), SpecValueRules.maximumExclusive(schema));
    }

    private BigDecimal tighter(BigDecimal declared, BigDecimal fromFormat, boolean isMinimum) {
        if (declared == null || fromFormat == null) {
            return declared != null ? declared : fromFormat;
        }
        return isMinimum ? declared.max(fromFormat) : declared.min(fromFormat);
    }
}
