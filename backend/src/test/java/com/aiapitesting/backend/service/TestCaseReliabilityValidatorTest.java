package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.TestCaseReliability;
import com.aiapitesting.backend.entity.TestCaseSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TestCaseReliabilityValidatorTest {

    private final TestCaseReliabilityValidator validator = new TestCaseReliabilityValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Endpoint endpointWithBodySchema(Schema<?> bodySchema) {
        RequestBody requestBody = new RequestBody()
                .content(new Content().addMediaType("application/json", new MediaType().schema(bodySchema)));
        Map<String, Object> trimmed = new LinkedHashMap<>();
        trimmed.put("requestBody", requestBody);
        return buildEndpoint(trimmed);
    }

    private Endpoint endpointWithParameters(List<Parameter> parameters) {
        Map<String, Object> trimmed = new LinkedHashMap<>();
        trimmed.put("parameters", parameters);
        return buildEndpoint(trimmed);
    }

    private Endpoint buildEndpoint(Map<String, Object> trimmed) {
        try {
            String json = objectMapper.writeValueAsString(trimmed);
            return Endpoint.builder().id(UUID.randomUUID()).path("/x").method("POST").schema(json).createdAt(Instant.now()).build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private GeneratedTestCase candidate(Integer expectedStatus, String requestBody) {
        return new GeneratedTestCase("name", "desc", Map.of(), requestBody, expectedStatus, "/x", Map.of(), null, null);
    }

    @Test
    void validate_requiredFieldMissingFromSuccessCase_flagsNeedsReview() {
        Schema<?> body = new Schema<>().required(List.of("email")).properties(Map.of("email", new Schema<>().type("string")));
        Endpoint endpoint = endpointWithBodySchema(body);

        TestCaseReliabilityValidator.Result result = validator.validate(endpoint, candidate(201, "{}"), TestCaseSource.AI_GENERATED);

        assertThat(result.status()).isEqualTo(TestCaseReliability.NEEDS_REVIEW);
        assertThat(result.notes()).hasSize(1);
        assertThat(result.notes().get(0)).contains("email").contains("Thiếu field bắt buộc");
    }

    @Test
    void validate_requiredFieldPresent_isReliable() {
        Schema<?> body = new Schema<>().required(List.of("email")).properties(Map.of("email", new Schema<>().type("string")));
        Endpoint endpoint = endpointWithBodySchema(body);

        TestCaseReliabilityValidator.Result result = validator.validate(endpoint, candidate(201, "{\"email\":\"a@b.com\"}"), TestCaseSource.AI_GENERATED);

        assertThat(result.status()).isEqualTo(TestCaseReliability.RELIABLE);
        assertThat(result.notes()).isEmpty();
    }

    @Test
    void validate_enumViolationOnSuccessCase_flagsNeedsReview() {
        Schema<?> statusField = new Schema<>().type("string")._enum(List.of("ACTIVE", "INACTIVE"));
        Schema<?> body = new Schema<>().properties(Map.of("status", statusField));
        Endpoint endpoint = endpointWithBodySchema(body);

        TestCaseReliabilityValidator.Result result = validator.validate(endpoint, candidate(200, "{\"status\":\"DELETED\"}"), TestCaseSource.AI_GENERATED);

        assertThat(result.status()).isEqualTo(TestCaseReliability.NEEDS_REVIEW);
        assertThat(result.notes().get(0)).contains("status").contains("enum");
    }

    @Test
    void validate_numberOutOfRangeOnSuccessCase_flagsNeedsReview() {
        Schema<?> quantity = new Schema<>().type("integer").minimum(BigDecimal.ONE).maximum(BigDecimal.valueOf(100));
        Schema<?> body = new Schema<>().properties(Map.of("quantity", quantity));
        Endpoint endpoint = endpointWithBodySchema(body);

        TestCaseReliabilityValidator.Result result = validator.validate(endpoint, candidate(200, "{\"quantity\":0}"), TestCaseSource.AI_GENERATED);

        assertThat(result.status()).isEqualTo(TestCaseReliability.NEEDS_REVIEW);
        assertThat(result.notes().get(0)).contains("quantity").contains("minimum");
    }

    @Test
    void validate_numberWithinRangeOnSuccessCase_isReliable() {
        Schema<?> quantity = new Schema<>().type("integer").minimum(BigDecimal.ONE).maximum(BigDecimal.valueOf(100));
        Schema<?> body = new Schema<>().properties(Map.of("quantity", quantity));
        Endpoint endpoint = endpointWithBodySchema(body);

        TestCaseReliabilityValidator.Result result = validator.validate(endpoint, candidate(200, "{\"quantity\":50}"), TestCaseSource.AI_GENERATED);

        assertThat(result.status()).isEqualTo(TestCaseReliability.RELIABLE);
    }

    @Test
    void validate_errorExpectingCaseWithRealViolation_isReliable() {
        // Case Negative cố ý vi phạm ràng buộc để test validation (thiếu field required) - đúng ý
        // đồ, không bị gắn cờ.
        Schema<?> body = new Schema<>().required(List.of("email")).properties(Map.of("email", new Schema<>().type("string")));
        Endpoint endpoint = endpointWithBodySchema(body);

        TestCaseReliabilityValidator.Result result = validator.validate(endpoint, candidate(400, "{}"), TestCaseSource.AI_GENERATED);

        assertThat(result.status()).isEqualTo(TestCaseReliability.RELIABLE);
        assertThat(result.notes()).isEmpty();
    }

    @Test
    void validate_errorExpectingCaseWithNoViolationFound_flagsNeedsReview() {
        // Module 15f: case tự nhận kỳ vọng lỗi (Negative) nhưng dữ liệu thực ra đầy đủ/hợp lệ theo
        // spec - không tìm được vi phạm nào, đáng ngờ tên/mô tả không khớp dữ liệu đã sinh.
        Schema<?> body = new Schema<>().required(List.of("email")).properties(Map.of("email", new Schema<>().type("string")));
        Endpoint endpoint = endpointWithBodySchema(body);

        TestCaseReliabilityValidator.Result result = validator.validate(
                endpoint, candidate(400, "{\"email\":\"a@b.com\"}"), TestCaseSource.AI_GENERATED);

        assertThat(result.status()).isEqualTo(TestCaseReliability.NEEDS_REVIEW);
        assertThat(result.notes().get(0)).contains("kỳ vọng lỗi");
    }

    @Test
    void validate_securityCaseWithNoViolationFound_staysReliable() {
        // Security luôn được loại trừ khỏi kiểm tra "case lỗi nhưng không vi phạm gì" - payload
        // injection có thể hợp lệ theo schema (vd field string bình thường) nhưng vẫn là tấn công.
        Schema<?> body = new Schema<>().required(List.of("email")).properties(Map.of("email", new Schema<>().type("string")));
        Endpoint endpoint = endpointWithBodySchema(body);

        TestCaseReliabilityValidator.Result result = validator.validate(
                endpoint, candidate(401, "{\"email\":\"a@b.com\"}"), TestCaseSource.SECURITY);

        assertThat(result.status()).isEqualTo(TestCaseReliability.RELIABLE);
        assertThat(result.notes()).isEmpty();
    }

    @Test
    void validate_pathParamViolatesEnum_flagsNeedsReview() {
        Schema<?> statusParamSchema = new Schema<>().type("string")._enum(List.of("available", "pending", "sold"));
        Parameter statusParam = new Parameter().name("status").in("query").required(false).schema(statusParamSchema);
        Endpoint endpoint = endpointWithParameters(List.of(statusParam));

        GeneratedTestCase withBadStatus = new GeneratedTestCase(
                "name", "desc", Map.of(), null, 200, "/pet/findByStatus?status={{status}}",
                Map.of("status", "unknown-value"), null, null);

        TestCaseReliabilityValidator.Result result = validator.validate(endpoint, withBadStatus, TestCaseSource.AI_GENERATED);

        assertThat(result.status()).isEqualTo(TestCaseReliability.NEEDS_REVIEW);
        assertThat(result.notes().get(0)).contains("status").contains("enum");
    }

    /**
     * Module 15h (sửa lỗi có sẵn) - prompt dặn AI ghi thẳng giá trị biên/sai của tham số query vào
     * resolvedPath, pathParamFallbacks để trống. Trước đây Tier 1 chỉ đọc pathParamFallbacks nên thấy
     * "kỳ vọng lỗi nhưng không vi phạm gì" và gắn NEEDS_REVIEW oan cho case hoàn toàn đúng.
     */
    @Test
    void validate_queryBoundaryWrittenLiterallyInUrl_isReadAndNotFlagged() {
        Schema<?> minPriceSchema = new Schema<>().type("integer").minimum(java.math.BigDecimal.ZERO);
        Endpoint endpoint = endpointWithParameters(List.of(new Parameter().name("minPrice").in("query").schema(minPriceSchema)));
        GeneratedTestCase belowMinimum = new GeneratedTestCase(
                "Boundary - minPrice am", "desc", Map.of(), null, 400, "/x?minPrice=-1", Map.of(), null, null);

        TestCaseReliabilityValidator.Result result = validator.validate(endpoint, belowMinimum, TestCaseSource.AI_GENERATED);

        assertThat(result.status()).isEqualTo(TestCaseReliability.RELIABLE);
    }

    @Test
    void validate_sameQueryParamInUrlAndFallback_readsValueActuallySent() {
        Schema<?> minPriceSchema = new Schema<>().type("integer").minimum(java.math.BigDecimal.ZERO);
        Endpoint endpoint = endpointWithParameters(List.of(new Parameter().name("minPrice").in("query").schema(minPriceSchema)));
        // URL gửi -1 (fallback 100 không bao giờ được dùng) - case kỳ vọng thành công phải bị gắn cờ.
        GeneratedTestCase sendsMinusOne = new GeneratedTestCase(
                "Positive", "desc", Map.of(), null, 200, "/x?minPrice=-1", Map.of("minPrice", "100"), null, null);

        TestCaseReliabilityValidator.Result result = validator.validate(endpoint, sendsMinusOne, TestCaseSource.AI_GENERATED);

        assertThat(result.status()).isEqualTo(TestCaseReliability.NEEDS_REVIEW);
        assertThat(result.notes().get(0)).contains("minPrice").contains("-1");
    }

    // ---- Kiểu dữ liệu + null (SpecValueRules) - phát hiện khi verify 15h bằng AI thật ----

    private Endpoint productEndpoint(boolean nameNullable) {
        Schema<?> name = new Schema<>().type("string");
        if (nameNullable) {
            name.nullable(true);
        }
        Map<String, Schema> props = new LinkedHashMap<>();
        props.put("name", name);
        props.put("price", new Schema<>().type("number"));
        Schema<?> body = new Schema<>().required(List.of("name", "price")).properties(props);
        return endpointWithBodySchema(body);
    }

    @Test
    void validate_negativeWrongType_isRecognisedAsViolationAndNotFlagged() {
        // Case thật: AI gửi "price": "rat-re" kỳ vọng 400 - trước đây Tier 1 không kiểm kiểu nên gắn cờ oan.
        TestCaseReliabilityValidator.Result result = validator.validate(productEndpoint(false),
                candidate(400, "{\"name\":\"Ban phim\",\"price\":\"rat-re\"}"), TestCaseSource.AI_GENERATED);

        assertThat(result.status()).isEqualTo(TestCaseReliability.RELIABLE);
    }

    @Test
    void validate_negativeNullOnNonNullableField_isRecognisedAsViolationAndNotFlagged() {
        // Case thật: "name": null kỳ vọng 400 - OpenAPI 3.0 type string không nullable thì null không hợp lệ.
        TestCaseReliabilityValidator.Result result = validator.validate(productEndpoint(false),
                candidate(400, "{\"name\":null,\"price\":599000}"), TestCaseSource.AI_GENERATED);

        assertThat(result.status()).isEqualTo(TestCaseReliability.RELIABLE);
    }

    @Test
    void validate_successCaseWithNullOnNonNullableField_flagsNeedsReview() {
        TestCaseReliabilityValidator.Result result = validator.validate(productEndpoint(false),
                candidate(200, "{\"name\":null,\"price\":599000}"), TestCaseSource.AI_GENERATED);

        assertThat(result.status()).isEqualTo(TestCaseReliability.NEEDS_REVIEW);
        assertThat(result.notes().get(0)).contains("name").contains("null");
    }

    @Test
    void validate_successCaseWithNullOnNullableField_isReliable() {
        TestCaseReliabilityValidator.Result result = validator.validate(productEndpoint(true),
                candidate(200, "{\"name\":null,\"price\":599000}"), TestCaseSource.AI_GENERATED);

        assertThat(result.status()).isEqualTo(TestCaseReliability.RELIABLE);
    }

    @Test
    void validate_successCaseWithNumberSentAsJsonString_flagsNeedsReview() {
        TestCaseReliabilityValidator.Result result = validator.validate(productEndpoint(false),
                candidate(200, "{\"name\":\"Ban phim\",\"price\":\"599000\"}"), TestCaseSource.AI_GENERATED);

        assertThat(result.status()).isEqualTo(TestCaseReliability.NEEDS_REVIEW);
        assertThat(result.notes().get(0)).contains("price").contains("kiểu");
    }

    @Test
    void validate_noSchemaInfo_isReliable() {
        Endpoint endpoint = Endpoint.builder().id(UUID.randomUUID()).path("/x").method("GET").schema(null).createdAt(Instant.now()).build();

        TestCaseReliabilityValidator.Result result = validator.validate(endpoint, candidate(200, null), TestCaseSource.AI_GENERATED);

        assertThat(result.status()).isEqualTo(TestCaseReliability.RELIABLE);
    }
}
