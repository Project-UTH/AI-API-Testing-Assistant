package com.aiapitesting.backend.dto.request;

/**
 * Tuỳ chọn khi bấm "Sinh Test Case" (Module 9). Body được phép thiếu hẳn (request null) - xem
 * TestCaseGenerationService.generate(), lúc đó coi như includeSecurity = false và
 * includePositive/includeNegative/includeBoundary = true (giữ đúng hành vi mặc định cũ: chỉ sinh 3
 * nhóm Cơ bản). Khi có body, mỗi field đọc đúng giá trị boolean gửi lên - phải chọn ít nhất 1 trong
 * 4 field, nếu không sẽ bị từ chối (không có gì để sinh).
 *
 * Không có tuỳ chọn AI sinh assertion (đã bỏ ở Module 9b) - assertion chỉ thêm tay qua
 * TestCaseRequest.assertions.
 */
public record GenerateTestCasesRequest(
        boolean includeSecurity,
        boolean includePositive,
        boolean includeNegative,
        boolean includeBoundary,
        /** Nhóm NGHIỆP VỤ (AI đoán luật không có trong spec). Mặc định false, nhóm này sinh/xoá độc lập với 4 loại còn lại. */
        boolean includeBusiness
) {
    public GenerateTestCasesRequest(boolean includeSecurity, boolean includePositive, boolean includeNegative, boolean includeBoundary) {
        this(includeSecurity, includePositive, includeNegative, includeBoundary, false);
    }
}
