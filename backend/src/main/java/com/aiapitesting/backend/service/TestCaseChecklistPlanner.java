package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.dto.response.ChecklistItemType;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.TestCaseAuthOverride;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Module 15h (phương án 3) - lập checklist EP/BVA tất định từ OpenAPI spec, CHẤM test case theo NỘI
 * DUNG THẬT (không tin số mục AI tự khai) và tự tạo case bù cho mục AI làm thiếu/sai. Không gọi AI.
 *
 * Module 15i - checklist chuẩn RIÊNG cho từng loại test case, AI bắt buộc làm đủ (không còn rỗng chỉ
 * vì spec không khai ràng buộc):
 * - Positive: 1 bộ dữ liệu hợp lệ.
 * - Negative: thiếu từng field bắt buộc; null ở từng field bắt buộc không nullable; sai kiểu từng
 *   field; giá trị ngoài enum.
 * - Boundary: biên khai rõ minimum/maximum/minLength/maxLength (đúng biên + ngay ngoài biên - BVA 2 giá
 *   trị); biên theo format int32/int64 (lớn nhất + vượt); biên ngầm khi spec không khai: số 0 và số
 *   âm (không có minimum), chuỗi rỗng (không có minLength) - spec không cấm nên kỳ vọng 2xx.
 * - Security: thiếu token, token sai (chỉ khi endpoint yêu cầu xác thực).
 *
 * Quy tắc chấm "mỗi case chỉ 1 lỗi": 1 case chỉ đạt 1 mục khi khớp đúng ý đồ VÀ không vi phạm thêm
 * ràng buộc nào khác - vi phạm 2 thứ cùng lúc thì API trả lỗi không chứng minh được nó kiểm đúng ràng
 * buộc đang xét. Quy tắc chấm chặt hơn hoặc bằng EndpointCoverageService (15c), nên mục đạt ở đây thì
 * dimension tương ứng của Coverage Matrix cũng COVERED.
 *
 * Tự trích schema riêng (cùng tiền lệ EndpointCoverageService/TestCaseReliabilityValidator) thay vì
 * refactor 2 service đã ổn định - ở đây cần vi phạm có CẤU TRÚC (field + loại) để xét "chỉ 1 lỗi".
 */
@Slf4j
@Component
public class TestCaseChecklistPlanner {

    /** Giá trị cố định chắc chắn không thuộc enum nào - dùng cho mục NEGATIVE_INVALID_ENUM. */
    public static final String INVALID_ENUM_VALUE = "__INVALID_ENUM__";

    /** Giá trị sai kiểu cho field số/boolean (gửi chuỗi chữ) và cho field chuỗi (gửi số). */
    static final String WRONG_TYPE_TEXT = "khong-phai-so";
    static final String WRONG_TYPE_NUMBER = "12345";

    static final String NOT_JSON_REASON =
            "requestBody của endpoint không phải JSON (vd form/upload file) - hệ thống không tự đọc/tạo được giá trị field";

    static final String NO_AUTH_REASON =
            "Endpoint không yêu cầu xác thực (spec không khai security) - không có gì để kiểm khi thiếu/sai token";

    private static final String NOT_FORBIDDEN_NOTE = "spec không cấm nên kỳ vọng thành công (2xx)";

    private static final int MAX_LITERAL_IN_DESCRIPTION = 20;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    public enum ParamLocation { BODY, PATH, QUERY, HEADER }

    /**
     * type/nullAllowed theo SpecValueRules - dùng chung với Tier 1 để 2 bên hiểu kiểu dữ liệu và null giống hệt nhau.
     * typeMinimum/typeMaximum = giới hạn ngầm của int32/int64 (15i); hasFormatOrPattern = spec khai
     * format/pattern cho chuỗi (vd email) - khi đó chuỗi rỗng không chắc hợp lệ, không tạo mục biên ngầm.
     */
    public record FieldRule(
            String name, ParamLocation location, boolean required, boolean numeric, String type, boolean nullAllowed,
            List<String> enumValues, BigDecimal minimum, BigDecimal maximum, Integer minLength, Integer maxLength,
            BigDecimal typeMinimum, BigDecimal typeMaximum, boolean hasFormatOrPattern,
            boolean minimumExclusive, boolean maximumExclusive
    ) {
    }

    /** Ràng buộc đã trích từ Endpoint.schema - đọc 1 lần, dùng cho plan/evaluate/fill. */
    public record Spec(String endpointPath, boolean hasBody, boolean bodyIsJson, List<FieldRule> rules) {
    }

    /**
     * 1 mục checklist. value = giá trị cụ thể cần thử (dạng chuỗi), null với POSITIVE/thiếu field.
     * notApplicableReason != null nghĩa là mục này không áp dụng được (không giao AI, không bù).
     */
    public record ChecklistItem(
            ChecklistItemType type, String field, ParamLocation location, String value, boolean expectSuccess,
            String description, String shortLabel, String rationale, String notApplicableReason
    ) {
        /**
         * Tham số path định danh tài nguyên nhận giá trị hợp lệ về kiểu nhưng không trỏ tới tài nguyên nào
         * (0, -1, lớn nhất int64) - kỳ vọng 404 (báo cáo ghi "lỗi 4xx" vì API có thể trả 400).
         */
        public boolean notFoundExpected() {
            return location == ParamLocation.PATH && !expectSuccess
                    && (type == ChecklistItemType.BOUNDARY_ZERO || type == ChecklistItemType.BOUNDARY_NEGATIVE_NUMBER
                    || type == ChecklistItemType.BOUNDARY_AT_TYPE_MAXIMUM);
        }

        /**
         * Mục mà Tier 1 (đối chiếu spec) KHÔNG nhìn thấy lý do lỗi nên sẽ gắn "Cần xem lại" oan: thiếu tham
         * số query/header bắt buộc, hoặc id không tồn tại. Bộ chấm checklist đã đọc nội dung case chặt hơn
         * Tier 1 nên kết luận của nó được tin hơn cho đúng những mục này.
         */
        public boolean tier1CannotSee() {
            return notFoundExpected()
                    || (type == ChecklistItemType.NEGATIVE_MISSING_FIELD && location != ParamLocation.BODY);
        }
    }

