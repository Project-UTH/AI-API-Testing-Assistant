package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.entity.TestCase;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Module 16 - "dấu vân tay" PHIÊN BẢN của 1 test case: SHA-256 của mọi thứ làm đổi điều test kiểm tra - method,
 * đường dẫn, header, body, mã kỳ vọng, cách gắn auth, assertion và liên kết dữ liệu (Test Data Chaining). Tên và
 * mô tả KHÔNG tính (đổi tên không đổi điều được kiểm). Lý do tồn tại: TestCaseService.update() sửa test nhưng giữ
 * nguyên TestResult cũ, nên lịch sử trộn nhiều phiên bản - sửa kỳ vọng 400→422 làm lịch sử Fail→Pass bị đọc nhầm
 * thành "không ổn định". Chỉ so lịch sử giữa các lần chạy CÙNG fingerprint.
 */
public final class TestCaseFingerprint {

    private TestCaseFingerprint() {
    }

    public static String of(TestCase testCase, List<AssertionSpec> assertions, List<DependencyEdge> edges) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, testCase.getEndpoint() == null ? null : testCase.getEndpoint().getMethod());
        append(canonical, testCase.getResolvedPath());
        append(canonical, testCase.getRequestHeaders());
        append(canonical, testCase.getRequestBody());
        append(canonical, testCase.getExpectedStatus());
        append(canonical, testCase.getAuthOverride());
        // Module 17 (3.1) - đổi vai trò (profile) là đổi điều test kiểm tra: lịch sử cũ ở vai trò khác không được trộn vào.
        append(canonical, testCase.getAuthProfileId());
        // Giá trị dự phòng của tham số cũng được gửi đi (thay token {{x}}) nên là 1 phần của phiên bản test.
        append(canonical, testCase.getPathParamFallbacks());
        assertions.stream()
                .map(a -> a.jsonPath() + "|" + a.operator() + "|" + a.expectedValue())
                .sorted()
                .forEach(line -> append(canonical, "A:" + line));
        edges.stream()
                .sorted(Comparator.comparing(DependencyEdge::placeholderName))
                .forEach(e -> append(canonical, "D:" + e.placeholderName() + "|" + e.jsonPath() + "|" + e.sourceTestCaseId()));
        return sha256(canonical.toString());
    }

    private static void append(StringBuilder builder, Object value) {
        // Độ dài đứng trước để 2 trường liền nhau không thể trộn thành cùng 1 chuỗi ("ab"+"c" khác "a"+"bc").
        String text = value == null ? "\u0000null" : String.valueOf(value);
        builder.append(text.length()).append(':').append(text).append(';');
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 luôn có sẵn trong JDK", e);
        }
    }
}
