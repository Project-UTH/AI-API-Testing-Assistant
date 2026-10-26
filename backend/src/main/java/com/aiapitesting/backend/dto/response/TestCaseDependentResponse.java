package com.aiapitesting.backend.dto.response;

import java.util.UUID;

/**
 * 1 quan hệ "test case này đang được test case khác phụ thuộc": test {@code dependentTestCaseId} lấy giá trị cho {@code placeholderName} từ phản hồi của test
 * {@code sourceTestCaseId} (theo {@code jsonPath}). Dùng để biết vì sao 1 test không xoá được và test nào bị ảnh hưởng nếu nó Fail.
 */
public record TestCaseDependentResponse(
        UUID sourceTestCaseId, UUID dependentTestCaseId, String dependentTestCaseName, UUID dependentEndpointId, String dependentEndpointLabel,
        String placeholderName, String jsonPath
) {
}
