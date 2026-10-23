package com.aiapitesting.backend.service.rule;

import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.ApproveRequest;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.CreateRequest;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.RejectRequest;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.RuleResponse;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.UpdateRequest;
import com.aiapitesting.backend.entity.BusinessRule;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.RuleRejectReason;
import com.aiapitesting.backend.entity.RuleSource;
import com.aiapitesting.backend.entity.RuleStatus;
import com.aiapitesting.backend.entity.RuleTemplate;
import com.aiapitesting.backend.exception.BusinessRuleException;
import com.aiapitesting.backend.exception.EndpointNotFoundException;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.repository.BusinessRuleRepository;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.service.CurrentUserService;
import com.aiapitesting.backend.service.ProjectService;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 17q G3 - luật nghiệp vụ theo project: tạo/sửa/duyệt/loại/xoá. Luật người dùng nhập được kiểm TẤT ĐỊNH (tham số hợp lệ theo mẫu + spec,
 * không trùng ràng buộc spec, mâu thuẫn spec thì CONFLICT); luật hệ thống/AI đề xuất trùng spec thì lưu REJECTED (giữ để đo precision).
 * Duyệt = người chịu trách nhiệm kỳ vọng (human oracle). Controller mỏng, mọi nghiệp vụ ở đây.
 */
@Service
@RequiredArgsConstructor
public class BusinessRuleService {

    private static final int MAX_PAGE_SIZE = 200;

    private final ProjectService projectService;
    private final CurrentUserService currentUserService;
    private final EndpointRepository endpointRepository;
    private final BusinessRuleRepository ruleRepository;
    private final TestCaseChecklistPlanner planner;
    private final ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    // ---- đọc ----

    @Transactional(readOnly = true)
    public PageResponse<RuleResponse> list(UUID projectId, Integer page, Integer size, UUID endpointId, RuleStatus status, RuleSource source,
                                           RuleTemplate template) {
        Project project = projectService.getOwnedProject(projectId);
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size < 1 ? 50 : Math.min(size, MAX_PAGE_SIZE);
        var result = ruleRepository.findAll(BusinessRuleRepository.filter(project, null, endpointId, status, source, template),
                PageRequest.of(p, s, Sort.by("endpointKey").ascending().and(Sort.by("createdAt").ascending())));
        return PageResponse.from(result, this::toResponse);
    }

    @Transactional(readOnly = true)
    public RuleResponse get(UUID projectId, UUID ruleId) {
        return toResponse(owned(projectService.getOwnedProject(projectId), ruleId));
    }

    // ---- tạo/sửa ----

    @Transactional
    public RuleResponse create(UUID projectId, CreateRequest request) {
        Project project = projectService.getOwnedProject(projectId);
        if (request == null || request.endpointId() == null) {
            throw BusinessRuleException.invalid("Phải chọn endpoint");
        }
        Endpoint endpoint = endpointRepository.findByIdAndProject(request.endpointId(), project)
                .orElseThrow(() -> new EndpointNotFoundException("Không tìm thấy endpoint với id đã cho"));
        BusinessRule rule = build(project, endpoint, request.template(), request.params(), request.expectedStatus(), request.rationale(),
                RuleSource.USER);
        if (rule.getStatus() == RuleStatus.REJECTED) {
            // Người dùng tự nhập luật trùng ràng buộc spec: báo ngay thay vì lưu im lặng (khác luật hệ thống/AI đề xuất).
            throw BusinessRuleException.duplicatesSpec(rule.getStatusNote());
        }
        if (ruleRepository.existsByProjectAndFingerprint(project, rule.getFingerprint())) {
            throw BusinessRuleException.alreadyExists();
        }
        if (Boolean.TRUE.equals(request.approve())) {
            if (rule.getStatus() == RuleStatus.CONFLICT) {
                throw BusinessRuleException.stateInvalid("Luật mâu thuẫn spec: " + rule.getStatusNote()
                        + " - tạo luật rồi duyệt kèm ghi chú để xác nhận vẫn muốn dùng");
            }
            markApproved(rule);
        }
        return toResponse(ruleRepository.save(rule));
    }

