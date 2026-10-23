package com.aiapitesting.backend.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Trạng thái 1 job sinh test kèm các bước (chỉ các bước mới hơn {@code afterSeq} cộng các bước còn RUNNING khi polling). */
public record GenerationJobResponse(
        UUID id, UUID projectId, String type, String status, boolean dryRun, int tokenCap, int tokensUsed, String errorMessage,
        Instant createdAt, Instant startedAt, Instant finishedAt, List<EndpointResult> results, List<Step> steps, int lastSeq,
        RulesJobResult rules
) {

    /** Kết quả cuối của từng endpoint: status CONFIRMED / ALREADY_HAS_BASELINE / NEEDS_HUMAN / FILLED / DRY_RUN / FAILED / GENERATED. */
    public record EndpointResult(UUID endpointId, String label, String status, String reason, String message, int tokens, int filled) {
    }

    public record Step(
            int seq, UUID endpointId, String endpointLabel, String phase, String actor, String status, String title, String detail,
            Integer httpStatus, Integer tokens, Instant startedAt, Instant finishedAt
    ) {
    }
}