    private enum ViolationKind {
        MISSING_REQUIRED, NULL_NOT_ALLOWED, WRONG_TYPE, ENUM, BELOW_MINIMUM, ABOVE_MAXIMUM, BELOW_MIN_LENGTH, ABOVE_MAX_LENGTH,
        BELOW_TYPE_MINIMUM, ABOVE_TYPE_MAXIMUM
    }

    private record Violation(String field, ParamLocation location, ViolationKind kind) {
    }

    private record Values(Map<String, Object> body, Map<String, String> params, Map<String, String> headers) {
    }

    private static class StoredSchema {
        public List<Parameter> parameters;
        public RequestBody requestBody;
    }

    // ---- Đọc spec ----

    public Spec readSpec(Endpoint endpoint) {
        String path = endpoint.getPath();
        if (endpoint.getSchema() == null || endpoint.getSchema().isBlank()) {
            return new Spec(path, false, false, List.of());
        }
        StoredSchema stored;
        try {
            stored = objectMapper.readValue(endpoint.getSchema(), StoredSchema.class);
        } catch (Exception e) {
            log.warn("Khong parse duoc schema cua endpoint {} de lap checklist: {}", endpoint.getId(), e.toString());
            return new Spec(path, false, false, List.of());
        }

        List<FieldRule> rules = new ArrayList<>();
        boolean hasBody = false;
        boolean bodyIsJson = false;
        if (stored.requestBody != null && stored.requestBody.getContent() != null && !stored.requestBody.getContent().isEmpty()) {
            hasBody = true;
            Schema<?> bodySchema = null;
            for (Map.Entry<String, MediaType> entry : stored.requestBody.getContent().entrySet()) {
                boolean json = entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).contains("json");
                if (json && !bodyIsJson) {
                    bodyIsJson = true;
                    bodySchema = entry.getValue() == null ? null : entry.getValue().getSchema();
                } else if (bodySchema == null && !bodyIsJson && entry.getValue() != null) {
                    bodySchema = entry.getValue().getSchema();
                }
            }
            if (bodySchema != null && bodySchema.getProperties() != null) {
                List<String> required = bodySchema.getRequired() == null ? List.of() : bodySchema.getRequired();
                for (Map.Entry<String, Schema> prop : bodySchema.getProperties().entrySet()) {
                    rules.add(toRule(prop.getKey(), ParamLocation.BODY, required.contains(prop.getKey()), prop.getValue()));
                }
            }
        }
        if (stored.parameters != null) {
            for (Parameter p : stored.parameters) {
                if (p.getName() == null || p.getIn() == null) {
                    continue;
                }
                ParamLocation location = switch (p.getIn().toLowerCase(Locale.ROOT)) {
                    case "path" -> ParamLocation.PATH;
                    case "query" -> ParamLocation.QUERY;
                    case "header" -> ParamLocation.HEADER;
                    default -> null; // cookie - engine thực thi chưa hỗ trợ, bỏ qua
                };
                if (location != null) {
                    rules.add(toRule(p.getName(), location, Boolean.TRUE.equals(p.getRequired()), p.getSchema()));
                }
            }
        }
        return new Spec(path, hasBody, bodyIsJson, rules);
    }

    private FieldRule toRule(String name, ParamLocation location, boolean required, Schema<?> schema) {
        if (schema == null) {
            return new FieldRule(name, location, required, false, null, true, null, null, null, null, null, null, null, false, false, false);
        }
        String type = SpecValueRules.typeOf(schema);
        boolean numeric = "integer".equals(type) || "number".equals(type)
                || (type == null && (schema.getMinimum() != null || schema.getMaximum() != null));
        List<String> enumValues = schema.getEnum() == null || schema.getEnum().isEmpty()
                ? null : schema.getEnum().stream().map(String::valueOf).toList();
        return new FieldRule(name, location, required, numeric, type, SpecValueRules.allowsNull(schema), enumValues,
                numeric ? SpecValueRules.effectiveMinimum(schema) : null, numeric ? SpecValueRules.effectiveMaximum(schema) : null,
                numeric ? null : schema.getMinLength(), numeric ? null : schema.getMaxLength(),
                SpecValueRules.formatMinimum(schema), SpecValueRules.formatMaximum(schema),
                schema.getFormat() != null || schema.getPattern() != null,
                numeric && SpecValueRules.minimumExclusive(schema), numeric && SpecValueRules.maximumExclusive(schema));
    }

    // ---- Lập checklist ----

    public List<ChecklistItem> plan(Spec spec, boolean includePositive, boolean includeNegative, boolean includeBoundary) {
        List<ChecklistItem> items = new ArrayList<>();
        String bodyNotApplicable = spec.hasBody() && !spec.bodyIsJson() ? NOT_JSON_REASON : null;

        if (includePositive) {
            items.add(new ChecklistItem(ChecklistItemType.POSITIVE_BASELINE, null, null, null, true,
                    "Dữ liệu hợp lệ đầy đủ mọi field bắt buộc - kỳ vọng thành công (2xx)",
                    "Dữ liệu hợp lệ đầy đủ",
                    "Điền đủ mọi field bắt buộc đúng ràng buộc của spec để xác nhận luồng thành công cơ bản.", null));
            // EP: mỗi giá trị enum hợp lệ là 1 lớp tương đương riêng - mỗi giá trị cần 1 case thành công.
            for (FieldRule rule : spec.rules()) {
                if (rule.enumValues() == null) {
                    continue;
                }
                for (String value : rule.enumValues()) {
                    items.add(new ChecklistItem(ChecklistItemType.POSITIVE_ENUM_VALUE, rule.name(), rule.location(), value, true,
                            "'" + rule.name() + "' " + where(rule.location()) + " = " + value + " (giá trị enum hợp lệ) - kỳ vọng thành công (2xx)",
                            "'" + rule.name() + "' = " + value + " (giá trị enum)",
                            "Trường '" + rule.name() + "' " + where(rule.location()) + " có enum trong spec; mỗi giá trị enum hợp lệ là một "
                                    + "lớp tương đương riêng nên dùng giá trị " + value + " để kiểm tra API nhận đúng.",
                            rule.location() == ParamLocation.BODY ? bodyNotApplicable : null));
                }
            }
        }
        if (includeNegative) {
            for (FieldRule rule : spec.rules()) {
                if (rule.location() == ParamLocation.BODY && rule.required()) {
                    items.add(new ChecklistItem(ChecklistItemType.NEGATIVE_MISSING_FIELD, rule.name(), ParamLocation.BODY, null, false,
                            "Thiếu field bắt buộc '" + rule.name() + "' trong requestBody (các field khác giữ hợp lệ) - kỳ vọng 400",
                            "Thiếu field bắt buộc '" + rule.name() + "'",
                            "Trường '" + rule.name() + "' nằm trong mảng required của requestBody; bỏ đúng field này, giữ nguyên "
                                    + "các field khác hợp lệ để kiểm tra validation.",
                            bodyNotApplicable));
                }
            }
            // Tham số query/header bắt buộc vắng mặt cũng là 1 lớp không hợp lệ (path thì luôn có mặt theo bản chất URL).
            for (FieldRule rule : spec.rules()) {
                if ((rule.location() == ParamLocation.QUERY || rule.location() == ParamLocation.HEADER) && rule.required()) {
                    String kind = rule.location() == ParamLocation.QUERY ? "query" : "header";
                    items.add(new ChecklistItem(ChecklistItemType.NEGATIVE_MISSING_FIELD, rule.name(), rule.location(), null, false,
                            "Thiếu tham số " + kind + " bắt buộc '" + rule.name() + "' (các tham số khác giữ hợp lệ) - kỳ vọng 400",
                            "Thiếu tham số " + kind + " bắt buộc '" + rule.name() + "'",
                            "Tham số " + kind + " '" + rule.name() + "' khai required trong spec; bỏ đúng tham số này, giữ nguyên "
                                    + "phần còn lại hợp lệ để kiểm tra validation.",
                            null));
                }
            }
            for (FieldRule rule : spec.rules()) {
                if (rule.location() == ParamLocation.BODY && rule.required() && !rule.nullAllowed()) {
                    items.add(new ChecklistItem(ChecklistItemType.NEGATIVE_NULL_FIELD, rule.name(), ParamLocation.BODY, null, false,
                            "'" + rule.name() + "' trong requestBody gửi giá trị null (\"" + rule.name() + "\": null, khác bỏ hẳn field) - kỳ vọng 400",
                            "'" + rule.name() + "' = null",
                            "Trường '" + rule.name() + "' bắt buộc và spec không khai nullable nên null không hợp lệ; gửi null để kiểm tra API chặn.",
                            bodyNotApplicable));
                }
            }
            for (FieldRule rule : spec.rules()) {
                String wrong = wrongTypeValue(rule);
                if (wrong != null) {
                    String shown = rule.numeric() || "boolean".equals(rule.type()) ? "\"" + wrong + "\" (chuỗi chữ)" : wrong + " (số)";
                    items.add(new ChecklistItem(ChecklistItemType.NEGATIVE_WRONG_TYPE, rule.name(), rule.location(), wrong, false,
                            "'" + rule.name() + "' " + where(rule.location()) + " nhận giá trị sai kiểu " + shown
                                    + " thay vì kiểu " + rule.type() + " - kỳ vọng 400",
                            "'" + rule.name() + "' sai kiểu dữ liệu",
                            "Trường '" + rule.name() + "' " + where(rule.location()) + " khai kiểu " + rule.type()
                                    + " trong spec; gửi giá trị sai kiểu để kiểm tra API chặn.",
                            rule.location() == ParamLocation.BODY ? bodyNotApplicable : null));
                }
            }
            for (FieldRule rule : spec.rules()) {
                if (rule.enumValues() != null) {
                    String allowed = String.join(", ", rule.enumValues());
                    items.add(new ChecklistItem(ChecklistItemType.NEGATIVE_INVALID_ENUM, rule.name(), rule.location(), INVALID_ENUM_VALUE, false,
                            "'" + rule.name() + "' " + where(rule.location()) + " nhận giá trị không thuộc enum (" + allowed + ") - kỳ vọng 400",
                            "'" + rule.name() + "' ngoài danh sách enum",
                            "Trường '" + rule.name() + "' " + where(rule.location()) + " chỉ nhận các giá trị enum (" + allowed
                                    + ") trong spec; dùng giá trị ngoài danh sách để kiểm tra API chặn.",
                            rule.location() == ParamLocation.BODY ? bodyNotApplicable : null));
                }
            }
        }
        if (includeBoundary) {
            for (FieldRule rule : spec.rules()) {
                String na = rule.location() == ParamLocation.BODY ? bodyNotApplicable : null;
                addDeclaredNumericBounds(rule, na, items);
                if (rule.minLength() != null) {
                    int n = rule.minLength();
                    items.add(boundary(ChecklistItemType.BOUNDARY_AT_MIN_LENGTH, rule, "a".repeat(n), true, na,
                            "dài đúng minLength = " + n + " ký tự" + literalHint(n), "dài " + n + " ký tự (đúng minLength)",
                            "có minLength=" + n + " trong spec; dùng chuỗi đúng " + n + " ký tự (hợp lệ) để kiểm tra biên dưới."));
                    if (n > 0) {
                        items.add(boundary(ChecklistItemType.BOUNDARY_BELOW_MIN_LENGTH, rule, "a".repeat(n - 1), false, na,
                                "dài " + (n - 1) + " ký tự, ngắn hơn minLength = " + n + literalHint(n - 1),
                                "dài " + (n - 1) + " ký tự (dưới minLength " + n + ")",
                                "có minLength=" + n + " trong spec; dùng chuỗi " + (n - 1) + " ký tự ngay dưới biên để kiểm tra API chặn."));
                    }
                }
                if (rule.maxLength() != null) {
                    int n = rule.maxLength();
                    items.add(boundary(ChecklistItemType.BOUNDARY_AT_MAX_LENGTH, rule, "a".repeat(n), true, na,
                            "dài đúng maxLength = " + n + " ký tự" + literalHint(n), "dài " + n + " ký tự (đúng maxLength)",
                            "có maxLength=" + n + " trong spec; dùng chuỗi đúng " + n + " ký tự (hợp lệ) để kiểm tra biên trên."));
                    items.add(boundary(ChecklistItemType.BOUNDARY_ABOVE_MAX_LENGTH, rule, "a".repeat(n + 1), false, na,
                            "dài " + (n + 1) + " ký tự, dài hơn maxLength = " + n + literalHint(n + 1),
                            "dài " + (n + 1) + " ký tự (trên maxLength " + n + ")",
                            "có maxLength=" + n + " trong spec; dùng chuỗi " + (n + 1) + " ký tự ngay trên biên để kiểm tra API chặn."));
                }
                addImplicitBoundaries(rule, na, items);
            }
        }
        return items;
    }

    /**
     * Biên khai rõ minimum/maximum, kể cả exclusive (Module 15i): minimum exclusive thì giá trị đúng bằng
     * minimum KHÔNG hợp lệ - biên hợp lệ nhỏ nhất là minimum + 1, giá trị bị chặn là chính minimum.
     */
    private void addDeclaredNumericBounds(FieldRule rule, String na, List<ChecklistItem> items) {
        if (rule.minimum() != null) {
            BigDecimal min = rule.minimum();
            boolean ex = rule.minimumExclusive();
            String bound = plain(min);
            String at = plain(ex ? min.add(BigDecimal.ONE) : min);
            String below = plain(ex ? min : min.subtract(BigDecimal.ONE));
            String declared = ex ? "minimum = " + bound + " (exclusive - giá trị đúng bằng minimum không hợp lệ)" : "minimum = " + bound;
            items.add(boundary(ChecklistItemType.BOUNDARY_AT_MINIMUM, rule, at, true, na,
                    ex ? "= " + at + ", nhỏ nhất hợp lệ vì " + declared : "đúng bằng minimum = " + bound,
                    ex ? "= " + at + " (nhỏ nhất hợp lệ, minimum exclusive)" : "= " + at + " (đúng minimum)",
                    "có " + declared + " trong spec; dùng đúng giá trị biên hợp lệ " + at + " để kiểm tra API không chặn nhầm giá trị biên."));
            items.add(boundary(ChecklistItemType.BOUNDARY_BELOW_MINIMUM, rule, below, false, na,
                    ex ? "= " + below + ", không hợp lệ vì " + declared : "= " + below + ", nhỏ hơn minimum = " + bound,
                    ex ? "= " + below + " (bằng minimum exclusive " + bound + ")" : "= " + below + " (dưới minimum " + bound + ")",
                    "có " + declared + " trong spec; dùng " + below + " ngay ngoài biên để kiểm tra API chặn giá trị vượt biên."));
        }
        if (rule.maximum() != null) {
            BigDecimal max = rule.maximum();
            boolean ex = rule.maximumExclusive();
            String bound = plain(max);
            String at = plain(ex ? max.subtract(BigDecimal.ONE) : max);
            String above = plain(ex ? max : max.add(BigDecimal.ONE));
            String declared = ex ? "maximum = " + bound + " (exclusive - giá trị đúng bằng maximum không hợp lệ)" : "maximum = " + bound;
            items.add(boundary(ChecklistItemType.BOUNDARY_AT_MAXIMUM, rule, at, true, na,
                    ex ? "= " + at + ", lớn nhất hợp lệ vì " + declared : "đúng bằng maximum = " + bound,
                    ex ? "= " + at + " (lớn nhất hợp lệ, maximum exclusive)" : "= " + at + " (đúng maximum)",
                    "có " + declared + " trong spec; dùng đúng giá trị biên hợp lệ " + at + " để kiểm tra API không chặn nhầm giá trị biên."));
            items.add(boundary(ChecklistItemType.BOUNDARY_ABOVE_MAXIMUM, rule, above, false, na,
                    ex ? "= " + above + ", không hợp lệ vì " + declared : "= " + above + ", lớn hơn maximum = " + bound,
                    ex ? "= " + above + " (bằng maximum exclusive " + bound + ")" : "= " + above + " (trên maximum " + bound + ")",
                    "có " + declared + " trong spec; dùng " + above + " ngay ngoài biên để kiểm tra API chặn giá trị vượt biên."));
        }
    }

    /**
     * Module 15i - biên ngầm khi spec KHÔNG khai ràng buộc, cả 2 đầu của kiểu int32/int64 (lớn nhất + vượt
     * trên, nhỏ nhất + vượt dưới) và số 0/số âm/chuỗi rỗng. Body và query là giá trị tự do nên các giá trị hợp
     * lệ về kiểu kỳ vọng thành công (spec không cấm). Tham số path định danh 1 tài nguyên thật nên 0/âm/lớn
     * nhất vẫn là biên cần thử nhưng kỳ vọng LỖI 4xx (không có tài nguyên nào mang id đó) - trước đây bị bỏ hẳn.
     * Header bỏ qua (hiếm khi mang dữ liệu nghiệp vụ dạng số/chuỗi tự do).
     */
    private void addImplicitBoundaries(FieldRule rule, String na, List<ChecklistItem> items) {
        if (rule.location() == ParamLocation.HEADER || rule.enumValues() != null) {
            return;
        }
        boolean path = rule.location() == ParamLocation.PATH;
        if (rule.numeric() && rule.typeMaximum() != null && rule.maximum() == null) {
            String max = plain(rule.typeMaximum());
            String above = plain(rule.typeMaximum().add(BigDecimal.ONE));
            String typeName = typeName(rule.typeMaximum());
            items.add(path
                    ? notFound(ChecklistItemType.BOUNDARY_AT_TYPE_MAXIMUM, rule, max,
                            "= " + max + ", giá trị lớn nhất của kiểu " + typeName, "= " + max + " (lớn nhất " + typeName + ")",
                            "khai format " + typeName + "; id lớn nhất " + max + " còn biểu diễn được nhưng gần như chắc không tồn tại - kiểm tra API xử lý đúng khi không tìm thấy.")
                    : boundary(ChecklistItemType.BOUNDARY_AT_TYPE_MAXIMUM, rule, max, true, na,
                            "= " + max + ", giá trị lớn nhất của kiểu " + typeName, "= " + max + " (lớn nhất " + typeName + ")",
                            "khai format " + typeName + " và không khai maximum; dùng giá trị lớn nhất " + max + " của kiểu để kiểm tra API nhận đúng."));
            items.add(boundary(ChecklistItemType.BOUNDARY_ABOVE_TYPE_MAXIMUM, rule, above, false, na,
                    "= " + above + ", vượt giới hạn kiểu " + typeName, "= " + above + " (vượt " + typeName + ")",
                    "khai format " + typeName + "; dùng " + above + " vượt giới hạn kiểu để kiểm tra API chặn tràn số."));
        }
        if (rule.numeric() && rule.typeMinimum() != null && rule.minimum() == null) {
            String min = plain(rule.typeMinimum());
            String below = plain(rule.typeMinimum().subtract(BigDecimal.ONE));
            String typeName = typeName(rule.typeMinimum());
            if (!path) {
                items.add(boundary(ChecklistItemType.BOUNDARY_AT_TYPE_MINIMUM, rule, min, true, na,
                        "= " + min + ", giá trị nhỏ nhất của kiểu " + typeName, "= " + min + " (nhỏ nhất " + typeName + ")",
                        "khai format " + typeName + " và không khai minimum; dùng giá trị nhỏ nhất " + min + " của kiểu để kiểm tra API nhận đúng."));
            }
            items.add(boundary(ChecklistItemType.BOUNDARY_BELOW_TYPE_MINIMUM, rule, below, false, na,
                    "= " + below + ", dưới giới hạn kiểu " + typeName, "= " + below + " (dưới " + typeName + ")",
                    "khai format " + typeName + "; dùng " + below + " dưới giới hạn kiểu để kiểm tra API chặn tràn số."));
        }
        if (rule.numeric() && rule.minimum() == null && (!path || "integer".equals(rule.type()))) {
            if (rule.maximum() == null || rule.maximum().signum() >= 0) {
                items.add(path
                        ? notFound(ChecklistItemType.BOUNDARY_ZERO, rule, "0", "= 0", "= 0 (id không tồn tại)",
                                "id = 0 hợp lệ về kiểu nhưng không trỏ tới tài nguyên nào - kiểm tra API xử lý đúng khi không tìm thấy.")
                        : boundary(ChecklistItemType.BOUNDARY_ZERO, rule, "0", true, na,
                                "= 0 (spec không khai minimum)", "= 0 (biên ngầm)",
                                "không khai minimum trong spec nên 0 không bị cấm - " + NOT_FORBIDDEN_NOTE
                                        + "; API trả 400 nghĩa là spec khai thiếu ràng buộc hoặc API có lỗi."));
            }
            if (rule.maximum() == null || rule.maximum().compareTo(BigDecimal.ONE.negate()) >= 0) {
                items.add(path
                        ? notFound(ChecklistItemType.BOUNDARY_NEGATIVE_NUMBER, rule, "-1", "= -1, số âm", "= -1 (id không tồn tại)",
                                "id số âm hợp lệ về kiểu nhưng không trỏ tới tài nguyên nào - kiểm tra API xử lý đúng khi không tìm thấy.")
                        : boundary(ChecklistItemType.BOUNDARY_NEGATIVE_NUMBER, rule, "-1", true, na,
                                "= -1, số âm (spec không khai minimum)", "= -1 (biên ngầm)",
                                "không khai minimum trong spec nên số âm không bị cấm - " + NOT_FORBIDDEN_NOTE
                                        + "; API trả 400 nghĩa là spec khai thiếu ràng buộc hoặc API có lỗi."));
            }
        }
        if (!path && "string".equals(rule.type()) && rule.minLength() == null && !rule.hasFormatOrPattern()) {
            items.add(boundary(ChecklistItemType.BOUNDARY_EMPTY_STRING, rule, "", true, na,
                    "= \"\", chuỗi rỗng (spec không khai minLength)", "= \"\" (biên ngầm)",
                    "không khai minLength trong spec nên chuỗi rỗng không bị cấm - " + NOT_FORBIDDEN_NOTE
                            + "; API trả 400 nghĩa là spec khai thiếu ràng buộc hoặc API có lỗi."));
        }
    }

    private String typeName(BigDecimal limit) {
        return limit.abs().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE).add(BigDecimal.ONE)) <= 0 ? "int32" : "int64";
    }

    /** Mục tham số path: giá trị hợp lệ về kiểu nhưng không trỏ tới tài nguyên nào - kỳ vọng lỗi 4xx (404). */
    private ChecklistItem notFound(ChecklistItemType type, FieldRule rule, String value, String what, String shortWhat, String why) {
        return new ChecklistItem(type, rule.name(), ParamLocation.PATH, value, false,
                "'" + rule.name() + "' (tham số path) " + what + " - kỳ vọng lỗi 4xx (404: không có tài nguyên mang id này)",
                "'" + rule.name() + "' " + shortWhat,
                "Trường '" + rule.name() + "' (tham số path) " + why,
                null);
    }

    /** Giá trị sai kiểu cho mục NEGATIVE_WRONG_TYPE; null nếu field không có kiểu kiểm được (object/array/không khai). */
    private String wrongTypeValue(FieldRule rule) {
        if (rule.type() == null || rule.enumValues() != null && !"string".equals(rule.type())) {
            return null;
        }
        return switch (rule.type()) {
            case "integer", "number", "boolean" -> WRONG_TYPE_TEXT;
            // Tham số path/query/header luôn là chuỗi - không có giá trị nào "sai kiểu chuỗi".
            case "string" -> rule.location() == ParamLocation.BODY ? WRONG_TYPE_NUMBER : null;
            default -> null;
        };
    }

    /** Module 15i - checklist Security: thiếu token + token sai, chỉ áp dụng khi endpoint yêu cầu xác thực. */
    public List<ChecklistItem> planSecurity(boolean requiresAuth) {
        String na = requiresAuth ? null : NO_AUTH_REASON;
        return List.of(
                new ChecklistItem(ChecklistItemType.SECURITY_MISSING_TOKEN, null, null, null, false,
                        "Gọi KHÔNG kèm token (authOverride = \"NONE\") - kỳ vọng 401",
                        "Thiếu token",
                        "Endpoint yêu cầu xác thực; gọi không kèm token để kiểm tra API chặn truy cập trái phép.", na),
                new ChecklistItem(ChecklistItemType.SECURITY_INVALID_TOKEN, null, null, null, false,
                        "Gọi với token SAI/hết hạn (authOverride = \"INVALID\") - kỳ vọng 401",
                        "Token sai",
                        "Endpoint yêu cầu xác thực; gọi với token sai để kiểm tra API không chấp nhận token không hợp lệ.", na));
    }

    private ChecklistItem boundary(
            ChecklistItemType type, FieldRule rule, String value, boolean expectSuccess, String notApplicable,
            String what, String shortWhat, String why
    ) {
        String status = expectSuccess ? "kỳ vọng thành công (2xx)" : "kỳ vọng 400";
        return new ChecklistItem(type, rule.name(), rule.location(), value, expectSuccess,
                "'" + rule.name() + "' " + where(rule.location()) + " " + what + " - " + status,
                "'" + rule.name() + "' " + shortWhat,
                "Trường '" + rule.name() + "' " + where(rule.location()) + " " + why,
                notApplicable);
    }

    private String where(ParamLocation location) {
        return switch (location) {
            case BODY -> "trong requestBody";
            case PATH -> "(tham số path)";
            case QUERY -> "(tham số query)";
            case HEADER -> "(header)";
        };
    }

    private String literalHint(int length) {
        return length > 0 && length <= MAX_LITERAL_IN_DESCRIPTION ? " (vd \"" + "a".repeat(length) + "\")" : "";
    }

    private String plain(BigDecimal value) {
        return value.toPlainString();
    }

    // ---- Chấm theo nội dung ----

    /** Case này có thật sự đạt mục checklist không - đọc nội dung thật, không dựa vào tên/mô tả/lời AI tự khai. */
    public boolean satisfies(Spec spec, ChecklistItem item, GeneratedTestCase candidate) {
        if (item.notApplicableReason() != null || candidate.expectedStatus() == null) {
            return false;
        }
        boolean expectsSuccess = candidate.expectedStatus() < 400;
        if (expectsSuccess != item.expectSuccess()) {
            return false;
        }
        // Security chỉ xét cách gọi (authOverride) + kỳ vọng 401 - nội dung body không phải thứ đang kiểm.
        if (item.type() == ChecklistItemType.SECURITY_MISSING_TOKEN) {
            return candidate.authOverride() == TestCaseAuthOverride.NONE && candidate.expectedStatus() == 401;
        }
        if (item.type() == ChecklistItemType.SECURITY_INVALID_TOKEN) {
            return candidate.authOverride() == TestCaseAuthOverride.INVALID && candidate.expectedStatus() == 401;
        }
        Values values = values(spec, candidate);
        List<Violation> violations = violations(spec, values);
        return switch (item.type()) {
            case POSITIVE_BASELINE -> violations.isEmpty();
            case POSITIVE_ENUM_VALUE -> violations.isEmpty() && textEquals(rawValue(values, item), item.value());
            case NEGATIVE_MISSING_FIELD -> onlyViolation(violations, item, ViolationKind.MISSING_REQUIRED);
            case NEGATIVE_NULL_FIELD -> onlyViolation(violations, item, ViolationKind.NULL_NOT_ALLOWED);
            case NEGATIVE_WRONG_TYPE -> onlyViolation(violations, item, ViolationKind.WRONG_TYPE);
            case NEGATIVE_INVALID_ENUM -> onlyViolation(violations, item, ViolationKind.ENUM);
            case BOUNDARY_AT_MINIMUM, BOUNDARY_AT_MAXIMUM, BOUNDARY_AT_TYPE_MAXIMUM, BOUNDARY_AT_TYPE_MINIMUM,
                 BOUNDARY_ZERO, BOUNDARY_NEGATIVE_NUMBER ->
                    violations.isEmpty() && numericEquals(rawValue(values, item), item.value());
            case BOUNDARY_AT_MIN_LENGTH, BOUNDARY_AT_MAX_LENGTH, BOUNDARY_EMPTY_STRING ->
                    violations.isEmpty() && lengthEquals(rawValue(values, item), item.value());
            case BOUNDARY_BELOW_MINIMUM -> onlyViolation(violations, item, ViolationKind.BELOW_MINIMUM);
            case BOUNDARY_ABOVE_MAXIMUM -> onlyViolation(violations, item, ViolationKind.ABOVE_MAXIMUM);
            case BOUNDARY_ABOVE_TYPE_MAXIMUM -> onlyViolation(violations, item, ViolationKind.ABOVE_TYPE_MAXIMUM);
            case BOUNDARY_BELOW_TYPE_MINIMUM -> onlyViolation(violations, item, ViolationKind.BELOW_TYPE_MINIMUM);
            case BOUNDARY_BELOW_MIN_LENGTH -> onlyViolation(violations, item, ViolationKind.BELOW_MIN_LENGTH);
            case BOUNDARY_ABOVE_MAX_LENGTH -> onlyViolation(violations, item, ViolationKind.ABOVE_MAX_LENGTH);
            case SECURITY_MISSING_TOKEN, SECURITY_INVALID_TOKEN -> false; // đã xử lý ở trên
        };
    }

    /** Case kỳ vọng thành công và không vi phạm ràng buộc nào - đủ điều kiện làm gốc để tạo case bù. */
    public boolean isValidBaseline(Spec spec, GeneratedTestCase candidate) {
        return candidate.expectedStatus() != null && candidate.expectedStatus() < 400
                && violations(spec, values(spec, candidate)).isEmpty();
    }

    private boolean onlyViolation(List<Violation> violations, ChecklistItem item, ViolationKind kind) {
        return violations.size() == 1 && violations.get(0).equals(new Violation(item.field(), item.location(), kind));
    }

    private Object rawValue(Values values, ChecklistItem item) {
        return switch (item.location()) {
            case BODY -> values.body().get(item.field());
            case PATH, QUERY -> values.params().get(item.field());
            case HEADER -> values.headers().get(item.field());
        };
    }

    private boolean textEquals(Object raw, String expected) {
        return raw != null && asText(raw).equals(expected);
    }

    private boolean numericEquals(Object raw, String expected) {
        BigDecimal actual = raw == null ? null : parseNumber(asText(raw));
        return actual != null && actual.compareTo(new BigDecimal(expected)) == 0;
    }

    private boolean lengthEquals(Object raw, String expected) {
        return raw != null && length(asText(raw)) == length(expected);
    }

    private Values values(Spec spec, GeneratedTestCase candidate) {
        Map<String, Object> body = parseObject(candidate.requestBody());
        Map<String, String> params = TestCaseParamValues.resolve(spec.endpointPath(), candidate.resolvedPath(), candidate.pathParamFallbacks());
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (candidate.requestHeaders() != null) {
            headers.putAll(candidate.requestHeaders());
        }
        return new Values(body, params, headers);
    }

    private List<Violation> violations(Spec spec, Values values) {
        List<Violation> result = new ArrayList<>();
        for (FieldRule rule : spec.rules()) {
            if (rule.location() == ParamLocation.BODY) {
                if (!spec.bodyIsJson()) {
                    continue;
                }
                if (!values.body().containsKey(rule.name())) {
                    if (rule.required()) {
                        result.add(new Violation(rule.name(), rule.location(), ViolationKind.MISSING_REQUIRED));
                    }
                    continue;
                }
                Object value = values.body().get(rule.name());
                if (value == null) {
                    // Cùng quy tắc Tier 1: null chỉ hợp lệ khi spec khai nullable (SpecValueRules).
                    if (!rule.nullAllowed()) {
                        result.add(new Violation(rule.name(), rule.location(), ViolationKind.NULL_NOT_ALLOWED));
                    }
                    continue;
                }
                checkValue(rule, value, true, result);
            } else {
                String value = rule.location() == ParamLocation.HEADER
                        ? values.headers().get(rule.name()) : values.params().get(rule.name());
                if (value != null) {
                    checkValue(rule, value, false, result);
                } else if (rule.required() && rule.location() != ParamLocation.PATH) {
                    // Module 15i: query/header khai required mà vắng mặt là lớp không hợp lệ (checklist Negative có
                    // mục này). Path luôn có mặt theo bản chất URL nên không suy "thiếu". Tier 1 vẫn không nhìn thấy
                    // lỗi này - xem ChecklistItem.tier1CannotSee().
                    result.add(new Violation(rule.name(), rule.location(), ViolationKind.MISSING_REQUIRED));
                }
            }
        }
        return result;
    }

    private void checkValue(FieldRule rule, Object value, boolean fromJsonBody, List<Violation> result) {
        String text = asText(value);
        // 15i - kiểm kiểu TRƯỚC enum: field chuỗi có enum nhận số 12345 là lỗi sai kiểu, không phải ngoài enum.
        if (!SpecValueRules.matchesType(rule.type(), value, fromJsonBody)) {
            result.add(new Violation(rule.name(), rule.location(), ViolationKind.WRONG_TYPE));
            return;
        }
        if (rule.enumValues() != null && !rule.enumValues().contains(text)) {
            result.add(new Violation(rule.name(), rule.location(), ViolationKind.ENUM));
            return;
        }
        if (rule.numeric()) {
            BigDecimal number = parseNumber(text);
            if (number == null) {
                result.add(new Violation(rule.name(), rule.location(), ViolationKind.WRONG_TYPE));
                return;
            }
            if (rule.typeMinimum() != null && number.compareTo(rule.typeMinimum()) < 0) {
                result.add(new Violation(rule.name(), rule.location(), ViolationKind.BELOW_TYPE_MINIMUM));
            }
            if (rule.typeMaximum() != null && number.compareTo(rule.typeMaximum()) > 0) {
                result.add(new Violation(rule.name(), rule.location(), ViolationKind.ABOVE_TYPE_MAXIMUM));
            }
            if (rule.minimum() != null) {
                int cmp = number.compareTo(rule.minimum());
                if (cmp < 0 || (cmp == 0 && rule.minimumExclusive())) {
                    result.add(new Violation(rule.name(), rule.location(), ViolationKind.BELOW_MINIMUM));
                }
            }
            if (rule.maximum() != null) {
                int cmp = number.compareTo(rule.maximum());
                if (cmp > 0 || (cmp == 0 && rule.maximumExclusive())) {
                    result.add(new Violation(rule.name(), rule.location(), ViolationKind.ABOVE_MAXIMUM));
                }
            }
            return;
        }
        // Độ dài chỉ có nghĩa với chuỗi thật - body có thể là số/boolean dù schema khai string.
        boolean isText = rule.location() != ParamLocation.BODY || value instanceof String;
        if (isText) {
            int length = length(text);
            if (rule.minLength() != null && length < rule.minLength()) {
                result.add(new Violation(rule.name(), rule.location(), ViolationKind.BELOW_MIN_LENGTH));
            }
            if (rule.maxLength() != null && length > rule.maxLength()) {
                result.add(new Violation(rule.name(), rule.location(), ViolationKind.ABOVE_MAX_LENGTH));
            }
        }
    }

    // ---- Tự tạo case bù ----

    /**
     * Tạo case bù cho 1 mục từ case gốc hợp lệ: copy rồi sửa ĐÚNG 1 chỗ. Trả null nếu không tạo được
     * (vd không đối chiếu được segment path) - tự chấm lại case vừa tạo, không đạt thì cũng trả null,
     * không bao giờ trả về case sai.
     */
    public GeneratedTestCase fill(Spec spec, ChecklistItem item, GeneratedTestCase baseline) {
        if (item.notApplicableReason() != null) {
            return null;
        }
        if (item.type() == ChecklistItemType.SECURITY_MISSING_TOKEN || item.type() == ChecklistItemType.SECURITY_INVALID_TOKEN) {
            return fillSecurity(spec, item, baseline);
        }
        if (item.location() == null) {
            return null;
        }
        Map<String, String> headers = new LinkedHashMap<>(baseline.requestHeaders() == null ? Map.of() : baseline.requestHeaders());
        Map<String, String> fallbacks = new LinkedHashMap<>(baseline.pathParamFallbacks() == null ? Map.of() : baseline.pathParamFallbacks());
        String body = baseline.requestBody();
        String resolvedPath = baseline.resolvedPath() == null ? spec.endpointPath() : baseline.resolvedPath();

        switch (item.location()) {
            case BODY -> {
                Map<String, Object> fields = new LinkedHashMap<>(parseObject(body));
                if (item.type() == ChecklistItemType.NEGATIVE_MISSING_FIELD) {
                    fields.remove(item.field());
                } else {
                    fields.put(item.field(), typedValue(spec, item));
                }
                try {
                    body = objectMapper.writeValueAsString(fields);
                } catch (Exception e) {
                    return null;
                }
            }
            case PATH -> {
                resolvedPath = replacePathSegment(spec.endpointPath(), resolvedPath, item.field(), item.value());
                if (resolvedPath == null) {
                    return null;
                }
                fallbacks.remove(item.field());
            }
            case QUERY -> {
                resolvedPath = item.type() == ChecklistItemType.NEGATIVE_MISSING_FIELD
                        ? removeQueryParam(resolvedPath, item.field())
                        : setQueryParam(resolvedPath, item.field(), item.value());
                fallbacks.remove(item.field());
            }
            case HEADER -> {
                if (item.type() == ChecklistItemType.NEGATIVE_MISSING_FIELD) {
                    headers.keySet().removeIf(name -> name.equalsIgnoreCase(item.field()));
                } else {
                    headers.put(item.field(), item.value());
                }
            }
        }

        String group = item.type().name().startsWith("POSITIVE_") ? "Positive"
                : item.type().name().startsWith("NEGATIVE_") ? "Negative" : "Boundary Value";
        GeneratedTestCase filled = new GeneratedTestCase(
                group + " - " + item.shortLabel(),
                "Hệ thống tự bổ sung vì AI chưa sinh case đạt mục checklist: " + item.description(),
                headers,
                body,
                item.expectSuccess() ? baseline.expectedStatus() : item.notFoundExpected() ? 404 : 400,
                resolvedPath,
                fallbacks,
                baseline.authOverride(),
                item.rationale());
        return satisfies(spec, item, filled) ? filled : null;
    }

    /** Case bù Security: copy case gốc, chỉ đổi cách gọi (authOverride) + kỳ vọng 401, bỏ header xác thực tự thêm. */
    private GeneratedTestCase fillSecurity(Spec spec, ChecklistItem item, GeneratedTestCase baseline) {
        Map<String, String> headers = new LinkedHashMap<>(baseline.requestHeaders() == null ? Map.of() : baseline.requestHeaders());
        headers.keySet().removeIf(name -> name.equalsIgnoreCase("Authorization"));
        TestCaseAuthOverride override = item.type() == ChecklistItemType.SECURITY_MISSING_TOKEN
                ? TestCaseAuthOverride.NONE : TestCaseAuthOverride.INVALID;
        GeneratedTestCase filled = new GeneratedTestCase(
                "Security - " + item.shortLabel(),
                "Hệ thống tự bổ sung vì AI chưa sinh case đạt mục checklist: " + item.description(),
                headers,
                baseline.requestBody(),
                401,
                baseline.resolvedPath() == null ? spec.endpointPath() : baseline.resolvedPath(),
                baseline.pathParamFallbacks() == null ? Map.of() : baseline.pathParamFallbacks(),
                override,
                item.rationale());
        return satisfies(spec, item, filled) ? filled : null;
    }

    private Object typedValue(Spec spec, ChecklistItem item) {
        return switch (item.type()) {
            case BOUNDARY_AT_MINIMUM, BOUNDARY_BELOW_MINIMUM, BOUNDARY_AT_MAXIMUM, BOUNDARY_ABOVE_MAXIMUM,
                 BOUNDARY_AT_TYPE_MAXIMUM, BOUNDARY_ABOVE_TYPE_MAXIMUM, BOUNDARY_AT_TYPE_MINIMUM,
                 BOUNDARY_BELOW_TYPE_MINIMUM, BOUNDARY_ZERO, BOUNDARY_NEGATIVE_NUMBER ->
                    new BigDecimal(item.value());
            // Giá trị enum của field số phải là số thật trong JSON (không phải chuỗi "1").
            case POSITIVE_ENUM_VALUE -> spec.rules().stream()
                    .filter(r -> r.name().equals(item.field()) && r.location() == item.location())
                    .findFirst().filter(FieldRule::numeric).<Object>map(r -> new BigDecimal(item.value()))
                    .orElse(item.value());
            case NEGATIVE_NULL_FIELD -> null;
            // Field chuỗi nhận SỐ thật trong JSON (không phải chuỗi "12345" - chuỗi thì vẫn đúng kiểu).
            case NEGATIVE_WRONG_TYPE -> WRONG_TYPE_NUMBER.equals(item.value()) ? new BigDecimal(item.value()) : item.value();
            default -> item.value();
        };
    }

    private String replacePathSegment(String endpointPath, String resolvedPath, String param, String value) {
        if (endpointPath == null) {
            return null;
        }
        int queryStart = resolvedPath.indexOf('?');
        String pathPart = queryStart < 0 ? resolvedPath : resolvedPath.substring(0, queryStart);
        String query = queryStart < 0 ? "" : resolvedPath.substring(queryStart);
        String[] template = endpointPath.split("/", -1);
        String[] actual = pathPart.split("/", -1);
        if (template.length != actual.length) {
            return null;
        }
        boolean replaced = false;
        for (int i = 0; i < template.length; i++) {
            if (template[i].equals("{" + param + "}")) {
                actual[i] = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
                replaced = true;
            }
        }
        return replaced ? String.join("/", actual) + query : null;
    }

    private String removeQueryParam(String resolvedPath, String param) {
        int queryStart = resolvedPath.indexOf('?');
        if (queryStart < 0) {
            return resolvedPath;
        }
        String pathPart = resolvedPath.substring(0, queryStart);
        List<String> kept = new ArrayList<>();
        for (String pair : resolvedPath.substring(queryStart + 1).split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            String name = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
            if (!name.equals(param) && !name.equals(URLEncoder.encode(param, StandardCharsets.UTF_8))) {
                kept.add(pair);
            }
        }
        return kept.isEmpty() ? pathPart : pathPart + "?" + String.join("&", kept);
    }

    private String setQueryParam(String resolvedPath, String param, String value) {
        int queryStart = resolvedPath.indexOf('?');
        String pathPart = queryStart < 0 ? resolvedPath : resolvedPath.substring(0, queryStart);
        String query = queryStart < 0 ? "" : resolvedPath.substring(queryStart + 1);
        String encoded = URLEncoder.encode(param, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
        List<String> pairs = new ArrayList<>();
        boolean replaced = false;
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            String name = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
            if (name.equals(param) || name.equals(URLEncoder.encode(param, StandardCharsets.UTF_8))) {
                pairs.add(encoded);
                replaced = true;
            } else {
                pairs.add(pair);
            }
        }
        if (!replaced) {
            pairs.add(encoded);
        }
        return pathPart + "?" + String.join("&", pairs);
    }

    // ---- Tiện ích ----

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseObject(String json) {
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

    private String asText(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return String.valueOf(value);
    }

    private BigDecimal parseNumber(String text) {
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Đếm theo code point - emoji/ký tự ngoài BMP tính 1 ký tự, khớp cách người đọc spec hiểu "độ dài". */
    private int length(String text) {
        return text.codePointCount(0, text.length());
    }
}
