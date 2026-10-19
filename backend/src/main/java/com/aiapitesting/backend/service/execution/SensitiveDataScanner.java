package com.aiapitesting.backend.service.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Module 17 (17b) - quét TẤT ĐỊNH response JSON tìm dữ liệu nhạy cảm bị trả ra (OWASP API3 Excessive Data Exposure): field tên
 * password/secret/privateKey..., giá trị trông như JWT/bcrypt/khoá PEM/khoá AWS. CHỈ là cảnh báo đi kèm kết quả (không làm test Fail).
 * KHÔNG BAO GIỜ lưu hay trả giá trị thật: mỗi phát hiện chỉ có đường dẫn, mã quy tắc, mức độ và mô tả không chứa giá trị.
 *
 * <p>Giảm false positive: tên field khớp CHÍNH XÁC (hoặc chứa các cụm đặc trưng như password/secret), không khớp chuỗi con "token"/"hash"
 * (tránh tokenType, expiresIn, fileHash...); giá trị phải là chuỗi đủ dài và không phải ký hiệu che; endpoint đăng nhập/làm mới được phép trả
 * token; giá trị chỉ phản chiếu lại đúng input của request thì bỏ qua. Password/secret/khoá riêng/bcrypt/PEM thì KHÔNG bao giờ được bỏ qua.
 */
public final class SensitiveDataScanner {

    public enum Severity {
        HIGH, MEDIUM
    }

    /** @param path đường dẫn JSON, mảng viết gọn "items[].password"; không chứa giá trị. */
    public record Finding(String path, String rule, Severity severity, String detail) {
    }

    static final int MAX_FINDINGS = 20;
    static final int MAX_DEPTH = 8;
    static final int MAX_BODY_CHARS = 200_000;
    private static final int MIN_VALUE_LENGTH = 6;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Chứa chuỗi con (đã chuẩn hoá: chữ thường, bỏ _ và -) → luôn nhạy cảm. */
    private static final List<String> NAME_CONTAINS = List.of(
            "password", "passwd", "pwhash", "privatekey", "secretkey", "clientsecret", "mfasecret");
    /** Khớp CHÍNH XÁC tên đã chuẩn hoá. */
    private static final Set<String> NAME_EXACT = Set.of(
            "pwd", "salt", "apikey", "accesskey", "secret", "ssn", "creditcard", "cardnumber", "cvv", "cvc", "otp");
    /** Nhóm token: được phép trả ở endpoint đăng nhập/làm mới. */
    private static final Set<String> TOKEN_NAMES = Set.of("token", "accesstoken", "refreshtoken", "idtoken");

    private static final Pattern JWT = Pattern.compile("eyJ[\\w-]{10,}\\.eyJ[\\w-]{10,}\\.[\\w-]*");
    private static final Pattern BCRYPT = Pattern.compile("\\$2[aby]\\$\\d\\d\\$[./A-Za-z0-9]{53}");
    private static final Pattern PEM = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");
    private static final Pattern AWS = Pattern.compile("AKIA[0-9A-Z]{16}");
    private static final Pattern PREFIXED_KEY = Pattern.compile("(sk_live_|ghp_|xox[bp]-)[A-Za-z0-9_-]{8,}");
    private static final Pattern AUTH_PATH = Pattern.compile("(?i)login|signin|sign-in|auth|token|oauth|refresh|register|signup|session");
    private static final Set<String> MASKS = Set.of("***", "******", "[redacted]", "redacted", "null", "undefined", "xxxxxx");

    private SensitiveDataScanner() {
    }

    /**
     * @param endpointPath    đường dẫn spec của endpoint (nhận diện endpoint xác thực)
     * @param requiresAuth    endpoint có yêu cầu xác thực không (POST công khai thường là đăng ký/đăng nhập)
     * @param requestBodyJson body đã gửi (để bỏ qua giá trị chỉ phản chiếu lại input), có thể null
     */
    public static List<Finding> scan(String endpointPath, String method, boolean requiresAuth, String requestBodyJson, String responseBody) {
        if (responseBody == null || responseBody.isBlank() || responseBody.length() > MAX_BODY_CHARS) {
            return List.of();
        }
        String trimmed = responseBody.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return List.of();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(trimmed);
        } catch (Exception e) {
            return List.of();
        }
        boolean authEndpoint = (endpointPath != null && AUTH_PATH.matcher(endpointPath).find())
                || ("POST".equalsIgnoreCase(method) && !requiresAuth);
        Set<String> echoed = valuesOf(requestBodyJson);

