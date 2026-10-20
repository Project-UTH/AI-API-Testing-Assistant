package com.aiapitesting.backend.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Placeholder ngày/giờ động, thay LÚC CHẠY (UTC) để test hợp lệ không bao giờ "cũ" (vd hạn nộp {{today+30d}}).
 * Cú pháp: {{today}}, {{today+30d}}, {{today-1d}} (ngày, yyyy-MM-dd) và {{now}}, {{now+1h}}, {{now-15m}} (ISO có Z, cắt giây).
 * Đơn vị: d/h/m (today chỉ nhận d). Lưu nguyên token trong test case nên fingerprint không đổi theo ngày.
 */
public final class DynamicPlaceholderResolver {

    /** Tên bị giữ chỗ: không được dùng làm tham số path/dependency. */
    public static final java.util.Set<String> RESERVED_NAMES = java.util.Set.of("today", "now", "uuid", "rand", "runId");

    private static final Pattern TOKEN = Pattern.compile("\\{\\{(today|now)(?:([+-])(\\d{1,4})([dhm]))?}}");
    /** Token trông giống ngày động (bắt đầu bằng today/now) - để phát hiện cú pháp sai. */
    private static final Pattern LOOKS_DYNAMIC = Pattern.compile("\\{\\{\\s*(?:today|now)(?:[+-][^}]*)?\\s*}}");

    /** {{uuid}}, {{rand}}, {{runId}}, hoặc dạng có tên {{uuid:a}} (cùng tên = cùng giá trị trong 1 lần chạy test case). */
    private static final Pattern UNIQUE_TOKEN = Pattern.compile("\\{\\{(uuid|rand|runId)(?::(\\w{1,20}))?}}");

    private DynamicPlaceholderResolver() {
    }

    /**
     * Giá trị sinh MỖI LẦN CHẠY test case để dữ liệu tạo ra không đụng bản ghi cũ (email/mã duy nhất, lặp lại được):
     * {{uuid}} và {{rand}} cho giá trị mới mỗi lần xuất hiện, {{uuid:a}} dùng lại cùng giá trị trong cùng test case (path/header/body),
     * {{runId}} là 1 mã ngắn cố định trong lần chạy. Không thread-safe: mỗi lần chạy tạo 1 scope riêng.
     */
    public static final class RunScope {
        private final java.util.Map<String, String> named = new java.util.HashMap<>();
        private final String runId = java.util.UUID.randomUUID().toString().substring(0, 8);

        String valueOf(String kind, String name) {
            if (name != null) {
                return named.computeIfAbsent(kind + ":" + name, k -> fresh(kind));
            }
            return fresh(kind);
        }

        private String fresh(String kind) {
            return switch (kind) {
                case "uuid" -> java.util.UUID.randomUUID().toString();
                case "rand" -> String.valueOf(java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
                default -> runId;
            };
        }
    }

    public static String resolveUnique(String text, RunScope scope) {
        if (text == null || scope == null || !text.contains("{{")) {
            return text;
        }
        Matcher m = UNIQUE_TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(scope.valueOf(m.group(1), m.group(2))));
        }
        m.appendTail(out);
        return out.toString();
    }

    public static String resolve(String text) {
        return resolve(text, Clock.systemUTC());
    }

    public static String resolve(String text, Clock clock) {
        if (text == null || !text.contains("{{")) {
            return text;
        }
        Matcher m = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(valueOf(m, clock)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** @return token đầu tiên có dạng today/now nhưng sai cú pháp (vd {{today+5x}}, {{today+1h}}), hoặc null nếu hợp lệ. */
    public static String firstMalformed(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = LOOKS_DYNAMIC.matcher(text);
        while (m.find()) {
            String token = m.group();
            Matcher strict = TOKEN.matcher(token);
            if (!strict.matches() || ("today".equals(strict.group(1)) && strict.group(4) != null && !"d".equals(strict.group(4)))) {
                return token;
            }
        }
        return null;
    }

    private static String valueOf(Matcher m, Clock clock) {
        boolean isToday = "today".equals(m.group(1));
        long amount = m.group(3) == null ? 0 : Long.parseLong(m.group(3)) * ("-".equals(m.group(2)) ? -1 : 1);
        String unit = m.group(4);
        if (isToday) {
            return LocalDate.now(clock).plusDays("d".equals(unit) ? amount : 0).toString();
        }
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        Duration delta = unit == null ? Duration.ZERO : "d".equals(unit) ? Duration.ofDays(amount) : "h".equals(unit) ? Duration.ofHours(amount) : Duration.ofMinutes(amount);
        return now.plus(delta).toString();
    }
}
