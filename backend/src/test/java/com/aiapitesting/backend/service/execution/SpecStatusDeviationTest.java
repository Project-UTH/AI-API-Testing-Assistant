package com.aiapitesting.backend.service.execution;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SpecStatusDeviationTest {

    @Test
    void successCodeOutsideDeclaredSet_warns() {
        assertThat(SpecStatusDeviation.check(Set.of("201", "400"), 200, 200))
                .hasValueSatisfying(w -> assertThat(w).contains("[201]").contains("API trả 200"));
        assertThat(SpecStatusDeviation.check(Set.of("200", "201"), 200, 204)).isPresent();
    }

    @Test
    void codeInsideDeclaredSet_doesNotWarn() {
        assertThat(SpecStatusDeviation.check(Set.of("200", "201"), 201, 201)).isEmpty();
    }

    @Test
    void springdocDefaultOnly200_doesNotWarn_acceptedFalseNegative() {
        assertThat(SpecStatusDeviation.check(Set.of("200"), 201, 201)).isEmpty();
        assertThat(SpecStatusDeviation.check(Set.of("200", "400", "401"), 201, 201)).isEmpty();
    }

    @Test
    void wildcardAndDefaultAreIgnored() {
        assertThat(SpecStatusDeviation.check(Set.of("201", "2XX"), 200, 200)).isPresent();
        assertThat(SpecStatusDeviation.check(Set.of("default"), 200, 200)).isEmpty();
        assertThat(SpecStatusDeviation.check(Set.of("2XX"), 200, 200)).isEmpty();
    }

    @Test
    void noDeclared2xx_emptyOrNull_doesNotWarn() {
        assertThat(SpecStatusDeviation.check(Set.of("400", "404"), 200, 200)).isEmpty();
        assertThat(SpecStatusDeviation.check(Set.of(), 200, 200)).isEmpty();
        assertThat(SpecStatusDeviation.check(null, 200, 200)).isEmpty();
    }

    @Test
    void onlyAppliesWhenBothExpectedAndActualAre2xx() {
        assertThat(SpecStatusDeviation.check(Set.of("201"), 400, 200)).isEmpty(); // test âm đã Fail với nhãn riêng
        assertThat(SpecStatusDeviation.check(Set.of("201"), 200, 404)).isEmpty();
        assertThat(SpecStatusDeviation.check(Set.of("201"), 200, null)).isEmpty();
        assertThat(SpecStatusDeviation.check(Set.of("201"), null, 200)).isEmpty();
    }

    @Test
    void declaredStatusCodes_readsStoredSchema_andToleratesMissingOrBrokenJson() {
        assertThat(SpecStatusDeviation.declaredStatusCodes("{\"responseStatusCodes\":[\" 201 \",\"400\"]}"))
                .containsExactly("201", "400");
        assertThat(SpecStatusDeviation.declaredStatusCodes(null)).isEmpty();
        assertThat(SpecStatusDeviation.declaredStatusCodes("{\"fields\":[]}")).isEmpty();
        assertThat(SpecStatusDeviation.declaredStatusCodes("{broken")).isEmpty();
    }
}
