package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.entity.FailureCause;
import com.aiapitesting.backend.entity.TestResultStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * So sánh 2 lượt chạy (Module 17 - 17c). KHÔNG chứa response body hay token. {@code summary} luôn đủ cho mọi trang để giao
 * diện không phải gọi thêm; {@code items} chỉ gồm nhóm được hỏi (mặc định mọi nhóm trừ STILL_PASSING), đã phân trang.
 */
public record ExecutionCompareResponse(
        ExecutionRef base, ExecutionRef target, Summary summary, PageResponse<Item> items
) {

    public record ExecutionRef(UUID id, Instant startedAt, ExecutionStatus status, String targetBaseUrl) {
    }

    /** @param environmentChanged Target Base URL hoặc mốc đổi token khác nhau giữa 2 lượt - giải thích cho nhiều thay đổi cùng lúc. */
    public record Summary(
            long regressed, long fixed, long stillFailing, long changedTest, long unreliable, long inconclusive,
            long onlyInTarget, long onlyInBase, long stillPassing, boolean environmentChanged
    ) {
    }

    public record Item(
            UUID testCaseId, String testCaseName, UUID endpointId, String endpointMethod, String endpointPath,
            ExecutionDiffCategory category,
            TestResultStatus baseStatus, TestResultStatus targetStatus,
            Integer baseResponseStatus, Integer targetResponseStatus,
            FailureCause baseFailureCause, FailureCause targetFailureCause,
            String note
    ) {
    }
}
