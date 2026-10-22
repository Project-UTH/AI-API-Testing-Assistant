package com.aiapitesting.backend.service;

/** Tham số thô của {@code GET /projects/{pid}/coverage?page=...} - EndpointCoverageService kiểm tra và chuẩn hoá trước khi tính. */
public record CoverageListQuery(Integer page, Integer size, String q, String method, Boolean onlyGaps, Boolean onlyFailing, String sort,
                                /** 17q G2 - lọc theo trạng thái kiểm thử của endpoint (EndpointTestState), null = không lọc. */
                                String state) {
}
