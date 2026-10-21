package com.aiapitesting.backend.service;

import com.aiapitesting.backend.exception.InvalidRequestException;

import java.util.Locale;
import java.util.Set;

/**
 * Kiểm tra tham số phân trang bằng tay (không bind {@code Pageable}): page/size sai hay sort lạ phải ra 400
 * {@code VALIDATION_ERROR}, không rơi vào 500 như khi để Spring Data tự ném {@code IllegalArgumentException}.
 */
public final class PagingSupport {

    public record Paging(int page, int size) {
    }

    private PagingSupport() {
    }

    /** page &lt; 0 hoặc size &lt; 1 là 400; size vượt maxSize thì cắt xuống maxSize (cùng cách ExecutionCompareService). */
    public static Paging resolve(Integer page, Integer size, int defaultSize, int maxSize) {
        int p = page == null ? 0 : page;
        if (p < 0) {
            throw new InvalidRequestException("page phải lớn hơn hoặc bằng 0");
        }
        int s = size == null ? defaultSize : size;
        if (s < 1) {
            throw new InvalidRequestException("size phải lớn hơn hoặc bằng 1");
        }
        return new Paging(p, Math.min(s, maxSize));
    }

    /** Chuỗi tìm kiếm: bỏ khoảng trắng đầu/cuối, cắt tối đa maxLength; rỗng thì trả null (không lọc). */
    public static String normalizeQuery(String q, int maxLength) {
        if (q == null) {
            return null;
        }
        String trimmed = q.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    /** Thoát ký tự đặc biệt của LIKE (\, %, _) để người dùng gõ "50%" không bị hiểu là ký tự đại diện. */
    public static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE");

    /** Method HTTP dùng để lọc: rỗng = không lọc; viết hoa; giá trị lạ là 400. */
    public static String normalizeMethod(String method) {
        if (method == null || method.isBlank()) {
            return null;
        }
        String upper = method.trim().toUpperCase(Locale.ROOT);
        if (!HTTP_METHODS.contains(upper)) {
            throw new InvalidRequestException("method không hợp lệ: " + method);
        }
        return upper;
    }

    /** Chỉ cho phép sort nằm trong danh sách trắng; khác thì 400. null hoặc rỗng trả về defaultSort. */
    public static String requireAllowedSort(String sort, String defaultSort, Set<String> allowed) {
        if (sort == null || sort.isBlank()) {
            return defaultSort;
        }
        String normalized = sort.trim();
        if (!allowed.contains(normalized)) {
            throw new InvalidRequestException("sort không hợp lệ, chỉ nhận: " + String.join(", ", allowed));
        }
        return normalized;
    }
}
