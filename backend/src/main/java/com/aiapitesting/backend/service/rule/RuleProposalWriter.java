package com.aiapitesting.backend.service.rule;

import com.aiapitesting.backend.entity.BusinessRule;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.RuleSource;
import com.aiapitesting.backend.entity.RuleStatus;
import com.aiapitesting.backend.exception.BusinessRuleException;
import com.aiapitesting.backend.repository.BusinessRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 17q G3.3 - lưu luật AI đề xuất SAU khi đã qua {@link RuleExtractionVerifier}. Mỗi endpoint tối đa {@code maxPerEndpoint} luật (ưu tiên luật có
 * trích dẫn) và tối đa {@code inferredCapPerEndpoint} luật GIẢ ĐỊNH. Luật trùng fingerprint đã có (kể cả luật đã bị loại) bị bỏ qua; luật trùng
 * ràng buộc spec được lưu REJECTED/DUPLICATE_OF_SPEC (giữ để đo precision); luật mâu thuẫn spec lưu CONFLICT.
 */
@Service
@RequiredArgsConstructor
public class RuleProposalWriter {

    public static final int DEFAULT_MAX_PER_ENDPOINT = 8;
    public static final int INFERRED_CAP_PER_ENDPOINT = 3;

    private final BusinessRuleService ruleService;
    private final BusinessRuleRepository ruleRepository;

    /** Số liệu của 1 lần ghi, dùng cho bước giám sát và kết quả job. */
    public record Outcome(
            int fromDocument, int fromInstruction, int inferred, int rejectedAsDuplicateOfSpec, int conflicts, int skippedExisting, int skippedOverCap,
            Map<RuleExtractionVerifier.DropReason, Integer> dropped
    ) {
        public int proposed() {
            return fromDocument + fromInstruction + inferred + conflicts;
        }
    }

    @Transactional
    public Outcome write(Project project, Map<String, Endpoint> endpointsByKey, List<RuleExtractionVerifier.Verdict> verdicts, UUID jobId,
                         int maxPerEndpoint) {
        return write(project, endpointsByKey, verdicts, jobId, maxPerEndpoint, INFERRED_CAP_PER_ENDPOINT);
    }

    /** Làn "AI gợi ý thêm" không đặt trần số mục: giới hạn duy nhất là ngân sách token do người dùng đặt. */
    @Transactional
    public Outcome writeSuggestions(Project project, Map<String, Endpoint> endpointsByKey, List<RuleExtractionVerifier.Verdict> verdicts, UUID jobId) {
        return write(project, endpointsByKey, verdicts, jobId, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Transactional
    public Outcome write(Project project, Map<String, Endpoint> endpointsByKey, List<RuleExtractionVerifier.Verdict> verdicts, UUID jobId,
                         int maxPerEndpoint, int inferredCap) {
        Map<RuleExtractionVerifier.DropReason, Integer> dropped = new EnumMap<>(RuleExtractionVerifier.DropReason.class);
        Map<String, List<RuleExtractionVerifier.Verdict>> byEndpoint = new HashMap<>();
        for (RuleExtractionVerifier.Verdict v : verdicts) {
            if (v.dropped() != null) {
                dropped.merge(v.dropped(), 1, Integer::sum);
            } else {
                byEndpoint.computeIfAbsent(v.endpointKey(), k -> new ArrayList<>()).add(v);
            }
        }
        int doc = 0;
        int instruction = 0;
        int inferred = 0;
        int dupSpec = 0;
        int conflicts = 0;
        int existing = 0;
        int overCap = 0;
        for (Map.Entry<String, List<RuleExtractionVerifier.Verdict>> entry : byEndpoint.entrySet()) {
            Endpoint endpoint = endpointsByKey.get(entry.getKey());
            if (endpoint == null) {
                continue;
            }
            List<RuleExtractionVerifier.Verdict> ordered = new ArrayList<>(entry.getValue());
            // Có trích dẫn trước, GIẢ ĐỊNH sau; giữ thứ tự AI trả trong cùng nhóm.
            ordered.sort(Comparator.comparingInt(v -> v.hasQuote() ? 0 : 1));
            int kept = 0;
            int keptInferred = 0;
            for (RuleExtractionVerifier.Verdict v : ordered) {
                boolean inferredRule = v.source() == RuleSource.INFERRED;
                if (kept >= maxPerEndpoint || (inferredRule && keptInferred >= inferredCap)) {
                    overCap++;
                    continue;
                }
                BusinessRule rule;
                try {
                    rule = ruleService.build(project, endpoint, v.template(), v.params(), v.expectedStatus(), v.rationale(), v.source());
                } catch (BusinessRuleException e) {
                    dropped.merge(RuleExtractionVerifier.DropReason.INVALID_PARAMS, 1, Integer::sum);
                    continue;
                }
                if (ruleRepository.existsByProjectAndFingerprint(project, rule.getFingerprint())) {
                    existing++;
                    continue;
                }
                rule.setQuote(v.quote());
                rule.setQuoteOffset(v.quoteOffset());
                rule.setDocumentId(v.documentId());
                rule.setExtractionJobId(jobId);
                if (inferredRule) {
                    rule.setSuggestedStatus(v.suggestedStatus());
                }
                if (v.note() != null && rule.getStatusNote() == null) {
                    rule.setStatusNote(v.note());
                }
                try {
                    ruleRepository.save(rule);
                } catch (DataIntegrityViolationException e) {
                    existing++; // chạy đua với lần trích khác cùng lúc
                    continue;
                }
                kept++;
                if (rule.getStatus() == RuleStatus.REJECTED) {
                    dupSpec++;
                } else if (rule.getStatus() == RuleStatus.CONFLICT) {
                    conflicts++;
                } else if (inferredRule) {
                    inferred++;
                    keptInferred++;
                } else if (v.source() == RuleSource.DOC) {
                    doc++;
                } else {
                    instruction++;
                }
            }
        }
        return new Outcome(doc, instruction, inferred, dupSpec, conflicts, existing, overCap, dropped);
    }
}
