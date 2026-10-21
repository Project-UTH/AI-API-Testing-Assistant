package com.aiapitesting.backend.dto.response;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

public record PageResponse<T>(
        List<T> data,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    /** Trang dựng thủ công (khi tổng số dòng lấy từ truy vấn riêng, không phải Spring Data Page). */
    public static <T> PageResponse<T> of(List<T> data, int page, int size, long totalElements) {
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PageResponse<>(data, page, size, totalElements, totalPages);
    }

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
