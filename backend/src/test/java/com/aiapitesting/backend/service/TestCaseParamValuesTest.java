package com.aiapitesting.backend.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestCaseParamValuesTest {

    @Test
    void resolve_literalQueryValueInUrl_isRead() {
        Map<String, String> values = TestCaseParamValues.resolve("/api/products", "/api/products?minPrice=-1", Map.of());

        assertThat(values).containsEntry("minPrice", "-1");
    }

    @Test
    void resolve_sameParamInUrlAndFallback_urlWins() {
        // Engine chỉ thay token {{ten}} - URL ghi thẳng -1 thì giá trị gửi đi chắc chắn là -1, không phải 100.
        Map<String, String> values = TestCaseParamValues.resolve(
                "/api/products", "/api/products?minPrice=-1", Map.of("minPrice", "100"));

        assertThat(values).containsEntry("minPrice", "-1");
    }

    @Test
    void resolve_placeholderInUrl_usesFallbackOfThatToken() {
        Map<String, String> values = TestCaseParamValues.resolve(
                "/pet/{petId}/stock", "/pet/{{petId}}/stock?quantity={{quantity}}",
                Map.of("petId", "7", "quantity", "3"));

        assertThat(values).containsEntry("petId", "7").containsEntry("quantity", "3");
    }

    @Test
    void resolve_literalPathSegment_matchedByTemplatePosition() {
        Map<String, String> values = TestCaseParamValues.resolve(
                "/pet/{petId}/stock", "/pet/0/stock?quantity={{quantity}}", Map.of("quantity", "3"));

        assertThat(values).containsEntry("petId", "0").containsEntry("quantity", "3");
    }

    @Test
    void resolve_encodedQueryValue_isDecoded() {
        Map<String, String> values = TestCaseParamValues.resolve("/items", "/items?name=%C3%81o%20thun", Map.of());

        assertThat(values).containsEntry("name", "Áo thun");
    }

    @Test
    void resolve_noResolvedPath_keepsFallbacks() {
        assertThat(TestCaseParamValues.resolve("/items", null, Map.of("a", "1"))).containsEntry("a", "1");
    }
}
