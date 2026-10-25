package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.entity.TestCase;

import java.util.Set;

/**
 * 17q G3 - so khớp mã trạng thái thật với kỳ vọng của test. Kỳ vọng "REJECTED" (test sinh từ luật nghiệp vụ không chỉ định mã) khớp mọi
 * 4xx NGOẠI TRỪ 401/403/404/405/415/429: các mã đó cho thấy request sai cách gọi (xác thực, quyền, đường dẫn, method, content-type, giới hạn
 * tần suất), không chứng minh API đã từ chối vì LUẬT. Test lệch sẽ Fail và được phân loại như thường (vd 401 thay vì 400 → API_WRONG_STATUS).
 */
public final class ExpectedStatus {

    public static final String REJECTED = "REJECTED";
    private static final Set<Integer> NOT_A_RULE_REJECTION = Set.of(401, 403, 404, 405, 415, 429);

    private ExpectedStatus() {
    }

    public static boolean matches(TestCase testCase, Integer actual) {
        return matches(testCase, actual, null);
    }

    /**
     * @param deniedStatus quy ước của API đích về mã khi thiếu/sai token (Project.unauthenticatedStatus; null = 401). Chỉ áp dụng cho test CỐ Ý bỏ hoặc
     *                     làm sai token (authOverride NONE/INVALID) kỳ vọng 401; test khác (vd sai mật khẩu đăng nhập) giữ nguyên.
     */
    public static boolean matches(TestCase testCase, Integer actual, Integer deniedStatus) {
        if (actual == null) {
            return false;
        }
        if (deniedStatus != null && deniedStatus == 403 && actual == 403 && Integer.valueOf(401).equals(testCase.getExpectedStatus())
                && (testCase.getAuthOverride() == com.aiapitesting.backend.entity.TestCaseAuthOverride.NONE
                || testCase.getAuthOverride() == com.aiapitesting.backend.entity.TestCaseAuthOverride.INVALID)) {
            return true;
        }
        if (REJECTED.equals(testCase.getExpectedStatusClass())) {
            return actual >= 400 && actual <= 499 && !NOT_A_RULE_REJECTION.contains(actual);
        }
        return actual.equals(testCase.getExpectedStatus());
    }
}
