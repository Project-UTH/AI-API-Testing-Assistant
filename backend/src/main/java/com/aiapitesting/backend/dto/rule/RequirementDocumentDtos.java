package com.aiapitesting.backend.dto.rule;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 17q G3.3 - DTO của API tài liệu nghiệp vụ. */
public final class RequirementDocumentDtos {

    private RequirementDocumentDtos() {
    }

    /** @param warnings cảnh báo bí mật (số dòng + mã quy tắc, KHÔNG chứa giá trị); bản gửi AI luôn được che */
    public record DocumentResponse(UUID id, String fileName, long sizeBytes, int lineCount, boolean active, Instant createdAt, List<Warning> warnings) {
        public record Warning(int line, String rule) {
        }
    }

    public record ContentResponse(UUID id, String fileName, String content) {
    }
}
