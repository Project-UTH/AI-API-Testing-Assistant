package com.aiapitesting.backend.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 1 dòng trong feed Lịch sử tổng (Module 8) - gộp sự kiện "Sinh test case" + "Chạy test" của MỌI
 * project thuộc user hiện tại. Giống {@link HistoryEventResponse} (record phẳng, field null theo
 * loại sự kiện) nhưng có thêm context project/endpoint vì feed tổng không có chỗ "treo" context
 * như {@code EndpointHistoryResponse} đang làm ở bản lịch sử theo-project.
 */
public record HistoryFeedItemResponse(
        UUID id,
        String type, // "GENERATION" | "EXECUTION" | "BUG_REPORT_CREATED" | "BUG_REPORT_DELETED"
        Instant occurredAt,
        UUID projectId,
        String projectName,
        UUID endpointId,
        String endpointMethod,
        String endpointPath,
        // Chỉ có giá trị khi type = GENERATION
        Integer generatedCount,
        List<GenerationSnapshotItemResponse> snapshotItems,
        // Chỉ có giá trị khi type = EXECUTION
        UUID executionId,
        Integer selectedCount,
        Integer passCount,
        Integer failCount,
        Integer otherEndpointCount,
        // Chỉ có giá trị khi type = BUG_REPORT_CREATED | BUG_REPORT_DELETED
        String bugId,
        String bugSummary,
        // Chỉ có giá trị khi type = BUG_REPORT_CREATED (bug còn tồn tại) - "Xem chi tiết" dùng field
        // này để mở thẳng dialog sửa đúng bug đó; null ở BUG_REPORT_DELETED vì bug đã không còn.
        UUID bugReportId
) {
    public static HistoryFeedItemResponse generation(
            UUID id, Instant occurredAt, UUID projectId, String projectName,
            UUID endpointId, String endpointMethod, String endpointPath,
            int generatedCount, List<GenerationSnapshotItemResponse> snapshotItems
    ) {
        return new HistoryFeedItemResponse(
                id, "GENERATION", occurredAt, projectId, projectName, endpointId, endpointMethod, endpointPath,
                generatedCount, snapshotItems, null, null, null, null, null, null, null, null);
    }

    public static HistoryFeedItemResponse execution(
            UUID id, Instant occurredAt, UUID projectId, String projectName,
            UUID endpointId, String endpointMethod, String endpointPath,
            UUID executionId, int selectedCount, int passCount, int failCount, int otherEndpointCount
    ) {
        return new HistoryFeedItemResponse(
                id, "EXECUTION", occurredAt, projectId, projectName, endpointId, endpointMethod, endpointPath,
                null, null, executionId, selectedCount, passCount, failCount, otherEndpointCount, null, null, null);
    }

    public static HistoryFeedItemResponse bugReportEvent(
            UUID id, String type, Instant occurredAt, UUID projectId, String projectName,
            UUID endpointId, String endpointMethod, String endpointPath,
            String bugId, String bugSummary, UUID bugReportId
    ) {
        return new HistoryFeedItemResponse(
                id, type, occurredAt, projectId, projectName, endpointId, endpointMethod, endpointPath,
                null, null, null, null, null, null, null, bugId, bugSummary, bugReportId);
    }
}
