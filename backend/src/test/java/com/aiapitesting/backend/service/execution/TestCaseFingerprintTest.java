package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.entity.AssertionOperator;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseAuthOverride;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Module 16 - dấu vân tay phiên bản test: đổi điều test kiểm tra thì đổi, đổi tên/mô tả thì không. */
class TestCaseFingerprintTest {

    private final Endpoint endpoint = Endpoint.builder().id(UUID.randomUUID()).method("POST").path("/users").build();

    private TestCase.TestCaseBuilder base() {
        return TestCase.builder().endpoint(endpoint).name("n").description("d").resolvedPath("/users")
                .requestBody("{\"email\":\"a@b.com\"}").expectedStatus(201).authOverride(TestCaseAuthOverride.DEFAULT);
    }

    private String of(TestCase testCase) {
        return TestCaseFingerprint.of(testCase, List.of(), List.of());
    }

    @Test
    void sameContentGivesSameFingerprint_sha256Hex() {
        assertThat(of(base().build())).isEqualTo(of(base().build())).matches("[0-9a-f]{64}");
    }

    @Test
    void renamingOrRedescribingDoesNotChangeFingerprint() {
        assertThat(of(base().name("khac").description("mo ta khac").build())).isEqualTo(of(base().build()));
    }

    @Test
    void anythingThatChangesWhatIsTestedChangesFingerprint() {
        String original = of(base().build());

        assertThat(of(base().expectedStatus(422).build())).isNotEqualTo(original);
        assertThat(of(base().requestBody("{\"email\":\"x@y.com\"}").build())).isNotEqualTo(original);
        assertThat(of(base().resolvedPath("/users?a=1").build())).isNotEqualTo(original);
        assertThat(of(base().requestHeaders("{\"X\":\"1\"}").build())).isNotEqualTo(original);
        assertThat(of(base().authOverride(TestCaseAuthOverride.NONE).build())).isNotEqualTo(original);
        assertThat(of(base().pathParamFallbacks("{\"id\":\"1\"}").build())).isNotEqualTo(original);
        Endpoint other = Endpoint.builder().id(UUID.randomUUID()).method("PUT").path("/users").build();
        assertThat(of(base().endpoint(other).build())).isNotEqualTo(original);
    }

    @Test
    void assertionsAndDependenciesCountButOrderDoesNot() {
        TestCase testCase = base().build();
        AssertionSpec a = new AssertionSpec("id", AssertionOperator.EXISTS, null);
        AssertionSpec b = new AssertionSpec("name", AssertionOperator.EQUALS, "x");
        DependencyEdge e1 = new DependencyEdge(UUID.randomUUID(), "src", "$.id", "userId");
        DependencyEdge e2 = new DependencyEdge(UUID.randomUUID(), "src2", "$.id", "orderId");

        String withAssertions = TestCaseFingerprint.of(testCase, List.of(a, b), List.of());

        assertThat(withAssertions).isNotEqualTo(of(testCase));
        assertThat(TestCaseFingerprint.of(testCase, List.of(b, a), List.of())).isEqualTo(withAssertions);
        assertThat(TestCaseFingerprint.of(testCase, List.of(), List.of(e1, e2)))
                .isEqualTo(TestCaseFingerprint.of(testCase, List.of(), List.of(e2, e1)))
                .isNotEqualTo(of(testCase));
    }

    @Test
    void adjacentFieldsCannotBeConfusedByShiftingCharactersBetweenThem() {
        // "ab" + "c" khác "a" + "bc": độ dài đứng trước từng trường chặn nhập nhằng ranh giới.
        String first = of(base().resolvedPath("ab").requestHeaders("c").build());
        String second = of(base().resolvedPath("a").requestHeaders("bc").build());

        assertThat(first).isNotEqualTo(second);
    }
}
