package com.aiapitesting.backend.service.rule;

import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.ApproveBatchRequest;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.ApproveBatchResponse;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.ApproveRequest;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.NotApproved;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.CreateRequest;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.DetectResponse;
import com.aiapitesting.backend.dto.rule.BusinessRuleDtos.EndpointFieldResponse;
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
import com.aiapitesting.backend.repository.TestCaseRepository;
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
    private final TestCaseRepository testCaseRepository;
    private final TestCaseChecklistPlanner planner;
    private final ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    // ---- đọc ----

    @Transactional
    public PageResponse<RuleResponse> list(UUID projectId, Integer page, Integer size, UUID endpointId, RuleStatus status, RuleSource source,
                                           RuleTemplate template) {
        Project project = projectService.getOwnedProject(projectId);
        relink(project);
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size < 1 ? 50 : Math.min(size, MAX_PAGE_SIZE);
        var result = ruleRepository.findAll(BusinessRuleRepository.filter(project, null, endpointId, status, source, template),
                PageRequest.of(p, s, Sort.by("endpointKey").ascending().and(Sort.by("createdAt").ascending())));
        // 1 truy vấn đếm test cho cả trang (không N+1).
        java.util.Map<UUID, Long> counts = testCaseRepository.findAllByRuleIdIn(
                        result.getContent().stream().map(BusinessRule::getId).toList()).stream()
                .collect(java.util.stream.Collectors.groupingBy(com.aiapitesting.backend.entity.TestCase::getRuleId,
                        java.util.stream.Collectors.counting()));
        return PageResponse.from(result, rule -> toResponse(rule, counts.getOrDefault(rule.getId(), 0L)));
    }

    @Transactional(readOnly = true)
    public RuleResponse get(UUID projectId, UUID ruleId) {
        return toResponse(owned(projectService.getOwnedProject(projectId), ruleId));
    }

    // ---- field của endpoint + phát hiện tự động ----

    /** Field của endpoint để chọn trong form nhập luật (cùng nguồn với checklist: Spec của planner). */
    @Transactional(readOnly = true)
    public List<EndpointFieldResponse> fieldsOf(UUID projectId, UUID endpointId) {
        Project project = projectService.getOwnedProject(projectId);
        Endpoint endpoint = endpointRepository.findByIdAndProject(endpointId, project)
                .orElseThrow(() -> new EndpointNotFoundException("Không tìm thấy endpoint với id đã cho"));
        return planner.readSpec(endpoint).rules().stream()
                .map(r -> new EndpointFieldResponse(
                        r.location() == TestCaseChecklistPlanner.ParamLocation.BODY ? r.name()
                                : r.location().name().toLowerCase(Locale.ROOT) + ":" + r.name(),
                        r.name(), r.location().name(), r.type(), r.required(), r.nested(), r.numeric(), r.enumValues(), r.minimum(), r.maximum()))
                .toList();
    }

    /**
     * Hệ thống tự đề xuất luật từ spec (0 token): mỗi cặp field "dưới/trên" (salaryMin/salaryMax...) thành luật COMPARE "dưới ≤ trên" ở trạng
     * thái chờ duyệt, nguồn SYSTEM_DETECTED. Chạy lại không tạo trùng (fingerprint duy nhất), luật đã bị loại không bị đề xuất lại.
     */
    @Transactional
    public DetectResponse detect(UUID projectId) {
        Project project = projectService.getOwnedProject(projectId);
        int proposed = 0;
        int skipped = 0;
        for (Endpoint endpoint : endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)) {
            for (String[] pair : planner.pairedBounds(planner.readSpec(endpoint))) {
                RuleParams params = new RuleParams(pair[0], "LTE", pair[1], null, null, null, null, null, null, null, null, null, null, null);
                BusinessRule rule;
                try {
                    rule = build(project, endpoint, RuleTemplate.COMPARE_FIELDS, params, null,
                            "Hệ thống thấy cặp field '" + pair[0] + "' / '" + pair[1] + "' - thường 'dưới' không được lớn hơn 'trên'", RuleSource.SYSTEM_DETECTED);
                } catch (BusinessRuleException e) {
                    skipped++;
                    continue;
                }
                if (ruleRepository.existsByProjectAndFingerprint(project, rule.getFingerprint())) {
                    skipped++;
                    continue;
                }
                ruleRepository.save(rule);
                if (rule.getStatus() == RuleStatus.PROPOSED || rule.getStatus() == RuleStatus.CONFLICT) {
                    proposed++;
                }
            }
        }
        return new DetectResponse(proposed, skipped);
    }

    // ---- nối lại luật với endpoint (luật sống qua import REPLACE) ----

    /** Endpoint hiện tại của luật: theo id, không có thì theo endpointKey (METHOD path) - và cập nhật lại endpointId nếu đổi. */
    java.util.Optional<Endpoint> resolveEndpoint(Project project, BusinessRule rule) {
        if (rule.getEndpointId() != null) {
            var byId = endpointRepository.findByIdAndProject(rule.getEndpointId(), project);
            if (byId.isPresent()) {
                return byId;
            }
        }
        String[] parts = rule.getEndpointKey().split(" ", 2);
        var byKey = endpointRepository.findByProjectAndPathAndMethod(project, parts.length > 1 ? parts[1] : "", parts[0]);
        byKey.ifPresent(e -> rule.setEndpointId(e.getId()));
        return byKey;
    }

    /** Sau import lại, endpoint đổi id: nối lại luật theo endpointKey; endpoint đã gỡ khỏi spec thì luật thành STALE. */
    void relink(Project project) {
        java.util.Set<UUID> liveIds = endpointRepository.findAllByProjectOrderByCreatedAtAsc(project).stream()
                .map(Endpoint::getId).collect(java.util.stream.Collectors.toSet());
        for (BusinessRule rule : ruleRepository.findAllByProject(project)) {
            if (rule.getEndpointId() != null && liveIds.contains(rule.getEndpointId())) {
                continue;
            }
            var found = resolveEndpoint(project, rule);
            if (found.isEmpty()) {
                if (rule.getStatus() != RuleStatus.STALE && rule.getStatus() != RuleStatus.REJECTED) {
                    rule.setStatus(RuleStatus.STALE);
                    rule.setStatusNote("Endpoint " + rule.getEndpointKey() + " không còn trong spec");
                }
                rule.setEndpointId(null);
            }
            ruleRepository.save(rule);
        }
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
        if (!rebuilt.getParamsJson().equals(rule.getParamsJson())) {
            // Tham số đã đổi: đoạn trích cũ không còn chứng minh luật này. Bỏ trích dẫn và coi là luật do người dùng tự sửa.
            rule.setQuote(null);
            rule.setQuoteOffset(null);
            rule.setDocumentId(null);
            rule.setExtractionJobId(null);
            if (rule.getSource() == RuleSource.DOC || rule.getSource() == RuleSource.INSTRUCTION || rule.getSource() == RuleSource.INFERRED) {
                rule.setSource(RuleSource.USER);
            }
        }
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
        if (rule.getSource() == RuleSource.INFERRED && (request == null || request.expectedStatus() == null)) {
            // Luật AI đoán không có trích dẫn nào nêu mã lỗi: người duyệt phải TỰ chọn mã kỳ vọng, không được bấm duyệt cho qua.
            throw BusinessRuleException.stateInvalid("Luật GIẢ ĐỊNH (AI đoán, không có trích dẫn) - phải tự chọn mã lỗi kỳ vọng trong chính thao tác duyệt");
        }
        markApproved(rule);
        return toResponse(ruleRepository.save(rule));
    }

    /** Duyệt hàng loạt các luật có trích dẫn; luật không đủ điều kiện được báo lý do, không làm hỏng cả lô. */
    @Transactional
    public ApproveBatchResponse approveBatch(UUID projectId, ApproveBatchRequest request) {
        Project project = projectService.getOwnedProject(projectId);
        if (request == null || request.ruleIds() == null || request.ruleIds().isEmpty()) {
            throw new InvalidRequestException("Phải chọn ít nhất 1 luật để duyệt");
        }
        List<UUID> approved = new java.util.ArrayList<>();
        List<NotApproved> skipped = new java.util.ArrayList<>();
        for (UUID id : request.ruleIds().stream().distinct().toList()) {
            BusinessRule rule = ruleRepository.findByIdAndProject(id, project).orElse(null);
            if (rule == null) {
                skipped.add(new NotApproved(id, "Không tìm thấy luật"));
            } else if (rule.getStatus() != RuleStatus.PROPOSED) {
                skipped.add(new NotApproved(id, "Chỉ duyệt hàng loạt luật đang chờ duyệt (hiện: " + rule.getStatus() + ")"));
            } else if (rule.getSource() == RuleSource.INFERRED || rule.getQuote() == null) {
                skipped.add(new NotApproved(id, "Luật không có trích dẫn - phải duyệt từng luật"));
            } else {
                markApproved(rule);
                ruleRepository.save(rule);
                approved.add(id);
            }
        }
        return new ApproveBatchResponse(approved, skipped);
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
        return resolveEndpoint(project, rule)
                .orElseThrow(() -> BusinessRuleException.stateInvalid("Endpoint của luật không còn trong project (luật lỗi thời)"));
    }

    RuleResponse toResponse(BusinessRule rule) {
        return toResponse(rule, rule.getId() == null ? 0 : testCaseRepository.countByRuleId(rule.getId()));
    }

    RuleResponse toResponse(BusinessRule rule, long testCount) {
        RuleParams params = read(rule.getParamsJson());
        return RuleResponse.from(rule, params, RuleSentence.of(rule.getTemplate(), params), testCount);
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
