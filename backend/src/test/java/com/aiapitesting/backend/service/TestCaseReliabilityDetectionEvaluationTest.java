package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.TestCaseReliability;
import com.aiapitesting.backend.entity.TestCaseSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Module 15f (mục 5) - đo Precision/Recall của Tier 1 ({@code TestCaseReliabilityValidator}) trên 1
 * bộ dữ liệu có NHÃN (ground truth) tự dựng, KHÔNG gọi AI - tất định, tái lập được, tự thành bài test
 * hồi quy. Ground truth KHÔNG tautological với rule tất định: bao gồm cả 2 case lỗi hợp lệ về
 * nghiệp vụ (404/409, không vi phạm field nào theo schema) để lộ đúng hạn chế một chiều đã ghi nhận
 * ở phần mở rộng Tier 1 (15f việc 2) - rule tất định sẽ gắn cờ 2 case này (false positive thật), vì
 * schema không thể hiện được lý do nghiệp vụ. Đây chính là động lực cho Tier 2 (15d) tồn tại - xem
 * {@code TestCaseReliabilityDetectionEvaluationTest} không đo Tier 2 (cần AI thật, xem Mốc xác nhận
 * 15f trong roadmap cho số liệu Tier 2 đo bằng tay).
 */
class TestCaseReliabilityDetectionEvaluationTest {

    private final TestCaseReliabilityValidator validator = new TestCaseReliabilityValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private record LabeledCase(String label, GeneratedTestCase candidate, TestCaseSource source, boolean isRealProblem) {
    }

