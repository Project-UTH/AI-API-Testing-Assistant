package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.TestCaseReliability;
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
        return new GeneratedTestCase("name", "desc", Map.of(), requestBody, expectedStatus, "/x", Map.of(), null, null, null);
    }

    @Test
    void validate_requiredFieldMissingFromSuccessCase_flagsNeedsReview() {
        Schema<?> body = new Schema<>().required(List.of("email")).properties(Map.of("email", new Schema<>().type("string")));
        Endpoint endpoint = endpointWithBodySchema(body);

        TestCaseReliabilityValidator.Result result = validator.validate(endpoint, candidate(201, "{}"));

        assertThat(result.status()).isEqualTo(TestCaseReliability.NEEDS_REVIEW);
        assertThat(result.notes()).hasSize(1);
        assertThat(result.notes().get(0)).contains("email").contains("Thiếu field bắt buộc");
    }

    @Test
    void validate_requiredFieldPresent_isReliable() {
        Schema<?> body = new Schema<>().required(List.of("email")).properties(Map.of("email", new Schema<>().type("string")));
        Endpoint endpoint = endpointWithBodySchema(body);

        TestCaseReliabilityValidator.Result result = validator.validate(endpoint, candidate(201, "{\"email\":\"a@b.com\"}"));

        assertThat(result.status()).isEqualTo(TestCaseReliability.RELIABLE);
        assertThat(result.notes()).isEmpty();
    }

    @Test
    void validate_enumViolationOnSuccessCase_flagsNeedsReview() {
        Schema<?> statusField = new Schema<>().type("string")._enum(List.of("ACTIVE", "INACTIVE"));
        Schema<?> body = new Schema<>().properties(Map.of("status", statusField));
        Endpoint endpoint = endpointWithBodySchema(body);

        TestCaseReliabilityValidator.Result result = validator.validate(endpoint, candidate(200, "{\"status\":\"DELETED\"}"));

        assertThat(result.status()).isEqualTo(TestCaseReliability.NEEDS_REVIEW);
        assertThat(result.notes().get(0)).contains("status").contains("enum");
    }

    @Test
    void validate_numberOutOfRangeOnSuccessCase_flagsNeedsReview() {
        Schema<?> quantity = new Schema<>().type("integer").minimum(BigDecimal.ONE).maximum(BigDecimal.valueOf(100));
        Schema<?> body = new Schema<>().properties(Map.of("quantity", quantity));
        Endpoint endpoint = endpointWithBodySchema(body);

        TestCaseReliabilityValidator.Result result = validator.validate(endpoint, candidate(200, "{\"quantity\":0}"));

        assertThat(result.status()).isEqualTo(TestCaseReliability.NEEDS_REVIEW);
        assertThat(result.notes().get(0)).contains("quantity").contains("minimum");
    }

    @Test
    void validate_numberWithinRangeOnSuccessCase_isReliable() {
        Schema<?> quantity = new Schema<>().type("integer").minimum(BigDecimal.ONE).maximum(BigDecimal.valueOf(100));
        Schema<?> body = new Schema<>().properties(Map.of("quantity", quantity));
        Endpoint endpoint = endpointWithBodySchema(body);

        TestCaseReliabilityValidator.Result result = validator.validate(endpoint, candidate(200, "{\"quantity\":50}"));

        assertThat(result.status()).isEqualTo(TestCaseReliability.RELIABLE);
    }

    @Test
    void validate_errorExpectingCase_neverFlaggedEvenWithViolations() {
        // Case Negative/Boundary cố ý vi phạm ràng buộc để test validation - không được đối chiếu.
        Schema<?> body = new Schema<>().required(List.of("email")).properties(Map.of("email", new Schema<>().type("string")));
        Endpoint endpoint = endpointWithBodySchema(body);

        TestCaseReliabilityValidator.Result result = validator.validate(endpoint, candidate(400, "{}"));

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
                Map.of("status", "unknown-value"), null, null, null);

        TestCaseReliabilityValidator.Result result = validator.validate(endpoint, withBadStatus);

        assertThat(result.status()).isEqualTo(TestCaseReliability.NEEDS_REVIEW);
        assertThat(result.notes().get(0)).contains("status").contains("enum");
    }

    @Test
    void validate_noSchemaInfo_isReliable() {
        Endpoint endpoint = Endpoint.builder().id(UUID.randomUUID()).path("/x").method("GET").schema(null).createdAt(Instant.now()).build();

        TestCaseReliabilityValidator.Result result = validator.validate(endpoint, candidate(200, null));

        assertThat(result.status()).isEqualTo(TestCaseReliability.RELIABLE);
    }
}
