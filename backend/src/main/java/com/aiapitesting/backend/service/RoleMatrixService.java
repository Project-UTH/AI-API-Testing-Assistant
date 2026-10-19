package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.request.RoleRulesRequest;
import com.aiapitesting.backend.dto.response.BulkUpdateResponse;
import com.aiapitesting.backend.dto.response.RoleMatrixResponse;
import com.aiapitesting.backend.dto.response.RoleRulesPreviewResponse;
import com.aiapitesting.backend.entity.AuthProfile;
import com.aiapitesting.backend.entity.AuthzExpectation;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.EndpointRole;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.repository.AuthProfileRepository;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.EndpointRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Module 17 (17a) - ma trận phân quyền (endpoint × vai trò): vai trò nào được phép / bị cấm gọi endpoint nào. Chỉ là KHAI BÁO của
 * người dùng; việc kiểm tra thật nằm ở Coverage (mục AUTHZ) và test case dùng profile.
 */
@Service
@RequiredArgsConstructor
public class RoleMatrixService {

    static final int SAMPLE_PATHS = 5;

    private final ProjectService projectService;
    private final EndpointRepository endpointRepository;
    private final AuthProfileRepository authProfileRepository;
    private final EndpointRoleRepository endpointRoleRepository;

    public RoleMatrixResponse get(UUID projectId) {
        Project project = projectService.getOwnedProject(projectId);
        List<RoleMatrixResponse.ProfileRef> profiles = authProfileRepository.findAllByProjectOrderByCreatedAtAsc(project).stream()
                .map(p -> new RoleMatrixResponse.ProfileRef(p.getId(), p.getName(), p.getRoleKey()))
                .toList();
        Map<UUID, Map<UUID, AuthzExpectation>> rulesByEndpoint = new HashMap<>();
        for (EndpointRole role : endpointRoleRepository.findAllByEndpointProject(project)) {
            rulesByEndpoint.computeIfAbsent(role.getEndpoint().getId(), k -> new LinkedHashMap<>())
                    .put(role.getAuthProfileId(), role.getExpectation());
        }
        List<RoleMatrixResponse.EndpointRow> rows = endpointRepository.findAllByProjectOrderByCreatedAtAsc(project).stream()
                .map(e -> new RoleMatrixResponse.EndpointRow(e.getId(), e.getMethod(), e.getPath(),
                        rulesByEndpoint.getOrDefault(e.getId(), Map.of())))
                .toList();
        return new RoleMatrixResponse(profiles, rows);
    }

    /** Trả số ô (endpoint × vai trò) đã đặt hoặc xoá. */
    @Transactional
    public BulkUpdateResponse update(UUID projectId, RoleRulesRequest request) {
        Plan plan = plan(projectId, request);
        List<EndpointRole> toSave = new ArrayList<>();
        List<EndpointRole> toDelete = new ArrayList<>();
        for (CellOp op : plan.ops()) {
            if (op.finalExpectation() == null) {
                toDelete.add(op.current());
            } else if (op.current() == null) {
                toSave.add(EndpointRole.builder().endpoint(op.endpoint()).authProfileId(op.profileId()).expectation(op.finalExpectation()).build());
            } else {
                op.current().setExpectation(op.finalExpectation());
                toSave.add(op.current());
            }
        }
        endpointRoleRepository.deleteAll(toDelete);
        endpointRoleRepository.saveAll(toSave);
        return new BulkUpdateResponse(toSave.size() + toDelete.size());
    }

    /** Xem trước (không ghi gì): cùng thuật toán với {@link #update}, nên số ô khớp đúng số ô lưu thật. */
    @Transactional(readOnly = true)
    public RoleRulesPreviewResponse preview(UUID projectId, RoleRulesRequest request) {
        return plan(projectId, request).preview();
    }

    /** 1 ô cần ghi sau khi áp mọi quy tắc theo thứ tự (quy tắc sau ghi đè quy tắc trước). current = khai báo đã lưu (null nếu chưa có). */
    private record CellOp(Endpoint endpoint, UUID profileId, EndpointRole current, AuthzExpectation finalExpectation) {
    }

    private record Plan(List<CellOp> ops, RoleRulesPreviewResponse preview) {
    }

