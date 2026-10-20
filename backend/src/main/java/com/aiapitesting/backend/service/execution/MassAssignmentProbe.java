package com.aiapitesting.backend.service.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Module 17 (17b-2) - kiểm thử MASS ASSIGNMENT (OWASP API3/API6), tất định, 0 token, dùng cho MỌI REST API (không gắn với 1 ứng dụng).
 *
 * <p>Ý tưởng: lấy body hợp lệ của endpoint ghi (POST/PUT/PATCH), thêm vài field "đặc quyền" KHÔNG khai báo trong schema (role, isAdmin,
 * status, price, ownerId...) với giá trị đánh dấu (sentinel) duy nhất. API an toàn sẽ bỏ qua hoặc từ chối (400/422); API dễ bị tấn công
 * sẽ nhận và PHẢN CHIẾU lại sentinel trong response - đó là bằng chứng field đã được nhận.
 *
 * <p>Thuần tuý (không DB/mạng). Sentinel sinh tất định theo endpointId + tên field nên fingerprint test ổn định và không giống mẫu bí mật nào
 * ({@link SensitiveDataScanner}). Tên field ưu tiên cùng kiểu đặt tên (camelCase/snake_case) với field đã khai của endpoint.
 */
public final class MassAssignmentProbe {

    public static final String KIND = "MASS_ASSIGNMENT";
    public static final String RULE_ACCEPTED = "MASS_ASSIGNMENT_ACCEPTED";
    public static final String RULE_SERVER_ERROR = "MASS_ASSIGNMENT_SERVER_ERROR";
    static final int MAX_FIELDS = 8;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Kiểu giá trị của field đánh dấu. */
    public enum ValueType {
        STRING, BOOLEAN, NUMBER, STRING_LIST
    }

    /** @param name tên field tiêm vào; @param type kiểu giá trị; @param sentinel giá trị đánh dấu dạng chuỗi (số/bool cũng ở dạng chuỗi). */
    public record Field(String name, ValueType type, String sentinel) {
    }

    public enum Verdict {
        /** API bỏ qua hoặc từ chối field lạ. */
        PASS,
        /** API nhận field lạ (phản chiếu sentinel) hoặc lỗi 5xx. */
        FAIL,
        /** Không kết luận được (vd 401/403/404/409): probe chưa chạm tới logic cần kiểm. */
        INCONCLUSIVE
    }

    public record Outcome(Verdict verdict, List<Field> reflected, String detail) {
    }

    private record Candidate(String camelName, ValueType type) {
    }

    /** Field đặc quyền thường gặp, xếp theo mức nguy hiểm. Không có "id"/"password"/"token" (id tạo tác dụng phụ, 3 tên kia bị quét bí mật). */
    private static final List<Candidate> CANDIDATES = List.of(
            new Candidate("role", ValueType.STRING), new Candidate("isAdmin", ValueType.BOOLEAN),
            new Candidate("permissions", ValueType.STRING_LIST), new Candidate("ownerId", ValueType.STRING),
            new Candidate("userId", ValueType.STRING), new Candidate("status", ValueType.STRING),
            new Candidate("price", ValueType.NUMBER), new Candidate("verified", ValueType.BOOLEAN),
            // dự phòng khi các tên ở trên đã được khai trong schema (vượt giới hạn MAX_FIELDS thì bị bỏ)
            new Candidate("admin", ValueType.BOOLEAN), new Candidate("roles", ValueType.STRING_LIST),
            new Candidate("balance", ValueType.NUMBER), new Candidate("emailVerified", ValueType.BOOLEAN));

    private MassAssignmentProbe() {
    }

    /** Chọn field để tiêm: bỏ field đã khai (so khớp không phân biệt hoa thường, bỏ _ và -), cùng kiểu đặt tên, tối đa {@link #MAX_FIELDS}. */
    public static List<Field> chooseFields(Set<String> declaredNames, UUID endpointId) {
        Set<String> declared = new java.util.HashSet<>();
        boolean snake = false;
        for (String name : declaredNames) {
            declared.add(normalize(name));
            snake |= name.contains("_");
        }
        List<Field> fields = new ArrayList<>();
        for (Candidate candidate : CANDIDATES) {
            String name = snake ? toSnake(candidate.camelName()) : candidate.camelName();
            if (declared.contains(normalize(name)) || fields.size() >= MAX_FIELDS) {
                continue;
            }
            fields.add(new Field(name, candidate.type(), sentinel(candidate.type(), endpointId, name)));
        }
        return List.copyOf(fields);
    }

