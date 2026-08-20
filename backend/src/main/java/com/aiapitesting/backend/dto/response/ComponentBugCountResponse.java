package com.aiapitesting.backend.dto.response;

import java.util.UUID;

/** Số bug theo Component/endpoint - dùng ở khối Dashboard đầu trang Bug Report. */
public record ComponentBugCountResponse(UUID endpointId, String endpointMethod, String endpointPath, long count) {
}
