package com.aiapitesting.backend.entity;

public enum TargetAuthType {
    NONE,
    API_KEY,
    BEARER_TOKEN,
    /** Generalization - HTTP Basic: giá trị lưu là "tên:mật khẩu", gửi header Authorization: Basic base64(...). Thêm vào CUỐI enum. */
    BASIC_AUTH,
    /** Generalization - header xác thực tuỳ ý: giá trị lưu là "Tên-Header: giá trị" (vd "X-Auth-Token: abc", "api-key: abc"). */
    CUSTOM_HEADER,
    /** Generalization - khoá API gửi qua query string: giá trị lưu là "tên_tham_số=giá trị" (vd "api_key=abc"). Thêm vào CUỐI enum. */
    QUERY_PARAM
}
