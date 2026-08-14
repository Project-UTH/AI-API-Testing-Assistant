package com.aiapitesting.backend.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 1 sự kiện trong timeline lịch sử kiểm thử của 1 endpoint (Module 8) - dùng chung 1 record phẳng
 * cho cả 2 loại (field không áp dụng để null), giống style TestExecutionResponse đang dùng, dự án
 * chưa dùng kiểu polymorphic Jackson ở đâu khác nên không cần thêm pattern đó riêng cho DTO này.
 */
public record HistoryEventResponse(
        UUID id,
        String type, // "GENERATION" | "EXECUTION"
        Instant occurredAt,
        // Chỉ có giá trị khi type = GENERATION
        Integer generatedCount,
        List<GenerationSnapshotItemResponse> snapshotItems,
        // Chỉ có giá trị khi type = EXECUTION
        UUID executionId,
        Integer selectedCount,
        Integer passCount,
        Integer failCount,
        Integer otherEndpointCount
) {
    public static HistoryEventResponse generation(UUID id, Instant occurredAt, int generatedCount, List<GenerationSnapshotItemResponse> snapshotItems) {
        return new HistoryEventResponse(id, "GENERATION", occurredAt, generatedCount, snapshotItems, null, null, null, null, null);
    }

    public static HistoryEventResponse execution(
            UUID id, Instant occurredAt, UUID executionId, int selectedCount, int passCount, int failCount, int otherEndpointCount
    ) {
        return new HistoryEventResponse(id, "EXECUTION", occurredAt, null, null, executionId, selectedCount, passCount, failCount, otherEndpointCount);
    }
}
