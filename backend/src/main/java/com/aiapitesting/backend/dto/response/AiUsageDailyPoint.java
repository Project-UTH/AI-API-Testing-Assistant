package com.aiapitesting.backend.dto.response;

import java.time.LocalDate;

/** 1 ngày trong biểu đồ usage AI (Module 11) - luôn có đủ mọi ngày trong khoảng, kể cả ngày không dùng AI (totalTokens=0), để biểu đồ không bị đứt quãng. */
public record AiUsageDailyPoint(LocalDate date, long totalTokens, long callCount) {
}
