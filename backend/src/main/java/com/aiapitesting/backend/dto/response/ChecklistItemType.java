package com.aiapitesting.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * Loại mục checklist tính tất định từ OpenAPI spec (Module 15h, mở rộng ở 15i thành checklist chuẩn
 * riêng cho từng loại test case). Biên dùng BVA 2 giá trị: đúng biên (hợp lệ, kỳ vọng 2xx) + ngay
 * ngoài biên (không hợp lệ, kỳ vọng 400). Tiền tố tên = loại test case (POSITIVE/NEGATIVE/BOUNDARY/
 * SECURITY) - frontend dựa vào đó để chia báo cáo theo loại.
 */
public enum ChecklistItemType {
    POSITIVE_BASELINE,
    /** 15i - mỗi giá trị enum hợp lệ là 1 lớp tương đương riêng (EP) nên mỗi giá trị cần 1 case thành công. */
    POSITIVE_ENUM_VALUE,

    NEGATIVE_MISSING_FIELD,
    /** 15i - field bắt buộc gửi null (khác bỏ hẳn field) khi spec không khai nullable. */
    NEGATIVE_NULL_FIELD,
    /** 15i - field nhận giá trị sai kiểu dữ liệu (vd số nhận chữ). */
    NEGATIVE_WRONG_TYPE,
    /** 15i - giá trị ngoài enum là lớp tương đương không hợp lệ nên thuộc Negative (15h xếp ở Boundary). */
    @JsonAlias("BOUNDARY_INVALID_ENUM")
    NEGATIVE_INVALID_ENUM,

    BOUNDARY_AT_MINIMUM,
    BOUNDARY_BELOW_MINIMUM,
    BOUNDARY_AT_MAXIMUM,
    BOUNDARY_ABOVE_MAXIMUM,
    BOUNDARY_AT_MIN_LENGTH,
    BOUNDARY_BELOW_MIN_LENGTH,
    BOUNDARY_AT_MAX_LENGTH,
    BOUNDARY_ABOVE_MAX_LENGTH,
    /** 15i - giá trị lớn nhất của int32/int64 khi spec không khai maximum (kỳ vọng 2xx). */
    BOUNDARY_AT_TYPE_MAXIMUM,
    /** 15i - vượt giới hạn int32/int64 (kỳ vọng 400 - không biểu diễn được bằng kiểu đã khai). */
    BOUNDARY_ABOVE_TYPE_MAXIMUM,
    /** 15i - giá trị nhỏ nhất của int32/int64 khi spec không khai minimum (kỳ vọng 2xx). */
    BOUNDARY_AT_TYPE_MINIMUM,
    /** 15i - dưới giới hạn int32/int64 (kỳ vọng 400). */
    BOUNDARY_BELOW_TYPE_MINIMUM,
    /** 15i - biên ngầm: số = 0 khi spec không khai minimum (spec không cấm → kỳ vọng 2xx). */
    BOUNDARY_ZERO,
    /** 15i - biên ngầm: số âm khi spec không khai minimum (spec không cấm → kỳ vọng 2xx). */
    BOUNDARY_NEGATIVE_NUMBER,
    /** 15i - biên ngầm: chuỗi rỗng khi spec không khai minLength (spec không cấm → kỳ vọng 2xx). */
    BOUNDARY_EMPTY_STRING,

    /** 15i - gọi không kèm token (authOverride=NONE), kỳ vọng 401. */
    SECURITY_MISSING_TOKEN,
    /** 15i - gọi với token sai (authOverride=INVALID), kỳ vọng 401. */
    SECURITY_INVALID_TOKEN,
    /** Module 17 (G2) - endpoint đăng nhập (không cần token, body có tài khoản + mật khẩu): gửi mật khẩu SAI, kỳ vọng 401. */
    SECURITY_WRONG_CREDENTIALS
}