    @Transactional
    public RuleResponse update(UUID projectId, UUID ruleId, UpdateRequest request) {
        Project project = projectService.getOwnedProject(projectId);
        BusinessRule rule = owned(project, ruleId);
        if (request == null) {
            throw BusinessRuleException.invalid("Thiếu nội dung cần sửa");
        }
        Endpoint endpoint = endpointOf(project, rule);
        BusinessRule rebuilt = build(project, endpoint, rule.getTemplate(), request.params(), request.expectedStatus(), request.rationale(),
                rule.getSource());
        if (rebuilt.getStatus() == RuleStatus.REJECTED) {
            throw BusinessRuleException.duplicatesSpec(rebuilt.getStatusNote());
        }
        ruleRepository.findByProjectAndFingerprint(project, rebuilt.getFingerprint())
                .filter(other -> !other.getId().equals(rule.getId()))
                .ifPresent(other -> {
                    throw BusinessRuleException.alreadyExists();
                });
        rule.setParamsJson(rebuilt.getParamsJson());
        rule.setFingerprint(rebuilt.getFingerprint());
        rule.setExpectedStatus(rebuilt.getExpectedStatus());
        rule.setRationale(rebuilt.getRationale());
        rule.setSpecHash(rebuilt.getSpecHash());
        // Sửa luật = người phải duyệt lại; test sinh từ phiên bản cũ bị xoá ở bước sinh test (G3.1 commit sinh test).
        rule.setStatus(rebuilt.getStatus());
        rule.setStatusNote(rebuilt.getStatusNote());
        rule.setRejectReason(null);
        rule.setReviewedBy(null);
        rule.setReviewedAt(null);
        return toResponse(ruleRepository.save(rule));
    }

    // ---- duyệt / loại / xoá ----

    @Transactional
    public RuleResponse approve(UUID projectId, UUID ruleId, ApproveRequest request) {
        Project project = projectService.getOwnedProject(projectId);
        BusinessRule rule = owned(project, ruleId);
        if (rule.getStatus() != RuleStatus.PROPOSED && rule.getStatus() != RuleStatus.CONFLICT) {
            throw BusinessRuleException.stateInvalid("Chỉ duyệt được luật đang chờ duyệt hoặc mâu thuẫn spec (hiện: " + rule.getStatus() + ")");
        }
        if (rule.getStatus() == RuleStatus.CONFLICT) {
            String note = request == null ? null : request.conflictNote();
            if (note == null || note.isBlank()) {
                throw BusinessRuleException.stateInvalid("Luật mâu thuẫn spec - phải ghi chú vì sao vẫn duyệt (conflictNote)");
            }
            rule.setStatusNote(truncate("Đã duyệt dù mâu thuẫn spec: " + note.trim()));
        }
        if (request != null && request.expectedStatus() != null) {
            rule.setExpectedStatus(validExpected(request.expectedStatus()));
        }
        markApproved(rule);
        return toResponse(ruleRepository.save(rule));
    }

    @Transactional
    public RuleResponse reject(UUID projectId, UUID ruleId, RejectRequest request) {
        Project project = projectService.getOwnedProject(projectId);
        BusinessRule rule = owned(project, ruleId);
        if (request == null || request.reason() == null || request.reason() == RuleRejectReason.DUPLICATE_OF_SPEC) {
            throw new InvalidRequestException("Phải chọn lý do loại luật (sai nghĩa / trùng / không áp dụng)");
        }
        if (rule.getStatus() == RuleStatus.REJECTED) {
            throw BusinessRuleException.stateInvalid("Luật đã bị loại rồi");
        }
        rule.setStatus(RuleStatus.REJECTED);
        rule.setRejectReason(request.reason());
        rule.setStatusNote(request.note() == null || request.note().isBlank() ? null : truncate(request.note().trim()));
        rule.setReviewedBy(currentUserService.getCurrentUser().getId());
        rule.setReviewedAt(Instant.now());
        return toResponse(ruleRepository.save(rule));
    }

    @Transactional
    public void delete(UUID projectId, UUID ruleId) {
        Project project = projectService.getOwnedProject(projectId);
        BusinessRule rule = owned(project, ruleId);
        if (rule.getSource() != RuleSource.USER || rule.getReviewedAt() != null) {
            throw BusinessRuleException.notDeletable();
        }
        ruleRepository.delete(rule);
    }

