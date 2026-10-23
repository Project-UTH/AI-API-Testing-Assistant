package com.aiapitesting.backend.dto.rule;

import com.aiapitesting.backend.entity.BusinessRule;
import com.aiapitesting.backend.entity.RuleRejectReason;
import com.aiapitesting.backend.entity.RuleSource;
import com.aiapitesting.backend.entity.RuleStatus;
import com.aiapitesting.backend.entity.RuleTemplate;
import com.aiapitesting.backend.service.rule.RuleParams;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 17q G3 - DTO của API luật nghiệp vụ (gom vào 1 file: các record nhỏ cùng chủ đề). */
public final class BusinessRuleDtos {

    private BusinessRuleDtos() {
    }

    /** @param approve true = tạo xong duyệt luôn (người nhập tự chịu trách nhiệm kỳ vọng) */
    public record CreateRequest(
            UUID endpointId, RuleTemplate template, RuleParams params, Integer expectedStatus, String rationale, Boolean approve
    ) {
    }

    /** Sửa luật: luật quay về PROPOSED (và test sinh từ luật cũ bị xoá) - phải duyệt lại. */
    public record UpdateRequest(RuleParams params, Integer expectedStatus, String rationale) {
    }

    /** @param conflictNote bắt buộc khi duyệt luật CONFLICT (ghi lý do vẫn duyệt dù mâu thuẫn spec) */
    public record ApproveRequest(Integer expectedStatus, String conflictNote) {
    }

    public record RejectRequest(RuleRejectReason reason, String note) {
    }

    /** @param ruleIds rỗng = mọi luật APPROVED của project (hoặc của endpointIds nếu có) */
    public record GenerateTestsRequest(List<UUID> ruleIds, List<UUID> endpointIds) {
    }

    public record RuleResponse(
            UUID id, String endpointKey, UUID endpointId, RuleTemplate template, RuleParams params, String sentence,
            Integer expectedStatus, RuleStatus status, RuleSource source, String rationale, String quote,
            RuleRejectReason rejectReason, String statusNote, UUID reviewedBy, Instant reviewedAt, long testCount,
            Instant createdAt, Instant updatedAt, Long version
    ) {
        public static RuleResponse from(BusinessRule rule, RuleParams params, String sentence, long testCount) {
            return new RuleResponse(rule.getId(), rule.getEndpointKey(), rule.getEndpointId(), rule.getTemplate(), params, sentence,
                    rule.getExpectedStatus(), rule.getStatus(), rule.getSource(), rule.getRationale(), rule.getQuote(),
                    rule.getRejectReason(), rule.getStatusNote(), rule.getReviewedBy(), rule.getReviewedAt(), testCount,
                    rule.getCreatedAt(), rule.getUpdatedAt(), rule.getVersion());
        }
    }

    public record NotGenerated(UUID ruleId, String sentence, String reason) {
    }

    public record GenerateTestsResponse(int createdCount, List<UUID> createdTestCaseIds, List<NotGenerated> notGenerated) {
    }

    public record DetectResponse(int proposedCount, int skippedExistingCount) {
    }
}
