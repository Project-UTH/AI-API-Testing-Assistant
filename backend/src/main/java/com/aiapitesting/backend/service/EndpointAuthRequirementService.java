package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.request.BulkRequiresAuthRequest;
import com.aiapitesting.backend.dto.response.BulkUpdateResponse;
import com.aiapitesting.backend.dto.response.EndpointResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.exception.EndpointNotFoundException;
import com.aiapitesting.backend.repository.EndpointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Module 17 (1.3) - người dùng tự khai endpoint có yêu cầu xác thực hay không (ghi đè giá trị suy từ spec). Chỉ đổi
 * {@code Endpoint.requiresAuthManual}; không đụng test case nào. Hệ quả: các mục Security của endpoint đó chuyển giữa "không
 * áp dụng" và "cần kiểm" ở lần tính Coverage kế tiếp.
 */
@Service
@RequiredArgsConstructor
public class EndpointAuthRequirementService {

    private final ProjectService projectService;
    private final EndpointRepository endpointRepository;

    @Transactional
    public EndpointResponse setRequiresAuth(UUID projectId, UUID endpointId, Boolean requiresAuth) {
        Project project = projectService.getOwnedProject(projectId);
        Endpoint endpoint = endpointRepository.findByIdAndProject(endpointId, project)
                .orElseThrow(() -> new EndpointNotFoundException("Không tìm thấy endpoint với id đã cho"));
        endpoint.setRequiresAuthManual(requiresAuth);
        return EndpointResponse.from(endpointRepository.save(endpoint));
    }

    @Transactional
    public BulkUpdateResponse bulkSetRequiresAuth(UUID projectId, BulkRequiresAuthRequest request) {
        Project project = projectService.getOwnedProject(projectId);
        Set<UUID> ids = request == null || request.endpointIds() == null ? null : new HashSet<>(request.endpointIds());
        String prefix = request == null || request.pathPrefix() == null || request.pathPrefix().isBlank() ? null : request.pathPrefix().trim();
        String method = request == null || request.method() == null || request.method().isBlank() ? null : request.method().trim();
        Boolean value = request == null ? null : request.requiresAuth();

        List<Endpoint> matched = endpointRepository.findAllByProjectOrderByCreatedAtAsc(project).stream()
                .filter(e -> ids == null || ids.isEmpty() || ids.contains(e.getId()))
                .filter(e -> prefix == null || (e.getPath() != null && e.getPath().startsWith(prefix)))
                .filter(e -> method == null || method.equalsIgnoreCase(e.getMethod()))
                .toList();
        matched.forEach(e -> e.setRequiresAuthManual(value));
        endpointRepository.saveAll(matched);
        return new BulkUpdateResponse(matched.size());
    }
}
