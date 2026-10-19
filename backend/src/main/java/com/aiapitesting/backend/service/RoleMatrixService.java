package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.request.RoleRulesRequest;
import com.aiapitesting.backend.dto.response.BulkUpdateResponse;
import com.aiapitesting.backend.dto.response.RoleMatrixResponse;
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

        List<EndpointRole> toSave = new ArrayList<>();
        List<EndpointRole> toDelete = new ArrayList<>();
        Set<String> touched = new HashSet<>();
        for (RoleRulesRequest.RuleInput rule : request.rules()) {
            if (rule.profileId() == null || !profiles.containsKey(rule.profileId())) {
                throw new InvalidRequestException("Vai trò (profile xác thực) không tồn tại trong project này");
            }
            Set<UUID> ids = rule.endpointIds() == null || rule.endpointIds().isEmpty() ? null : new HashSet<>(rule.endpointIds());
            String prefix = rule.pathPrefix() == null || rule.pathPrefix().isBlank() ? null : rule.pathPrefix().trim();
            String method = rule.method() == null || rule.method().isBlank() ? null : rule.method().trim();
            for (Endpoint endpoint : allEndpoints) {
                if ((ids != null && !ids.contains(endpoint.getId()))
                        || (prefix != null && (endpoint.getPath() == null || !endpoint.getPath().startsWith(prefix)))
                        || (method != null && !method.equalsIgnoreCase(endpoint.getMethod()))) {
                    continue;
                }
                String cell = endpoint.getId() + "|" + rule.profileId();
                EndpointRole current = existing.get(cell);
                if (rule.expectation() == null) {
                    if (current != null && touched.add(cell)) {
                        toDelete.add(current);
                        existing.remove(cell);
                    }
                    continue;
                }
                touched.add(cell);
                if (current == null) {
                    EndpointRole created = EndpointRole.builder().endpoint(endpoint).authProfileId(rule.profileId())
                            .expectation(rule.expectation()).build();
                    existing.put(cell, created);
                    toSave.add(created);
                } else if (current.getExpectation() != rule.expectation()) {
                    current.setExpectation(rule.expectation());
                    if (!toSave.contains(current)) {
                        toSave.add(current);
                    }
                }
            }
        }
        endpointRoleRepository.deleteAll(toDelete);
        endpointRoleRepository.saveAll(toSave);
        return new BulkUpdateResponse(toSave.size() + toDelete.size());
    }
}
