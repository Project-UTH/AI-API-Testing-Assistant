package com.aiapitesting.backend.service.rule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 17q G3.3 - phát hiện/che bí mật trong VĂN BẢN TỰ DO (tài liệu nghiệp vụ). SensitiveDataScanner chỉ quét JSON nên không dùng lại được.
 * Tất định, chỉ đọc: {@link #scan} trả số dòng + mã quy tắc (KHÔNG chứa giá trị bí mật); {@link #mask} thay mọi khớp bằng {@link #MASK} -
 * bản đã che là bản DUY NHẤT gửi cho AI. Kiểm chứng đoạn trích vẫn chạy trên bản gốc đã lưu nên offset không lệch.
 */
public final class TextSecretScanner {

    public static final String MASK = "[ĐÃ CHE]";

    private static final Map<String, Pattern> RULES = new LinkedHashMap<>();

    static {
        RULES.put("JWT", Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"));
        RULES.put("BCRYPT_HASH", Pattern.compile("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{50,}"));
        RULES.put("PRIVATE_KEY", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"));
        RULES.put("AWS_ACCESS_KEY", Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"));
        RULES.put("PREFIXED_API_KEY", Pattern.compile("\\b(?:sk|pk|rk|ghp|gho|xox[abp])[-_][A-Za-z0-9_-]{16,}"));
        RULES.put("URL_CREDENTIALS", Pattern.compile("(?<=://)[^\\s/:@]+:[^\\s/@]{3,}(?=@)"));
        RULES.put("ASSIGNED_SECRET", Pattern.compile(
                "(?i)(?<=\\b(?:password|passwd|secret|api[_-]?key|token|authorization)\\s{0,3}[:=]\\s{0,3})[\"']?[^\\s\"']{8,}"));
    }

    private TextSecretScanner() {
    }

    /** 1 phát hiện: số dòng (từ 1) + mã quy tắc. Không bao giờ chứa giá trị. */
    public record Finding(int line, String rule) {
    }

    public static List<Finding> scan(String text) {
        List<Finding> findings = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return findings;
        }
        for (Map.Entry<String, Pattern> rule : RULES.entrySet()) {
            Matcher matcher = rule.getValue().matcher(text);
            while (matcher.find()) {
                findings.add(new Finding(lineOf(text, matcher.start()), rule.getKey()));
                if (findings.size() >= 50) {
                    return findings;
                }
            }
        }
        findings.sort(java.util.Comparator.comparingInt(Finding::line).thenComparing(Finding::rule));
        return findings;
    }

    public static String mask(String text) {
        if (text == null) {
            return null;
        }
        String masked = text;
        for (Pattern pattern : RULES.values()) {
            masked = pattern.matcher(masked).replaceAll(Matcher.quoteReplacement(MASK));
        }
        return masked;
    }

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
