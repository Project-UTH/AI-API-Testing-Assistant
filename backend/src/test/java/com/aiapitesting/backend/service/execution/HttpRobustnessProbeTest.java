package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.service.execution.HttpRobustnessProbe.Variant;
import com.aiapitesting.backend.service.execution.MassAssignmentProbe.Verdict;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 17q G4 - chấm probe độ bền xử lý request lỗi định dạng và phát hiện rò rỉ chi tiết lỗi. */
class HttpRobustnessProbeTest {

    @Test
    void malformedBody_isNeverValidJson_forObjectsArraysAndGarbage() {
        for (String valid : new String[]{"{\"a\":1,\"b\":\"x\"}", "[1,2,3]", "{}", "", null, "chuỗi thường"}) {
            String broken = HttpRobustnessProbe.malformedBody(valid);
            assertThatThrownBy(() -> new ObjectMapper().readTree(broken)).as("body=%s -> %s", valid, broken).isInstanceOf(Exception.class);
        }
    }

    @Test
    void malformedJson_serverErrorFails_acceptedFails_400And422Pass_authCodesAreInconclusive() {
        MassAssignmentProbe.Outcome error = HttpRobustnessProbe.evaluate(Variant.MALFORMED_JSON, 500);
        assertThat(error.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(error.finding().rule()).isEqualTo(HttpRobustnessProbe.RULE_SERVER_ERROR);
        assertThat(error.finding().severity()).isEqualTo(SensitiveDataScanner.Severity.HIGH);

        MassAssignmentProbe.Outcome accepted = HttpRobustnessProbe.evaluate(Variant.MALFORMED_JSON, 201);
        assertThat(accepted.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(accepted.finding().rule()).isEqualTo(HttpRobustnessProbe.RULE_ACCEPTED);

        assertThat(HttpRobustnessProbe.evaluate(Variant.MALFORMED_JSON, 400).verdict()).isEqualTo(Verdict.PASS);
        assertThat(HttpRobustnessProbe.evaluate(Variant.MALFORMED_JSON, 422).verdict()).isEqualTo(Verdict.PASS);
        // 401 thay vì 400 KHÔNG được coi là đạt: rơi về so sánh mã thường (kỳ vọng 400) nên vẫn là Fail có nhãn
        for (int status : new int[]{401, 403, 404, 409}) {
            assertThat(HttpRobustnessProbe.evaluate(Variant.MALFORMED_JSON, status).verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        }
    }

    @Test
    void wrongContentType_415Passes_serverErrorAndAcceptedFail() {
        assertThat(HttpRobustnessProbe.evaluate(Variant.WRONG_CONTENT_TYPE, 415).verdict()).isEqualTo(Verdict.PASS);
        assertThat(HttpRobustnessProbe.evaluate(Variant.WRONG_CONTENT_TYPE, 400).verdict()).isEqualTo(Verdict.PASS);
        assertThat(HttpRobustnessProbe.evaluate(Variant.WRONG_CONTENT_TYPE, 503).verdict()).isEqualTo(Verdict.FAIL);
        assertThat(HttpRobustnessProbe.evaluate(Variant.WRONG_CONTENT_TYPE, 200).verdict()).isEqualTo(Verdict.FAIL);
        assertThat(Variant.WRONG_CONTENT_TYPE.expectedStatus()).isEqualTo(415);
        assertThat(Variant.MALFORMED_JSON.expectedStatus()).isEqualTo(400);
    }

    @Test
    void noStatusOrUnknownVariant_isInconclusive_andProbeJsonRoundTrips() {
        assertThat(HttpRobustnessProbe.evaluate(Variant.MALFORMED_JSON, null).verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(HttpRobustnessProbe.evaluate(null, 500).verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        for (Variant v : Variant.values()) {
            assertThat(HttpRobustnessProbe.variantOf(HttpRobustnessProbe.probeJson(v))).isEqualTo(v);
        }
        assertThat(HttpRobustnessProbe.variantOf("{\"variant\":\"KHONG_CO\"}")).isNull();
        assertThat(HttpRobustnessProbe.variantOf(null)).isNull();
    }

    @Test
    void errorLeak_detectsStackTraceSqlAndInternalClasses_withoutEchoingTheText() {
        String javaTrace = "{\"error\":\"boom\",\"trace\":\"java.lang.NullPointerException\\n\\tat com.shop.JobService.save(JobService.java:42)\"}";
        String raw = "Exception in thread \"main\" java.lang.IllegalStateException\n\tat com.shop.A.b(A.java:10)\n";
        assertThat(SensitiveDataScanner.scanErrorLeak(raw)).extracting(SensitiveDataScanner.Finding::rule)
                .contains("ERROR_LEAK_STACKTRACE", "ERROR_LEAK_CLASS");
        assertThat(SensitiveDataScanner.scanErrorLeak(javaTrace)).extracting(SensitiveDataScanner.Finding::rule).contains("ERROR_LEAK_CLASS");
        assertThat(SensitiveDataScanner.scanErrorLeak("You have an error in your SQL syntax near 'x'")).extracting(SensitiveDataScanner.Finding::rule)
                .containsExactly("ERROR_LEAK_SQL");
        assertThat(SensitiveDataScanner.scanErrorLeak("Traceback (most recent call last):\n  File \"app.py\"")).extracting(SensitiveDataScanner.Finding::rule)
                .contains("ERROR_LEAK_STACKTRACE");
        assertThat(SensitiveDataScanner.scanErrorLeak(raw).toString()).doesNotContain("JobService").doesNotContain("main");
    }

    @Test
    void errorLeak_ignoresNormalResponses() {
        assertThat(SensitiveDataScanner.scanErrorLeak("{\"id\":1,\"title\":\"Tuyển dụng Java Spring\",\"url\":\"https://example.com/a\"}")).isEmpty();
        assertThat(SensitiveDataScanner.scanErrorLeak("{\"message\":\"Dữ liệu không hợp lệ\",\"code\":\"VALIDATION_ERROR\"}")).isEmpty();
        assertThat(SensitiveDataScanner.scanErrorLeak(null)).isEmpty();
        assertThat(SensitiveDataScanner.scanErrorLeak("  ")).isEmpty();
    }
}
