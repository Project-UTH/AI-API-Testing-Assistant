package com.aiapitesting.backend.service.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

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
    static final int MAX_DEPTH = 32;
    /** Giá trị nhạy cảm bị thay bằng chuỗi này trong body lưu lại. */
    public static final String MASK_MARKER = "***ĐÃ CHE***";
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
     * Kết quả quét + che.
     *
     * @param maskedBody      body JSON đã thay giá trị nhạy cảm bằng {@link #MASK_MARKER}; null = không có gì để che (hoặc không quét được)
     * @param sensitiveValues giá trị thật đã bị che - CHỈ để dùng trong bộ nhớ (che nốt chỗ khác như kết quả assertion), không được lưu/log
     */
    public record Scan(List<Finding> findings, String maskedBody, Set<String> sensitiveValues) {
        static final Scan EMPTY = new Scan(List.of(), null, Set.of());

        @Override
        public String toString() {
            return "Scan[findings=" + findings.size() + "]";
        }
    }

    /** Mỗi vị trí cần che + giá trị thật tại đó. */
    private record Ctx(boolean authEndpoint, Set<String> echoed, Map<String, Finding> found, List<Runnable> maskers, Set<String> values) {
    }

    /**
     * @param endpointPath    đường dẫn spec của endpoint (nhận diện endpoint xác thực)
     * @param requiresAuth    endpoint có yêu cầu xác thực không (POST công khai thường là đăng ký/đăng nhập)
     * @param requestBodyJson body đã gửi (để bỏ qua giá trị chỉ phản chiếu lại input), có thể null
     */
    public static List<Finding> scan(String endpointPath, String method, boolean requiresAuth, String requestBodyJson, String responseBody) {
        return scanAndMask(endpointPath, method, requiresAuth, requestBodyJson, responseBody).findings();
    }

    /** Quét như {@link #scan} và đồng thời dựng body đã che MỌI vị trí nhạy cảm (không bị giới hạn bởi MAX_FINDINGS). */
    public static Scan scanAndMask(String endpointPath, String method, boolean requiresAuth, String requestBodyJson, String responseBody) {
        if (responseBody == null || responseBody.isBlank() || responseBody.length() > MAX_BODY_CHARS) {
            return Scan.EMPTY;
        }
        String trimmed = responseBody.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return Scan.EMPTY;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(trimmed);
        } catch (Exception e) {
            return Scan.EMPTY;
        }
        boolean authEndpoint = (endpointPath != null && AUTH_PATH.matcher(endpointPath).find())
                || ("POST".equalsIgnoreCase(method) && !requiresAuth);
        Set<String> echoed = valuesOf(requestBodyJson);

        Ctx ctx = new Ctx(authEndpoint, echoed, new LinkedHashMap<>(), new ArrayList<>(), new java.util.HashSet<>()); // khoá (path|rule): mảng nhiều phần tử chỉ báo 1 lần
        walk(root, "", 0, ctx);
        if (ctx.maskers().isEmpty()) {
            return new Scan(List.copyOf(ctx.found().values()), null, Set.of());
        }
        ctx.maskers().forEach(Runnable::run);
        String masked;
        try {
            masked = MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            masked = null;
        }
        return new Scan(List.copyOf(ctx.found().values()), masked, Set.copyOf(ctx.values()));
    }

    private static void walk(JsonNode node, String path, int depth, Ctx ctx) {
        if (depth > MAX_DEPTH) {
            return;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            object.properties().forEach(entry -> {
                String key = entry.getKey();
                String childPath = path.isEmpty() ? key : path + "." + key;
                JsonNode value = entry.getValue();
                if (value.isTextual()) {
                    if (inspect(key, childPath, value.asText(), ctx)) {
                        ctx.values().add(value.asText());
                        ctx.maskers().add(() -> object.set(key, TextNode.valueOf(MASK_MARKER)));
                    }
                } else {
                    walk(value, childPath, depth + 1, ctx);
                }
            });
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                JsonNode element = array.get(i);
                int index = i;
                if (element.isTextual()) {
                    if (inspect("", path + "[]", element.asText(), ctx)) {
                        ctx.values().add(element.asText());
                        ctx.maskers().add(() -> array.set(index, TextNode.valueOf(MASK_MARKER)));
                    }
                } else {
                    walk(element, path + "[]", depth + 1, ctx);
                }
            }
        }
    }

    /** @return true nếu giá trị này nhạy cảm (cần che); finding chỉ được ghi khi chưa vượt MAX_FINDINGS. */
    private static boolean inspect(String fieldName, String path, String value, Ctx ctx) {
        boolean authEndpoint = ctx.authEndpoint();
        Map<String, Finding> found = ctx.found();
        if (value == null || ctx.echoed().contains(value)) {
            return false;
        }
        String name = fieldName.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");

        // 1) Theo GIÁ TRỊ (độ chính xác cao).
        String valueRule = valueRule(value);
        if (valueRule != null) {
            boolean tokenLike = "JWT".equals(valueRule);
            if (!(tokenLike && authEndpoint)) {
                add(found, path, "VALUE_" + valueRule, severityOfValue(valueRule), "Giá trị có dạng " + describe(valueRule) + " (dài " + value.length() + " ký tự)");
                return true;
            }
        }
        // 2) Theo TÊN field (chỉ khi giá trị là chuỗi đủ dài, không phải ký hiệu che).
        if (name.isEmpty() || value.length() < MIN_VALUE_LENGTH || MASKS.contains(value.trim().toLowerCase(Locale.ROOT))) {
            return false;
        }
        boolean contains = NAME_CONTAINS.stream().anyMatch(name::contains);
        if (contains || NAME_EXACT.contains(name)) {
            add(found, path, "FIELD_" + (name.contains("password") || name.contains("passwd") || name.equals("pwd") ? "PASSWORD"
                    : name.contains("key") || name.equals("apikey") || name.equals("accesskey") ? "KEY" : "SECRET"),
                    Severity.HIGH, "Tên field gợi ý dữ liệu nhạy cảm (kiểu chuỗi, dài " + value.length() + " ký tự)");
            return true;
        } else if (TOKEN_NAMES.contains(name) && !authEndpoint) {
            add(found, path, "FIELD_TOKEN", Severity.MEDIUM, "Tên field là token (kiểu chuỗi, dài " + value.length() + " ký tự) ở endpoint không phải đăng nhập");
            return true;
        }
        return false;
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
        if (found.size() >= MAX_FINDINGS) {
            return; // vượt giới hạn cảnh báo: vẫn che giá trị (caller), chỉ không ghi thêm finding
        }
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