    /** Body mới = body gốc + field tiêm ở gốc object (hoặc ở phần tử đầu của mảng object); null nếu body không phải JSON object/mảng object. */
    public static String inject(String bodyJson, List<Field> fields) {
        if (bodyJson == null || bodyJson.isBlank() || fields.isEmpty()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(bodyJson.trim());
            ObjectNode target = null;
            if (root.isObject()) {
                target = (ObjectNode) root;
            } else if (root.isArray() && !root.isEmpty() && root.get(0).isObject()) {
                target = (ObjectNode) ((ArrayNode) root).get(0);
            }
            if (target == null) {
                return null;
            }
            for (Field field : fields) {
                target.set(field.name(), valueNode(field));
            }
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return null;
        }
    }

    /** Chấm kết quả probe theo mã trạng thái + body phản hồi THÔ (trước khi che). */
    public static Outcome evaluate(Integer status, String responseBody, List<Field> fields) {
        if (status == null) {
            return new Outcome(Verdict.INCONCLUSIVE, List.of(), "Không có phản hồi");
        }
        if (status >= 500) {
            return new Outcome(Verdict.FAIL, List.of(), "API trả lỗi máy chủ " + status + " khi nhận field không khai báo");
        }
        if (status == 400 || status == 422) {
            return new Outcome(Verdict.PASS, List.of(), "API từ chối field không khai báo (" + status + ")");
        }
        if (status < 200 || status > 299) {
            return new Outcome(Verdict.INCONCLUSIVE, List.of(), "Mã " + status + ": probe chưa chạm tới logic cần kiểm");
        }
        List<Field> reflected = reflectedFields(responseBody, fields);
        if (!reflected.isEmpty()) {
            return new Outcome(Verdict.FAIL, reflected, "API nhận và trả lại " + reflected.size() + " field không khai báo");
        }
        return new Outcome(Verdict.PASS, List.of(), "API bỏ qua field không khai báo");
    }

    private static List<Field> reflectedFields(String responseBody, List<Field> fields) {
        if (responseBody == null || responseBody.isBlank()) {
            return List.of();
        }
        List<Field> reflected = new ArrayList<>();
        JsonNode root = null;
        try {
            root = MAPPER.readTree(responseBody.trim());
        } catch (Exception ignored) {
            // không phải JSON: so khớp chuỗi sentinel trong text thô bên dưới
        }
        for (Field field : fields) {
            boolean found = root != null ? containsKeyWithValue(root, normalize(field.name()), field.sentinel(), 0)
                    : field.type() == ValueType.STRING && responseBody.contains(field.sentinel());
            if (found) {
                reflected.add(field);
            }
        }
        return reflected;
    }

    private static boolean containsKeyWithValue(JsonNode node, String normalizedKey, String sentinel, int depth) {
        if (depth > 12) {
            return false;
        }
        if (node.isObject()) {
            for (var entry : node.properties()) {
                if (normalize(entry.getKey()).equals(normalizedKey) && valueMatches(entry.getValue(), sentinel)) {
                    return true;
                }
                if (containsKeyWithValue(entry.getValue(), normalizedKey, sentinel, depth + 1)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                if (containsKeyWithValue(element, normalizedKey, sentinel, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean valueMatches(JsonNode value, String sentinel) {
        if (value.isTextual()) {
            return value.asText().contains(sentinel);
        }
        if (value.isArray()) {
            for (JsonNode element : value) {
                if (valueMatches(element, sentinel)) {
                    return true;
                }
            }
            return false;
        }
        return value.isValueNode() && value.asText().equals(sentinel);
    }

    private static JsonNode valueNode(Field field) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        return switch (field.type()) {
            case STRING -> f.textNode(field.sentinel());
            case BOOLEAN -> f.booleanNode(true);
            case NUMBER -> f.numberNode(Long.parseLong(field.sentinel()));
            case STRING_LIST -> f.arrayNode().add(field.sentinel());
        };
    }

    private static String sentinel(ValueType type, UUID endpointId, String name) {
        String hash = hash(endpointId + "|" + name);
        return switch (type) {
            case BOOLEAN -> "true";
            case NUMBER -> Long.toString(900_000_000L + Long.parseLong(hash.substring(0, 6), 16) % 99_000_000L);
            case STRING, STRING_LIST -> "zz_probe_" + hash.substring(0, 8);
        };
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    private static String toSnake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
