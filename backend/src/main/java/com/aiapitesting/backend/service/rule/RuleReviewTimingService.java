package com.aiapitesting.backend.service.rule;

import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.ReviewTimingRequest;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.ReviewTimingRow;
import com.aiapitesting.backend.entity.BusinessRule;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.RuleReviewTiming;
import com.aiapitesting.backend.entity.RuleStatus;
import com.aiapitesting.backend.exception.BusinessRuleException;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.repository.BusinessRuleRepository;
import com.aiapitesting.backend.repository.RuleReviewTimingRepository;
import com.aiapitesting.backend.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 17q G3.5 - ghi và xuất thời gian duyệt luật cho thực nghiệm E2. Giao diện chỉ gọi khi người dùng ĐÃ BẬT đo; backend vẫn kiểm: luật thuộc project,
 * quyết định khớp trạng thái thật của luật (chặn ghi rác), thời gian trong khoảng hợp lý. Dữ liệu xuất ẩn danh (không có người dùng, không có nội dung luật).
 */
@Service
@RequiredArgsConstructor
public class RuleReviewTimingService {

    /** Quá 1 giờ cho 1 luật là bỏ quên chứ không phải đang duyệt: từ chối thay vì làm lệch trung vị. */
    static final int MAX_FOCUSED_MS = 3_600_000;

    private final ProjectService projectService;
    private final BusinessRuleRepository ruleRepository;
    private final RuleReviewTimingRepository timingRepository;

    @Transactional
    public void record(UUID projectId, UUID ruleId, ReviewTimingRequest request) {
        Project project = projectService.getOwnedProject(projectId);
        if (request == null || request.decision() == null || request.focusedMs() == null) {
            throw new InvalidRequestException("Thiếu quyết định hoặc thời gian");
        }
        if (request.focusedMs() < 0 || request.focusedMs() > MAX_FOCUSED_MS) {
            throw new InvalidRequestException("Thời gian duyệt phải trong khoảng 0 đến 1 giờ");
        }
        BusinessRule rule = ruleRepository.findByIdAndProject(ruleId, project).orElseThrow(() -> BusinessRuleException.invalid("Không tìm thấy luật"));
        String decision = request.decision().trim().toUpperCase(java.util.Locale.ROOT);
        boolean consistent = switch (decision) {
            case RuleReviewTiming.APPROVED -> rule.getStatus() == RuleStatus.APPROVED;
            case RuleReviewTiming.REJECTED -> rule.getStatus() == RuleStatus.REJECTED;
            case RuleReviewTiming.EDITED -> rule.getStatus() == RuleStatus.PROPOSED;
            default -> throw new InvalidRequestException("Quyết định phải là APPROVED, REJECTED hoặc EDITED");
        };
        if (!consistent) {
            throw BusinessRuleException.stateInvalid("Quyết định không khớp trạng thái hiện tại của luật");
        }
        timingRepository.save(RuleReviewTiming.builder()
                .projectId(projectId).ruleId(ruleId).ruleSource(rule.getSource().name()).template(rule.getTemplate().name())
                .hadQuote(rule.getQuote() != null).decision(decision).focusedMs(request.focusedMs()).build());
    }

    /** Xuất ẩn danh: không có ruleId, người dùng hay nội dung - chỉ nguồn, mẫu, có trích dẫn, quyết định, thời gian và thứ tự. */
    @Transactional(readOnly = true)
    public List<ReviewTimingRow> export(UUID projectId) {
        projectService.getOwnedProject(projectId);
        return timingRepository.findAllByProjectIdOrderByCreatedAtAsc(projectId).stream()
                .map(t -> new ReviewTimingRow(t.getRuleSource(), t.getTemplate(), t.isHadQuote(), t.getDecision(), t.getFocusedMs(), t.getCreatedAt()))
                .toList();
    }
}
