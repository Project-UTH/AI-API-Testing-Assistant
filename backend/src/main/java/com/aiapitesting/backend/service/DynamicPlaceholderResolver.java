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
    public static final java.util.Set<String> RESERVED_NAMES = java.util.Set.of("today", "now");

    private static final Pattern TOKEN = Pattern.compile("\\{\\{(today|now)(?:([+-])(\\d{1,4})([dhm]))?}}");
    /** Token trông giống ngày động (bắt đầu bằng today/now) - để phát hiện cú pháp sai. */
    private static final Pattern LOOKS_DYNAMIC = Pattern.compile("\\{\\{\\s*(?:today|now)(?:[+-][^}]*)?\\s*}}");

    private DynamicPlaceholderResolver() {
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
