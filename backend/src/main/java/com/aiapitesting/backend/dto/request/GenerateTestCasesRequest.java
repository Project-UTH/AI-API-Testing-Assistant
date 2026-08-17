package com.aiapitesting.backend.dto.request;

/**
 * Tuỳ chọn khi bấm "Sinh Test Case" (Module 9) - cả 2 field đều opt-in, mặc định false để không
 * đổi hành vi cũ (chỉ sinh 3 nhóm Cơ bản). Body được phép thiếu hẳn (request rỗng) - xem
 * TestCaseController, lúc đó coi như cả 2 đều false.
 */
public record GenerateTestCasesRequest(
        boolean includeSecurity,
        boolean includeAssertions
) {
}
