package com.aiapitesting.backend.entity;

/** Module 17 (17a) - kỳ vọng phân quyền của 1 vai trò với 1 endpoint. */
public enum AuthzExpectation {
    /** Vai trò được phép gọi: kỳ vọng thành công (2xx). */
    ALLOW,
    /** Vai trò bị cấm: kỳ vọng bị từ chối (403, hoặc 401). */
    DENY
}
