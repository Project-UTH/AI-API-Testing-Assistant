package com.aiapitesting.backend.service.execution;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseSchemaValidatorTest {

    private static final String PRODUCT = """
            {"type":"object","required":["id","name","price"],
             "properties":{"id":{"type":"integer"},"name":{"type":"string"},"price":{"type":"number"},
                           "tags":{"type":"array","items":{"type":"string"}},
                           "note":{"type":"string","nullable":true}}}
            """;

    @Test
    void matchingResponse_hasNoViolations() {
        assertThat(ResponseSchemaValidator.validate(PRODUCT, "{\"id\":1,\"name\":\"a\",\"price\":9.5,\"tags\":[\"x\"],\"note\":null}"))
                .isEmpty();
    }

    @Test
    void missingRequiredField_isReportedWithItsPath() {
        List<String> violations = ResponseSchemaValidator.validate(PRODUCT, "{\"id\":1,\"price\":9.5}");

        assertThat(violations).containsExactly("$.name: thiếu trường bắt buộc");
    }

    @Test
    void wrongType_isReportedWithActualAndDeclaredType() {
        List<String> violations = ResponseSchemaValidator.validate(PRODUCT, "{\"id\":\"1\",\"name\":\"a\",\"price\":1}");

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("$.id").contains("string").contains("integer");
    }

    @Test
    void nullOnNonNullableField_isReported_butNullableFieldIsFine() {
        assertThat(ResponseSchemaValidator.validate(PRODUCT, "{\"id\":1,\"name\":null,\"price\":1}"))
                .hasSize(1).first().asString().contains("$.name").contains("null");
        assertThat(ResponseSchemaValidator.validate(PRODUCT, "{\"id\":1,\"name\":\"a\",\"price\":1,\"note\":null}")).isEmpty();
    }

    @Test
    void nullOnOptionalNonNullableField_isNotReported_becauseFrameworksRarelyMarkNullable() {
        String schema = "{\"type\":\"object\",\"required\":[\"id\"],\"properties\":{\"id\":{\"type\":\"integer\"},\"description\":{\"type\":\"string\"}}}";

        assertThat(ResponseSchemaValidator.validate(schema, "{\"id\":1,\"description\":null}")).isEmpty();
    }

    @Test
    void integerAcceptsWholeNumbersWrittenAsDecimal_numberAcceptsIntegers() {
        assertThat(ResponseSchemaValidator.validate(PRODUCT, "{\"id\":2.0,\"name\":\"a\",\"price\":5}")).isEmpty();
    }

    @Test
    void arrayItemsAreCheckedAndPathShowsTheIndex() {
        String list = "{\"type\":\"array\",\"items\":" + PRODUCT + "}";

        List<String> violations = ResponseSchemaValidator.validate(list, "[{\"id\":1,\"name\":\"a\",\"price\":1},{\"id\":2,\"price\":1}]");

        assertThat(violations).containsExactly("$[1].name: thiếu trường bắt buộc");
    }

    @Test
    void extraUnknownFields_areAllowed_becauseSpecsRarelyDeclareThemAll() {
        assertThat(ResponseSchemaValidator.validate(PRODUCT, "{\"id\":1,\"name\":\"a\",\"price\":1,\"createdAt\":\"2026\"}")).isEmpty();
    }

    @Test
    void typeIsInferredWhenSpecOmitsItButDeclaresProperties() {
        String schema = "{\"properties\":{\"id\":{\"type\":\"integer\"}},\"required\":[\"id\"]}";

        assertThat(ResponseSchemaValidator.validate(schema, "{}")).containsExactly("$.id: thiếu trường bắt buộc");
        assertThat(ResponseSchemaValidator.validate(schema, "[1]")).hasSize(1);
    }

    @Test
    void writeOnlyRequiredFieldIsNotExpectedInResponses() {
        String schema = "{\"type\":\"object\",\"required\":[\"password\"],\"properties\":{\"password\":{\"type\":\"string\",\"writeOnly\":true}}}";

        assertThat(ResponseSchemaValidator.validate(schema, "{}")).isEmpty();
    }

    @Test
    void allOfPartsAreAllChecked() {
        String schema = "{\"allOf\":[{\"type\":\"object\",\"required\":[\"a\"]},{\"type\":\"object\",\"required\":[\"b\"]}]}";

        assertThat(ResponseSchemaValidator.validate(schema, "{\"a\":1}")).containsExactly("$.b: thiếu trường bắt buộc");
    }

    @Test
    void oneOfAndFormatsAreIgnoredOnPurpose() {
        String schema = "{\"type\":\"object\",\"properties\":{\"when\":{\"type\":\"string\",\"format\":\"date-time\"},"
                + "\"x\":{\"oneOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}}}";

        assertThat(ResponseSchemaValidator.validate(schema, "{\"when\":\"not a date\",\"x\":true}")).isEmpty();
    }

    @Test
    void emptyOrNonJsonBody_isNotEnoughToConclude_soNoWarning() {
        // Spec hay khai "*/*" cho cả phản hồi không phải JSON: báo ở đây sẽ là báo sai.
        assertThat(ResponseSchemaValidator.validate(PRODUCT, "")).isEmpty();
        assertThat(ResponseSchemaValidator.validate(PRODUCT, "<html>oops</html>")).isEmpty();
    }

    @Test
    void brokenOrMissingSchema_neverProducesViolations() {
        assertThat(ResponseSchemaValidator.validate(null, "{}")).isEmpty();
        assertThat(ResponseSchemaValidator.validate("{not json", "{}")).isEmpty();
    }

    @Test
    void violationsAreCapped() {
        StringBuilder props = new StringBuilder();
        StringBuilder required = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            props.append(i > 0 ? "," : "").append("\"f").append(i).append("\":{\"type\":\"string\"}");
            required.append(i > 0 ? "," : "").append("\"f").append(i).append('"');
        }
        String schema = "{\"type\":\"object\",\"required\":[" + required + "],\"properties\":{" + props + "}}";

        assertThat(ResponseSchemaValidator.validate(schema, "{}")).hasSize(ResponseSchemaValidator.MAX_VIOLATIONS);
    }
}
