package com.aiapitesting.backend.service.ci;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Module 17 (17d) - giới hạn tần suất đơn giản cho /ci/** (cửa sổ cố định 1 phút, trong bộ nhớ, không thư viện ngoài). Hạn chế đã biết:
 * không chia sẻ giữa nhiều instance backend - đủ cho 1 instance (kiến trúc hiện tại); nhiều instance thì cần kho chung.
 */
@Component
public class CiRateLimiter {

    private static final long WINDOW_MILLIS = 60_000;
    private static final int MAX_KEYS = 10_000;

    private record Window(long startMillis, int count) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public CiRateLimiter() {
        this(Clock.systemUTC());
    }

    CiRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Ghi nhận 1 lần dùng khoá {@code key}; trả false khi đã vượt {@code maxPerMinute} trong cửa sổ hiện tại. */
    public boolean tryAcquire(String key, int maxPerMinute) {
        long now = clock.millis();
        if (windows.size() > MAX_KEYS) {
            windows.entrySet().removeIf(e -> now - e.getValue().startMillis() >= WINDOW_MILLIS); // dọn khoá cũ, tránh phình bộ nhớ
        }
        boolean[] allowed = {true};
        windows.compute(key, (k, w) -> {
            if (w == null || now - w.startMillis() >= WINDOW_MILLIS) {
                return new Window(now, 1);
            }
            if (w.count() >= maxPerMinute) {
                allowed[0] = false;
                return w;
            }
            return new Window(w.startMillis(), w.count() + 1);
        });
        return allowed[0];
    }
}