    private Endpoint endpoint() {
        Schema<?> emailSchema = new Schema<>().type("string");
        Schema<?> statusSchema = new Schema<>().type("string")._enum(List.of("ACTIVE", "INACTIVE"));
        Schema<?> quantitySchema = new Schema<>().type("integer").minimum(BigDecimal.ONE).maximum(BigDecimal.valueOf(100));
        Schema<?> usernameSchema = new Schema<>().type("string").minLength(3);
        Schema<?> bodySchema = new Schema<>()
                .required(List.of("email"))
                .properties(Map.of("email", emailSchema, "status", statusSchema, "quantity", quantitySchema, "username", usernameSchema));
        RequestBody requestBody = new RequestBody()
                .content(new Content().addMediaType("application/json", new MediaType().schema(bodySchema)));

        Map<String, Object> trimmed = new LinkedHashMap<>();
        trimmed.put("requestBody", requestBody);
        try {
            String json = objectMapper.writeValueAsString(trimmed);
            return Endpoint.builder().id(UUID.randomUUID()).path("/items").method("POST").schema(json).createdAt(Instant.now()).build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private GeneratedTestCase gtc(Integer expectedStatus, String requestBody) {
        return new GeneratedTestCase("n", "d", Map.of(), requestBody, expectedStatus, "/items", Map.of(), null, null, "r");
    }

    private List<LabeledCase> labeledDataset() {
        List<LabeledCase> cases = new ArrayList<>();
        // A. Positive hợp lệ hoàn toàn - không phải lỗi.
        cases.add(new LabeledCase("A-positive-valid", gtc(201,
                "{\"email\":\"a@b.com\",\"status\":\"ACTIVE\",\"quantity\":10,\"username\":\"abc\"}"), TestCaseSource.AI_GENERATED, false));
        // B. Positive thiếu field required - lỗi AI thật.
        cases.add(new LabeledCase("B-positive-missingRequired", gtc(201, "{}"), TestCaseSource.AI_GENERATED, true));
        // C. Positive enum sai - lỗi AI thật.
        cases.add(new LabeledCase("C-positive-badEnum", gtc(200,
                "{\"email\":\"a@b.com\",\"status\":\"DELETED\"}"), TestCaseSource.AI_GENERATED, true));
        // D. Positive số ngoài biên - lỗi AI thật.
        cases.add(new LabeledCase("D-positive-outOfRange", gtc(200,
                "{\"email\":\"a@b.com\",\"quantity\":500}"), TestCaseSource.AI_GENERATED, true));
        // E. Positive hợp lệ, biến thể khác (biên nhỏ nhất) - không phải lỗi.
        cases.add(new LabeledCase("E-positive-validMinBoundary", gtc(200,
                "{\"email\":\"a@b.com\",\"quantity\":1}"), TestCaseSource.AI_GENERATED, false));
        // F. Negative đúng ý đồ - thiếu field, kỳ vọng lỗi đúng - không phải lỗi.
        cases.add(new LabeledCase("F-negative-correctlyMissingField", gtc(400, "{}"), TestCaseSource.AI_GENERATED, false));
        // G. Negative SAI - tên/mô tả nói thiếu field nhưng dữ liệu thực ra đủ/hợp lệ - lỗi AI thật.
        cases.add(new LabeledCase("G-negative-mislabeledActuallyValid", gtc(400,
                "{\"email\":\"a@b.com\"}"), TestCaseSource.AI_GENERATED, true));
        // H. Boundary đúng ý đồ - dưới min, kỳ vọng lỗi đúng - không phải lỗi.
        cases.add(new LabeledCase("H-boundary-correctlyBelowMin", gtc(400,
                "{\"email\":\"a@b.com\",\"quantity\":0}"), TestCaseSource.AI_GENERATED, false));
        // I. Boundary SAI - claim ngoài biên nhưng giá trị thực ra hợp lệ - lỗi AI thật.
        cases.add(new LabeledCase("I-boundary-mislabeledActuallyInRange", gtc(400,
                "{\"email\":\"a@b.com\",\"quantity\":50}"), TestCaseSource.AI_GENERATED, true));
        // J. Negative lỗi NGHIỆP VỤ (404 tài nguyên không tồn tại) - mọi field hợp lệ theo schema,
        // KHÔNG phải lỗi AI - nhưng rule tất định sẽ gắn cờ (false positive THẬT, không tránh được
        // bằng rule tất định vì schema không thể hiện lý do nghiệp vụ - đúng hạn chế đã ghi ở 15f).
        cases.add(new LabeledCase("J-negative-businessLogic404", gtc(404,
                "{\"email\":\"a@b.com\",\"quantity\":10}"), TestCaseSource.AI_GENERATED, false));
        // K. Negative lỗi NGHIỆP VỤ khác (409 trùng dữ liệu) - cùng lý do như J.
        cases.add(new LabeledCase("K-negative-businessLogic409", gtc(409,
                "{\"email\":\"a@b.com\",\"username\":\"abc\"}"), TestCaseSource.AI_GENERATED, false));
        // L. Security injection - cố ý sai định dạng nhưng vẫn là string hợp lệ theo schema - loại
        // trừ khỏi kiểm tra theo thiết kế, không phải lỗi AI.
        cases.add(new LabeledCase("L-security-injectionValidType", gtc(401,
                "{\"email\":\"'; DROP TABLE users; --\"}"), TestCaseSource.SECURITY, false));
        // M. Security thiếu field required - vẫn loại trừ theo thiết kế (payload tấn công cố ý thiếu
        // dữ liệu), không phải lỗi AI theo định nghĩa phạm vi Tier 1.
        cases.add(new LabeledCase("M-security-missingFieldExcluded", gtc(403, "{}"), TestCaseSource.SECURITY, false));
        // N. Positive enum hợp lệ ở giá trị khác - không phải lỗi.
        cases.add(new LabeledCase("N-positive-validOtherEnum", gtc(200,
                "{\"email\":\"a@b.com\",\"status\":\"INACTIVE\"}"), TestCaseSource.AI_GENERATED, false));
        // O. Positive vi phạm minLength - lỗi AI thật.
        cases.add(new LabeledCase("O-positive-tooShortUsername", gtc(200,
                "{\"email\":\"a@b.com\",\"username\":\"ab\"}"), TestCaseSource.AI_GENERATED, true));
        // P. Boundary đúng ý đồ - đúng biên lớn nhất, hợp lệ - không phải lỗi.
        cases.add(new LabeledCase("P-boundary-validMaxBoundary", gtc(200,
                "{\"email\":\"a@b.com\",\"quantity\":100}"), TestCaseSource.AI_GENERATED, false));
        return cases;
    }

    @Test
    void tier1_precisionAndRecallOnLabeledDataset_meetsMinimumThresholds() {
        Endpoint endpoint = endpoint();
        List<LabeledCase> dataset = labeledDataset();

        int truePositive = 0, falsePositive = 0, falseNegative = 0, trueNegative = 0;
        List<String> misclassified = new ArrayList<>();

        for (LabeledCase lc : dataset) {
            TestCaseReliabilityValidator.Result result = validator.validate(endpoint, lc.candidate(), lc.source());
            boolean flagged = result.status() == TestCaseReliability.NEEDS_REVIEW;

            if (flagged && lc.isRealProblem()) {
                truePositive++;
            } else if (flagged && !lc.isRealProblem()) {
                falsePositive++;
                misclassified.add(lc.label() + " (FP)");
            } else if (!flagged && lc.isRealProblem()) {
                falseNegative++;
                misclassified.add(lc.label() + " (FN)");
            } else {
                trueNegative++;
            }
        }

        double precision = truePositive == 0 ? 0 : (double) truePositive / (truePositive + falsePositive);
        double recall = truePositive == 0 ? 0 : (double) truePositive / (truePositive + falseNegative);
        double f1 = (precision + recall) == 0 ? 0 : 2 * precision * recall / (precision + recall);

        System.out.printf(
                "[Module 15f - Tier 1 Precision/Recall] TP=%d FP=%d FN=%d TN=%d | Precision=%.2f Recall=%.2f F1=%.2f%s%n",
                truePositive, falsePositive, falseNegative, trueNegative, precision, recall, f1,
                misclassified.isEmpty() ? "" : " | sai lệch: " + misclassified);

        // Ngưỡng tối thiểu - bộ dữ liệu này tự thành test hồi quy, không chỉ 1 lần đo rồi bỏ. Precision
        // KHÔNG đặt 1.0 vì J/K là false positive THẬT, chủ ý đưa vào để đo đúng hạn chế một chiều của
        // phần mở rộng Tier 1 (expectedStatus>=400 không vi phạm gì) - không phải lỗi code.
        assertThat(recall).isGreaterThanOrEqualTo(0.9);
        assertThat(precision).isGreaterThanOrEqualTo(0.6);
        assertThat(truePositive).isEqualTo(6);
        assertThat(falsePositive).isEqualTo(2);
        assertThat(falseNegative).isEqualTo(0);
    }
}
