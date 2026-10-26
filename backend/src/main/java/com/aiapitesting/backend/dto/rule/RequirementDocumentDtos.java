package com.aiapitesting.backend.dto.rule;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 17q G3.3 - DTO của API tài liệu nghiệp vụ. */
public final class RequirementDocumentDtos {

    private RequirementDocumentDtos() {
    }

    /** @param warnings cảnh báo bí mật (số dòng + mã quy tắc, KHÔNG chứa giá trị); bản gửi AI luôn được che */
    public record DocumentResponse(UUID id, String fileName, long sizeBytes, int lineCount, boolean active, Instant createdAt, List<Warning> warnings,
                                   List<String> forEndpoints) {
        public record Warning(int line, String rule) {
        }
    }

    /** 1 dòng lịch sử: tài liệu đã được gửi cho AI để lập checklist của endpoint này (lần gần nhất, số lần). */
    public record SetEndpointsRequest(List<String> endpointKeys) {
    }

    public record UsageEntry(UUID documentId, String fileName, boolean documentActive, String endpointKey, Instant lastUsedAt, long timesUsed) {
    }

    public record ContentResponse(UUID id, String fileName, String content) {
    }
}
