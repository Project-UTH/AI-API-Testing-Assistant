package com.aiapitesting.backend.dto.request;

/** Quy ước của API đích. unauthenticatedStatus: 401, 403 hoặc null (= 401, chuẩn). */
public record ApiConventionsRequest(Integer unauthenticatedStatus) {
}
