package com.aiapitesting.backend.service;

import com.aiapitesting.backend.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PagingSupportTest {

    @Test
    void resolve_appliesDefaultsAndClampsSize() {
        assertThat(PagingSupport.resolve(null, null, 50, 200)).isEqualTo(new PagingSupport.Paging(0, 50));
        assertThat(PagingSupport.resolve(3, 500, 50, 200)).isEqualTo(new PagingSupport.Paging(3, 200));
        assertThat(PagingSupport.resolve(0, 1, 50, 200).size()).isEqualTo(1);
    }

    @Test
    void resolve_rejectsNegativePageAndNonPositiveSize() {
        assertThatThrownBy(() -> PagingSupport.resolve(-1, 10, 50, 200)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> PagingSupport.resolve(0, 0, 50, 200)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> PagingSupport.resolve(0, -5, 50, 200)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void normalizeQuery_trimsCapsAndTreatsBlankAsNoFilter() {
        assertThat(PagingSupport.normalizeQuery(null, 10)).isNull();
        assertThat(PagingSupport.normalizeQuery("   ", 10)).isNull();
        assertThat(PagingSupport.normalizeQuery("  abc  ", 10)).isEqualTo("abc");
        assertThat(PagingSupport.normalizeQuery("abcdefghijklmnop", 5)).isEqualTo("abcde");
    }

    @Test
    void escapeLike_escapesWildcardsAndBackslash() {
        assertThat(PagingSupport.escapeLike("50%_off\\x")).isEqualTo("50\\%\\_off\\\\x");
    }

    @Test
    void normalizeMethod_upperCasesBlankMeansNoFilterUnknownIsRejected() {
        assertThat(PagingSupport.normalizeMethod(null)).isNull();
        assertThat(PagingSupport.normalizeMethod("  ")).isNull();
        assertThat(PagingSupport.normalizeMethod(" post ")).isEqualTo("POST");
        assertThatThrownBy(() -> PagingSupport.normalizeMethod("FETCH")).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void requireAllowedSort_usesDefaultOrRejectsUnknown() {
        Set<String> allowed = Set.of("endpoint", "name,asc");
        assertThat(PagingSupport.requireAllowedSort(null, "endpoint", allowed)).isEqualTo("endpoint");
        assertThat(PagingSupport.requireAllowedSort(" ", "endpoint", allowed)).isEqualTo("endpoint");
        assertThat(PagingSupport.requireAllowedSort("name,asc", "endpoint", allowed)).isEqualTo("name,asc");
        assertThatThrownBy(() -> PagingSupport.requireAllowedSort("id,desc", "endpoint", allowed))
                .isInstanceOf(InvalidRequestException.class);
    }
}
