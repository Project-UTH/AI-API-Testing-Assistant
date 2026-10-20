package com.aiapitesting.backend.dto.request;

import java.util.List;
import java.util.UUID;

/**
 * Chọn endpoint theo điều kiện (mọi điều kiện đã cho phải thoả): danh sách id, tiền tố đường dẫn, method. Không cho điều
 * kiện nào = mọi endpoint của project. requiresAuth = null: bỏ khai báo thủ công (về giá trị suy từ spec).
 */
public record BulkRequiresAuthRequest(List<UUID> endpointIds, String pathPrefix, String method, Boolean requiresAuth) {
}