        Map<String, Finding> found = new LinkedHashMap<>(); // khoá (path|rule): mảng nhiều phần tử chỉ báo 1 lần
        walk(root, "", 0, authEndpoint, echoed, found);
        return List.copyOf(found.values());
    }

    private static void walk(JsonNode node, String path, int depth, boolean authEndpoint, Set<String> echoed, Map<String, Finding> found) {
        if (depth > MAX_DEPTH || found.size() >= MAX_FINDINGS) {
            return;
        }
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                String childPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                JsonNode value = entry.getValue();
                if (value.isTextual()) {
                    inspect(entry.getKey(), childPath, value.asText(), authEndpoint, echoed, found);
                } else {
                    walk(value, childPath, depth + 1, authEndpoint, echoed, found);
                }
            });
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                if (element.isTextual()) {
                    inspect("", path + "[]", element.asText(), authEndpoint, echoed, found);
                } else {
                    walk(element, path + "[]", depth + 1, authEndpoint, echoed, found);
                }
            }
        }
    }

    private static void inspect(String fieldName, String path, String value, boolean authEndpoint, Set<String> echoed, Map<String, Finding> found) {
        if (found.size() >= MAX_FINDINGS || value == null || echoed.contains(value)) {
            return;
        }
        String name = fieldName.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");

        // 1) Theo GIÁ TRỊ (độ chính xác cao).
        String valueRule = valueRule(value);
        if (valueRule != null) {
            boolean tokenLike = "JWT".equals(valueRule);
            if (!(tokenLike && authEndpoint)) {
                add(found, path, "VALUE_" + valueRule, severityOfValue(valueRule), "Giá trị có dạng " + describe(valueRule) + " (dài " + value.length() + " ký tự)");
                return;
            }
        }
        // 2) Theo TÊN field (chỉ khi giá trị là chuỗi đủ dài, không phải ký hiệu che).
        if (name.isEmpty() || value.length() < MIN_VALUE_LENGTH || MASKS.contains(value.trim().toLowerCase(Locale.ROOT))) {
            return;
        }
        boolean contains = NAME_CONTAINS.stream().anyMatch(name::contains);
        if (contains || NAME_EXACT.contains(name)) {
            add(found, path, "FIELD_" + (name.contains("password") || name.contains("passwd") || name.equals("pwd") ? "PASSWORD"
                    : name.contains("key") || name.equals("apikey") || name.equals("accesskey") ? "KEY" : "SECRET"),
                    Severity.HIGH, "Tên field gợi ý dữ liệu nhạy cảm (kiểu chuỗi, dài " + value.length() + " ký tự)");
        } else if (TOKEN_NAMES.contains(name) && !authEndpoint) {
            add(found, path, "FIELD_TOKEN", Severity.MEDIUM, "Tên field là token (kiểu chuỗi, dài " + value.length() + " ký tự) ở endpoint không phải đăng nhập");
        }
    }

    private static String valueRule(String value) {
        if (value.length() < 20) {
            return null;
        }
        if (BCRYPT.matcher(value).find()) {
            return "BCRYPT";
        }
        if (PEM.matcher(value).find()) {
            return "PEM_KEY";
        }
        if (AWS.matcher(value).find()) {
            return "AWS_KEY";
        }
        if (PREFIXED_KEY.matcher(value).find()) {
            return "API_KEY";
        }
        return JWT.matcher(value).find() ? "JWT" : null;
    }

    private static Severity severityOfValue(String rule) {
        return "JWT".equals(rule) ? Severity.MEDIUM : Severity.HIGH;
    }

    private static String describe(String rule) {
        return switch (rule) {
            case "BCRYPT" -> "băm mật khẩu (bcrypt)";
            case "PEM_KEY" -> "khoá riêng PEM";
            case "AWS_KEY" -> "khoá truy cập AWS";
            case "API_KEY" -> "khoá API có tiền tố đặc trưng";
            default -> "JWT";
        };
    }

    private static void add(Map<String, Finding> found, String path, String rule, Severity severity, String detail) {
        found.putIfAbsent(path + "|" + rule, new Finding(path, rule, severity, detail));
    }

    /** Mọi giá trị chuỗi trong body request (để biết API chỉ phản chiếu lại input, không phải dữ liệu lưu). */
    private static Set<String> valuesOf(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            Set<String> values = new java.util.HashSet<>();
            collect(MAPPER.readTree(json), values, 0);
            return values;
        } catch (Exception e) {
            return Set.of();
        }
    }

    private static void collect(JsonNode node, Set<String> out, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        if (node.isTextual()) {
            out.add(node.asText());
        } else if (node.isContainerNode()) {
            node.forEach(child -> collect(child, out, depth + 1));
        }
    }

    /** Gom mọi finding thành danh sách chuỗi ổn định cho báo cáo/log (không chứa giá trị). */
    static List<String> describeAll(List<Finding> findings) {
        List<String> lines = new ArrayList<>();
        findings.forEach(f -> lines.add(f.path() + " [" + f.rule() + "]"));
        return lines;
    }
}
