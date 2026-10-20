package com.aiapitesting.backend.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Placeholder ngày động: thay lúc chạy theo UTC, token sai cú pháp bị phát hiện, không đụng placeholder tham số/dependency. */
class DynamicPlaceholderResolverTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2028-02-28T23:30:45.678Z"), ZoneOffset.UTC);
    private final TestCasePathValidator validator = new TestCasePathValidator();

    @Test
    void resolvesTodayAndNowTokens_inUtc_withRollover() {
        assertThat(DynamicPlaceholderResolver.resolve("{{today}}", CLOCK)).isEqualTo("2028-02-28");
        assertThat(DynamicPlaceholderResolver.resolve("{{today+1d}}", CLOCK)).isEqualTo("2028-02-29"); // năm nhuận
        assertThat(DynamicPlaceholderResolver.resolve("{{today+2d}}", CLOCK)).isEqualTo("2028-03-01");
        assertThat(DynamicPlaceholderResolver.resolve("{{today-30d}}", CLOCK)).isEqualTo("2028-01-29");
        assertThat(DynamicPlaceholderResolver.resolve("{{now}}", CLOCK)).isEqualTo("2028-02-28T23:30:45Z");
        assertThat(DynamicPlaceholderResolver.resolve("{{now+1h}}", CLOCK)).isEqualTo("2028-02-29T00:30:45Z");
        assertThat(DynamicPlaceholderResolver.resolve("{{now-15m}}", CLOCK)).isEqualTo("2028-02-28T23:15:45Z");
    }

    @Test
    void resolvesEveryTokenInABody_andLeavesOtherTextAndTokensAlone() {
        String body = "{\"deadline\":\"{{today+30d}}\",\"at\":\"{{now+1h}}\",\"ref\":\"{{jobId}}\",\"n\":5}";
        String resolved = DynamicPlaceholderResolver.resolve(body, CLOCK);
        assertThat(resolved).contains("\"deadline\":\"2028-03-29\"").contains("\"at\":\"2028-02-29T00:30:45Z\"")
                .contains("{{jobId}}").contains("\"n\":5");
    }

    @Test
    void malformedTokens_areLeftUntouchedAndFlagged() {
        assertThat(DynamicPlaceholderResolver.resolve("{{today+5x}} {{todya}}", CLOCK)).isEqualTo("{{today+5x}} {{todya}}");
        assertThat(DynamicPlaceholderResolver.firstMalformed("a {{today+5x}} b")).isEqualTo("{{today+5x}}");
        assertThat(DynamicPlaceholderResolver.firstMalformed("{{today+1h}}")).isEqualTo("{{today+1h}}"); // today chỉ nhận ngày
        assertThat(DynamicPlaceholderResolver.firstMalformed("{{now+99999d}}")).isNotNull();
        assertThat(DynamicPlaceholderResolver.firstMalformed("{{today}} {{now-15m}} {{jobId}} {{todayCount}}")).isNull();
        assertThat(DynamicPlaceholderResolver.firstMalformed(null)).isNull();
    }

    @Test
    void validatorTreatsDynamicTokensAsNeitherParametersNorMissingFallbacks() {
        assertThat(validator.extractPlaceholders("/jobs/{{jobId}}?from={{today}}&to={{today+7d}}")).isEqualTo(Set.of("jobId"));
        // không cần fallback cho {{today+1d}} trong path
        validator.validate("/jobs?until={{today+1d}}", null, IllegalStateException::new);
        assertThat(validator.substitute("/jobs/{{jobId}}?until={{today+1d}}", Map.of("jobId", "7")))
                .matches("/jobs/7\\?until=\\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    void validateDynamicTokens_rejectsMalformedInAnyField() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> validator.validateDynamicTokens(IllegalArgumentException::new, "/ok", "{\"d\":\"{{today+5x}}\"}", null));
        validator.validateDynamicTokens(IllegalArgumentException::new, "/ok", "{\"d\":\"{{today+5d}}\"}", null);
    }

    @Test
    void generationPrompt_tellsTheModelToUseDynamicDatesAndNeverHardCodeOne() throws IOException {
        String prompt = new String(getClass().getResourceAsStream("/prompts/generate-test-case.st").readAllBytes(), StandardCharsets.UTF_8);
        assertThat(prompt).contains("{{today+30d}}").contains("{{now+1h}}").contains("date-time").contains("KHÔNG BAO GIỜ ghi cứng");
    }

    @Test
    void resolveUnique_sinhGiaTriMoiMoiLanVaDungLaiTheoTen() {
        DynamicPlaceholderResolver.RunScope scope = new DynamicPlaceholderResolver.RunScope();
        String out = DynamicPlaceholderResolver.resolveUnique("{{uuid}}|{{uuid}}|{{uuid:a}}|{{uuid:a}}|{{runId}}|{{runId}}|{{rand}}", scope);
        String[] parts = out.split("\\|");
        assertThat(parts[0]).isNotEqualTo(parts[1]);
        assertThat(parts[2]).isEqualTo(parts[3]).hasSize(36);
        assertThat(parts[4]).isEqualTo(parts[5]).hasSize(8);
        assertThat(parts[6]).matches("\\d{6}");
        assertThat(DynamicPlaceholderResolver.resolveUnique("{{uuid}}", null)).isEqualTo("{{uuid}}");
    }
}
