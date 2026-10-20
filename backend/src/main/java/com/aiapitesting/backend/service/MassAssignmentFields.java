package com.aiapitesting.backend.service;

import java.util.Locale;

/** Tiện ích nhỏ dùng chung cho Coverage và Bổ sung: method nào là "ghi" (có thể nhận body để gán dữ liệu). */
final class MassAssignmentFields {

    private MassAssignmentFields() {
    }

    static boolean isWriteMethod(String method) {
        if (method == null) {
            return false;
        }
        String m = method.toUpperCase(Locale.ROOT);
        return m.equals("POST") || m.equals("PUT") || m.equals("PATCH");
    }
}
