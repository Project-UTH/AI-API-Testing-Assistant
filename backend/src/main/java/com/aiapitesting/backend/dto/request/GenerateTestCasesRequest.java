package com.aiapitesting.backend.dto.request;

/**
 * Tuỳ chọn khi bấm "Sinh Test Case" (Module 9). Body được phép thiếu hẳn (request null) - xem
 * TestCaseGenerationService.generate(), lúc đó coi như includeSecurity/includeAssertions = false
 * và includePositive/includeNegative/includeBoundary = true (giữ đúng hành vi mặc định cũ: chỉ
 * sinh 3 nhóm Cơ bản). Khi có body, mỗi field đọc đúng giá trị boolean gửi lên - phải chọn ít nhất
 * 1 trong 4 field includePositive/includeNegative/includeBoundary/includeSecurity, nếu không sẽ bị
 * từ chối (không có gì để sinh).
 */
public record GenerateTestCasesRequest(
        boolean includeSecurity,
        boolean includeAssertions,
        boolean includePositive,
        boolean includeNegative,
        boolean includeBoundary
) {
}
