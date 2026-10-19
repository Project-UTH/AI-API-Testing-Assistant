package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.Endpoint;

/**
 * Phạm vi đo coverage của 1 endpoint: những loại test case (Positive/Negative/Boundary/Security) người dùng
 * muốn kiểm. Mục checklist thuộc loại ngoài phạm vi không tính vào "Cần kiểm"/% và không được Bổ sung.
 *
 * @param custom false khi endpoint chưa từng chọn phạm vi (dữ liệu cũ) - lúc đó coi như đủ 4 loại.
 */
public record CoverageScope(boolean positive, boolean negative, boolean boundary, boolean security, boolean custom) {

    public static final CoverageScope FULL = new CoverageScope(true, true, true, true, false);

    /** Cột null (endpoint tạo trước khi có phạm vi) = đủ 4 loại, giữ hành vi cũ. */
    public static CoverageScope of(Endpoint endpoint) {
        if (endpoint.getScopePositive() == null) {
            return FULL;
        }
        return new CoverageScope(
                !Boolean.FALSE.equals(endpoint.getScopePositive()),
                !Boolean.FALSE.equals(endpoint.getScopeNegative()),
                !Boolean.FALSE.equals(endpoint.getScopeBoundary()),
                !Boolean.FALSE.equals(endpoint.getScopeSecurity()),
                true);
    }

    public boolean includes(String category) {
        return switch (category) {
            case "POSITIVE" -> positive;
            case "NEGATIVE" -> negative;
            case "BOUNDARY" -> boundary;
            case "SECURITY" -> security;
            default -> true;
        };
    }

    /** 4 loại checklist EP/BVA - chỉ các dòng thuộc 4 loại này tính vào "Cần kiểm"/% coverage. */
    public static boolean isChecklistCategory(String category) {
        return "POSITIVE".equals(category) || "NEGATIVE".equals(category)
                || "BOUNDARY".equals(category) || "SECURITY".equals(category);
    }
}