    private Plan plan(UUID projectId, RoleRulesRequest request) {
        Project project = projectService.getOwnedProject(projectId);
        if (request == null || request.rules() == null || request.rules().isEmpty()) {
            throw new InvalidRequestException("Chưa có rule nào để áp dụng");
        }
        Map<UUID, AuthProfile> profiles = new HashMap<>();
        authProfileRepository.findAllByProjectOrderByCreatedAtAsc(project).forEach(p -> profiles.put(p.getId(), p));
        List<Endpoint> allEndpoints = endpointRepository.findAllByProjectOrderByCreatedAtAsc(project);

        Map<String, EndpointRole> existing = new HashMap<>();
        for (EndpointRole role : endpointRoleRepository.findAllByEndpointProject(project)) {
            existing.put(role.getEndpoint().getId() + "|" + role.getAuthProfileId(), role);
        }

        // Trạng thái ảo của từng ô sau mỗi quy tắc (không đụng entity): value null = chưa khai / đã xoá.
        Map<String, AuthzExpectation> state = new HashMap<>();
        existing.forEach((cell, role) -> state.put(cell, role.getExpectation()));
        Set<String> setByEarlierRule = new HashSet<>();
        Map<String, Endpoint> touchedEndpoint = new LinkedHashMap<>();
        Map<String, UUID> touchedProfile = new LinkedHashMap<>();

        List<RoleRulesPreviewResponse.RuleReport> reports = new ArrayList<>();
        int ruleIndex = 0;
        for (RoleRulesRequest.RuleInput rule : request.rules()) {
            ruleIndex++;
            if (rule.profileId() == null || !profiles.containsKey(rule.profileId())) {
                throw new InvalidRequestException("Vai trò (profile xác thực) không tồn tại trong project này");
            }
            Set<UUID> ids = rule.endpointIds() == null || rule.endpointIds().isEmpty() ? null : new HashSet<>(rule.endpointIds());
            String prefix = rule.pathPrefix() == null || rule.pathPrefix().isBlank() ? null : rule.pathPrefix().trim();
            String method = rule.method() == null || rule.method().isBlank() ? null : rule.method().trim();
            int matched = 0, overridesEarlier = 0, overridesExisting = 0;
            List<String> samples = new ArrayList<>();
            Set<String> setByThisRule = new HashSet<>();
            for (Endpoint endpoint : allEndpoints) {
                if ((ids != null && !ids.contains(endpoint.getId()))
                        || (prefix != null && (endpoint.getPath() == null || !endpoint.getPath().startsWith(prefix)))
                        || (method != null && !method.equalsIgnoreCase(endpoint.getMethod()))) {
                    continue;
                }
                matched++;
                if (samples.size() < SAMPLE_PATHS) {
                    samples.add(endpoint.getMethod() + " " + endpoint.getPath());
                }
                String cell = endpoint.getId() + "|" + rule.profileId();
                AuthzExpectation before = state.get(cell);
                if (rule.expectation() != null && before != null && before != rule.expectation()) {
                    if (setByEarlierRule.contains(cell)) {
                        overridesEarlier++;
                    } else if (existing.containsKey(cell)) {
                        overridesExisting++;
                    }
                }
                touchedEndpoint.putIfAbsent(cell, endpoint);
                touchedProfile.putIfAbsent(cell, rule.profileId());
                state.put(cell, rule.expectation());
                setByThisRule.add(cell);
            }
            setByEarlierRule.addAll(setByThisRule);
            reports.add(new RoleRulesPreviewResponse.RuleReport(ruleIndex, rule.profileId(), profiles.get(rule.profileId()).getName(),
                    rule.expectation(), matched, List.copyOf(samples), overridesEarlier, overridesExisting));
        }

        List<CellOp> ops = new ArrayList<>();
        int created = 0, changed = 0, deleted = 0;
        for (String cell : touchedEndpoint.keySet()) {
            EndpointRole current = existing.get(cell);
            AuthzExpectation after = state.get(cell);
            if (current == null && after == null) {
                continue; // đặt rồi xoá lại trong cùng yêu cầu / xoá ô chưa từng khai: không đổi gì
            }
            if (current != null && current.getExpectation() == after) {
                continue;
            }
            if (after == null) {
                deleted++;
            } else if (current == null) {
                created++;
            } else {
                changed++;
            }
            ops.add(new CellOp(touchedEndpoint.get(cell), touchedProfile.get(cell), current, after));
        }
        return new Plan(ops, new RoleRulesPreviewResponse(created, changed, deleted, List.copyOf(reports)));
    }
}
