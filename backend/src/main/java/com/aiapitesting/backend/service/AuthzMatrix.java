package com.aiapitesting.backend.service;

import com.aiapitesting.backend.entity.AuthzExpectation;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseAuthOverride;

/**
 * Module 17 (17a) - quy tắc chấm mục "phân quyền theo vai trò" của Coverage. Tách khỏi TestCaseChecklistPlanner (EP/BVA chấm theo
 * giá trị dữ liệu) vì mục này chấm theo CÁCH GỌI: test case dùng đúng profile của vai trò, gọi bằng token thật của nó (không bỏ/sai
 * token) và kỳ vọng đúng chiều.
 */
final class AuthzMatrix {

    static final String CATEGORY = "AUTHZ";
    static final String TYPE_ALLOWED = "AUTHZ_ALLOWED";
    static final String TYPE_DENIED = "AUTHZ_DENIED";
    /** Mã bị từ chối được chấp nhận cho ô DENY: 403 (đã xác thực nhưng không đủ quyền) hoặc 401. */
    static final int DENIED_STATUS = 403;

    private AuthzMatrix() {
    }

    static String type(AuthzExpectation expectation) {
        return expectation == AuthzExpectation.ALLOW ? TYPE_ALLOWED : TYPE_DENIED;
    }

    /** Khoá ổn định theo roleKey (không theo UUID profile): cùng dạng "type|location|field|value" của checklist EP/BVA. */
    static String key(AuthzExpectation expectation, String roleKey) {
        return type(expectation) + "|||" + roleKey;
    }

    static String label(AuthzExpectation expectation, String profileName) {
        return expectation == AuthzExpectation.ALLOW
                ? "Vai trò '" + profileName + "' được phép gọi - kỳ vọng thành công (2xx)"
                : "Vai trò '" + profileName + "' bị cấm - kỳ vọng bị từ chối (403/401)";
    }

    static String why(AuthzExpectation expectation, String profileName) {
        return expectation == AuthzExpectation.ALLOW
                ? "Ma trận khai vai trò '" + profileName + "' được gọi endpoint này; cần test gọi bằng token của vai trò đó và nhận 2xx để "
                        + "xác nhận quyền không bị chặn nhầm."
                : "Ma trận khai vai trò '" + profileName + "' bị cấm; cần test gọi bằng token của vai trò đó và nhận 403/401 để xác nhận "
                        + "API thật sự chặn (nhận 2xx nghĩa là thiếu kiểm quyền hoặc leo quyền).";
    }

    /** Test case này có phủ ô (endpoint, vai trò) không - đọc cách gọi + kỳ vọng thật, không đọc tên/lời tự khai. */
    static boolean covers(AuthzExpectation expectation, java.util.UUID profileId, TestCase testCase) {
        if (profileId == null || !profileId.equals(testCase.getAuthProfileId())) {
            return false;
        }
        // Case cố ý bỏ/sai token là test Security, không phải kiểm quyền của vai trò.
        TestCaseAuthOverride override = testCase.getAuthOverride();
        if (override != null && override != TestCaseAuthOverride.DEFAULT) {
            return false;
        }
        Integer expected = testCase.getExpectedStatus();
        if (expected == null) {
            return false;
        }
        return expectation == AuthzExpectation.ALLOW
                ? expected >= 200 && expected < 300
                : expected == 401 || expected == 403;
    }
}
