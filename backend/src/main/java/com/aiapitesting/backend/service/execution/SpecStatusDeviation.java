package com.aiapitesting.backend.service.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 17q G1 - phát hiện "mã thành công lệch spec" (tất định, 0 token, chỉ CẢNH BÁO, không đổi Pass/Fail).
 *
 * Test gốc được hệ thống tự đổi kỳ vọng sang mã 2xx API trả (ADOPT_2XX) và test bù dương kế thừa kỳ vọng đó, nên nếu spec
 * khai đúng 201 mà API trả 200 thì mọi test vẫn Pass - lỗi thật bị che. Lớp này so mã thật với danh sách mã spec khai.
 *
 * Chỉ cảnh báo khi: test kỳ vọng 2xx, API trả 2xx, spec khai ít nhất 1 mã 2xx cụ thể, tập 2xx khai KHÁC đúng {200}
 * (springdoc mặc định khai 200 cho mọi endpoint nên không dựa vào được), và mã thật không thuộc tập khai. Bỏ qua "2XX"/"default".
 * Âm tính giả được chấp nhận: spec thật sự chỉ khai 200 mà API trả 201 thì không cảnh báo.
 */
public final class SpecStatusDeviation {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SpecStatusDeviation() {
    }

    /** Đọc {@code responseStatusCodes} trong schema đã lưu của endpoint; thiếu/hỏng thì trả tập rỗng (không cảnh báo). */
    public static Set<String> declaredStatusCodes(String storedSchemaJson) {
        Set<String> codes = new LinkedHashSet<>();
        if (storedSchemaJson == null || storedSchemaJson.isBlank()) {
            return codes;
        }
        try {
            JsonNode node = MAPPER.readTree(storedSchemaJson).get("responseStatusCodes");
            if (node != null && node.isArray()) {
                node.forEach(code -> codes.add(code.asText().trim()));
            }
        } catch (Exception ignored) {
            // schema hỏng: không đủ cơ sở để kết luận
        }
        return codes;
    }

    /** Mã 2xx cụ thể spec khai (bỏ "2XX", "default", mã không phải số). */
    public static Set<Integer> declaredSuccessCodes(Set<String> declared) {
        Set<Integer> success = new LinkedHashSet<>();
        if (declared == null) {
            return success;
        }
        for (String code : declared) {
            if (code != null && code.trim().matches("2\\d\\d")) {
                success.add(Integer.parseInt(code.trim()));
            }
        }
        return success;
    }

    /** Spec chỉ khai đúng {200} (thường do springdoc tự sinh) thì không đủ căn cứ để nói mã 2xx khác là sai. */
    public static boolean isInformative(Set<Integer> declaredSuccess) {
        return !declaredSuccess.isEmpty() && !declaredSuccess.equals(Set.of(200));
    }

    /**
     * @return câu cảnh báo khi mã thật lệch spec, rỗng nếu không lệch hoặc không đủ căn cứ.
     */
    public static Optional<String> check(Set<String> declared, Integer expectedStatus, Integer actualStatus) {
        if (expectedStatus == null || actualStatus == null || !is2xx(expectedStatus) || !is2xx(actualStatus)) {
            return Optional.empty();
        }
        Set<Integer> success = declaredSuccessCodes(declared);
        if (!isInformative(success) || success.contains(actualStatus)) {
            return Optional.empty();
        }
        return Optional.of("Mã trạng thái lệch spec: spec khai " + success + ", API trả " + actualStatus);
    }

    private static boolean is2xx(int status) {
        return status >= 200 && status <= 299;
    }
}
