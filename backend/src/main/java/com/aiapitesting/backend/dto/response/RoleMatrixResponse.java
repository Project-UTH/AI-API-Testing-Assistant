package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.AuthzExpectation;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Ma trận phân quyền của project: mỗi endpoint kèm kỳ vọng theo từng vai trò (thiếu khoá = chưa khai). */
public record RoleMatrixResponse(List<ProfileRef> profiles, List<EndpointRow> endpoints) {

    public record ProfileRef(UUID id, String name, String roleKey) {
    }

    public record EndpointRow(UUID endpointId, String method, String path, Map<UUID, AuthzExpectation> rules) {
    }
}