    // ---- dùng chung với phát hiện tự động / AI ----

    /**
     * Dựng luật từ tham số: kiểm tham số theo mẫu + spec, đối chiếu spec → trạng thái ban đầu. Luật trùng spec: nguồn USER được báo lỗi
     * (xem create); nguồn khác lưu REJECTED/DUPLICATE_OF_SPEC. STALE/CONFLICT lấy từ đối chiếu.
     */
    BusinessRule build(Project project, Endpoint endpoint, RuleTemplate template, RuleParams params, Integer expectedStatus,
                       String rationale, RuleSource source) {
        TestCaseChecklistPlanner.Spec spec = planner.readSpec(endpoint);
        RuleParams normalized = RuleParamsValidator.validate(template, params, spec, endpoint.getMethod());
        RuleParamsValidator.Check check = RuleParamsValidator.check(template, normalized, spec);
        RuleStatus status = switch (check.consistency()) {
            case OK -> RuleStatus.PROPOSED;
            case DUPLICATES_SPEC -> RuleStatus.REJECTED;
            case CONFLICT -> RuleStatus.CONFLICT;
            case STALE -> RuleStatus.STALE;
        };
        String json = write(normalized);
        String key = keyOf(endpoint);
        return BusinessRule.builder()
                .project(project)
                .endpointKey(key)
                .endpointId(endpoint.getId())
                .template(template)
                .paramsJson(json)
                .expectedStatus(expectedStatus == null ? null : validExpected(expectedStatus))
                .status(status)
                .source(source)
                .rationale(rationale == null || rationale.isBlank() ? null : rationale.trim())
                .rejectReason(status == RuleStatus.REJECTED ? RuleRejectReason.DUPLICATE_OF_SPEC : null)
                .statusNote(check.note() == null ? null : truncate(check.note()))
                .specHash(sha256(endpoint.getSchema() == null ? "" : endpoint.getSchema()))
                .fingerprint(sha256(key + "|" + template.name() + "|" + json))
                .build();
    }

    public static String keyOf(Endpoint endpoint) {
        return endpoint.getMethod().toUpperCase(Locale.ROOT) + " " + endpoint.getPath();
    }

    private void markApproved(BusinessRule rule) {
        rule.setStatus(RuleStatus.APPROVED);
        rule.setRejectReason(null);
        rule.setReviewedBy(currentUserService.getCurrentUser().getId());
        rule.setReviewedAt(Instant.now());
    }

    private Integer validExpected(Integer status) {
        if (status < 400 || status > 499) {
            throw BusinessRuleException.invalid("Mã kỳ vọng của luật phải là mã 4xx (hoặc để trống = bị từ chối)");
        }
        return status;
    }

    private BusinessRule owned(Project project, UUID ruleId) {
        return ruleRepository.findByIdAndProject(ruleId, project).orElseThrow(BusinessRuleException::notFound);
    }

    private Endpoint endpointOf(Project project, BusinessRule rule) {
        if (rule.getEndpointId() != null) {
            var found = endpointRepository.findByIdAndProject(rule.getEndpointId(), project);
            if (found.isPresent()) {
                return found.get();
            }
        }
        String[] parts = rule.getEndpointKey().split(" ", 2);
        return endpointRepository.findByProjectAndPathAndMethod(project, parts.length > 1 ? parts[1] : "", parts[0])
                .orElseThrow(() -> BusinessRuleException.stateInvalid("Endpoint của luật không còn trong project (luật lỗi thời)"));
    }

    RuleResponse toResponse(BusinessRule rule) {
        RuleParams params = read(rule.getParamsJson());
        return RuleResponse.from(rule, params, RuleSentence.of(rule.getTemplate(), params), 0);
    }

    RuleParams read(String json) {
        try {
            return objectMapper.readValue(json, RuleParams.class);
        } catch (Exception e) {
            throw new IllegalStateException("Tham số luật hỏng", e);
        }
    }

    private String write(RuleParams params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String text) {
        return text.length() <= 500 ? text : text.substring(0, 497) + "...";
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    List<BusinessRule> all(Project project) {
        return ruleRepository.findAllByProject(project);
    }
}
