package com.aiapitesting.backend.service;

import com.aiapitesting.backend.entity.TestCaseGenerationOrigin;
import com.aiapitesting.backend.entity.TestCaseReliability;
import com.aiapitesting.backend.entity.TestCaseSource;

import java.util.UUID;

/** Tham số thô của {@code GET /projects/{pid}/test-cases?page=...} - TestCaseService kiểm tra và chuẩn hoá trước khi truy vấn. */
public record TestCaseListQuery(
        Integer page, Integer size, String q, String method, UUID endpointId, TestCaseSource source,
        TestCaseGenerationOrigin generationOrigin, Boolean locked, TestCaseReliability reliability, String sort
) {
}
