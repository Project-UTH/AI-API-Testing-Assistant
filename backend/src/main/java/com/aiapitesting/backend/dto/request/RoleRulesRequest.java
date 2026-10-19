package com.aiapitesting.backend.dto.request;

import com.aiapitesting.backend.entity.AuthzExpectation;

import java.util.List;
import java.util.UUID;

/**
 * Khai ma trận phân quyền hàng loạt. Mỗi rule chọn endpoint theo điều kiện (mọi điều kiện đã cho phải thoả: danh sách id, tiền tố
 * đường dẫn, method; không điều kiện nào = mọi endpoint) và đặt kỳ vọng của 1 vai trò. expectation = null: xoá khai báo.
 */
public record RoleRulesRequest(List<RuleInput> rules) {
    public record RuleInput(List<UUID> endpointIds, String pathPrefix, String method, UUID profileId, AuthzExpectation expectation) {
    }
}
