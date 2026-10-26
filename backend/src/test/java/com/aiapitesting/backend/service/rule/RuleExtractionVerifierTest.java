package com.aiapitesting.backend.service.rule;

import com.aiapitesting.backend.entity.RuleSource;
import com.aiapitesting.backend.entity.RuleTemplate;
import com.aiapitesting.backend.service.rule.RuleExtractionVerifier.DropReason;
import com.aiapitesting.backend.service.rule.RuleExtractionVerifier.EndpointInfo;
import com.aiapitesting.backend.service.rule.RuleExtractionVerifier.Proposed;
import com.aiapitesting.backend.service.rule.RuleExtractionVerifier.Source;
import com.aiapitesting.backend.service.rule.RuleExtractionVerifier.Verdict;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.aiapitesting.backend.service.rule.RuleParamsValidatorTest.array;
import static com.aiapitesting.backend.service.rule.RuleParamsValidatorTest.num;
import static com.aiapitesting.backend.service.rule.RuleParamsValidatorTest.spec;
import static com.aiapitesting.backend.service.rule.RuleParamsValidatorTest.text;
import static org.assertj.core.api.Assertions.assertThat;

class RuleExtractionVerifierTest {

    private static final String KEY = "POST /api/jobs";
    private static final Map<String, EndpointInfo> ENDPOINTS = Map.of(KEY, new EndpointInfo(
            spec(num("salaryMin", true, null, null), num("salaryMax", true, null, null), text("deadline", true),
                    num("years", false, null, null), array("criteria"), num("criteria[0].weight", false, null, null)), "POST"));

    private static final UUID DOC_ID = UUID.randomUUID();
    private static final String DOC = "# Tin tuyển dụng\n\nLương tối thiểu\nkhông được lớn hơn lương tối đa của tin.\n\n"
            + "Số năm kinh nghiệm phải nằm trong khoảng từ 0 đến 50. Nếu sai hệ thống trả lỗi 422.\n"
            + "Tổng trọng số các tiêu chí phải bằng 100.";
    private static final List<Source> SOURCES = List.of(new Source(DOC_ID, "yeu-cau.md", DOC, RuleSource.DOC));

    private static RuleParams compare() {
        return new RuleParams("salaryMin", "LTE", "salaryMax", null, null, null, null, null, null, null, null, null, null, null);
    }

    private static RuleParams range(int min, int max) {
        return new RuleParams(null, null, null, "years", null, null, null, null, new BigDecimal(min), new BigDecimal(max), null, null, null, null);
    }

    private static Proposed proposed(String template, RuleParams params, Integer status, String quote) {
        return new Proposed(KEY, template, params, status, quote, "ý nghĩa luật", null);
    }

    @Test
    void verbatimQuote_becomesDocWithRealOffsetAndOriginalText_evenWhenWhitespaceAndQuotesDiffer() {
        // AI gộp 2 dòng thành 1 và dùng khoảng trắng khác
        Verdict v = RuleExtractionVerifier.verify(
                proposed("COMPARE_FIELDS", compare(), null, "Lương tối thiểu   không được lớn hơn lương tối đa của tin."), ENDPOINTS, SOURCES);

        assertThat(v.dropped()).isNull();
        assertThat(v.source()).isEqualTo(RuleSource.DOC);
        assertThat(v.documentId()).isEqualTo(DOC_ID);
        assertThat(v.quote()).isEqualTo("Lương tối thiểu\nkhông được lớn hơn lương tối đa của tin."); // đoạn GỐC, đúng nguyên văn
        assertThat(DOC.substring(v.quoteOffset(), v.quoteOffset() + v.quote().length())).isEqualTo(v.quote());
        assertThat(v.rationale()).doesNotStartWith("GIẢ ĐỊNH");
    }

    @Test
    void fabricatedOrTooShortOrTooLongQuote_isDowngradedToInferredNotDropped() {
        for (String quote : new String[]{"Lương tối thiểu luôn nhỏ hơn mức trần của công ty theo quy định mới.", "ngắn quá", null, " ", "x".repeat(401)}) {
            Verdict v = RuleExtractionVerifier.verify(proposed("COMPARE_FIELDS", compare(), null, quote), ENDPOINTS, SOURCES);
            assertThat(v.dropped()).isNull();
            assertThat(v.source()).as("quote=%s", quote).isEqualTo(RuleSource.INFERRED);
            assertThat(v.quote()).isNull();
            assertThat(v.documentId()).isNull();
            assertThat(v.rationale()).startsWith("GIẢ ĐỊNH:");
        }
    }

    @Test
    void quoteFoundOnlyInInstruction_isInstructionSource_withoutDocumentId() {
        String instruction = "Chú ý: deadline của tin không được ở quá khứ so với ngày đăng tin.";
        List<Source> sources = List.of(new Source(DOC_ID, "yeu-cau.md", DOC, RuleSource.DOC),
                new Source(null, "chỉ dẫn", instruction, RuleSource.INSTRUCTION));
        RuleParams date = new RuleParams(null, "GTE", null, "deadline", null, null, 0, "DAY", null, null, null, null, null, null);

        Verdict v = RuleExtractionVerifier.verify(
                proposed("DATE_RELATIVE", date, null, "deadline của tin không được ở quá khứ so với ngày đăng tin."), ENDPOINTS, sources);

        assertThat(v.source()).isEqualTo(RuleSource.INSTRUCTION);
        assertThat(v.documentId()).isNull();
        assertThat(instruction.substring(v.quoteOffset())).startsWith("deadline");
    }

    @Test
    void constantsInParamsMustAppearInTheQuote_otherwiseDowngraded() {
        String quote = "Số năm kinh nghiệm phải nằm trong khoảng từ 0 đến 50.";
        assertThat(RuleExtractionVerifier.verify(proposed("VALUE_RANGE", range(0, 50), null, quote), ENDPOINTS, SOURCES).source())
                .isEqualTo(RuleSource.DOC);
        // AI tự đổi con số 50 thành 60 nhưng vẫn dán đúng đoạn trích
        Verdict wrong = RuleExtractionVerifier.verify(proposed("VALUE_RANGE", range(0, 60), null, quote), ENDPOINTS, SOURCES);
        assertThat(wrong.source()).isEqualTo(RuleSource.INFERRED);
        assertThat(wrong.note()).contains("không chứa các con số");
        assertThat(wrong.quote()).isNull();
    }

    @Test
    void numbersIn_handlesThousandAndDecimalSeparators() {
        assertThat(RuleExtractionVerifier.numbersIn("lương tối đa 1.000.000 đồng")).contains("1000000");
        assertThat(RuleExtractionVerifier.numbersIn("mức 2,5 lần")).contains("2.5").contains("25");
        assertThat(RuleExtractionVerifier.numbersIn("không có số")).isEmpty();
        // không tách mảnh: "2,5" không được coi là có số 5, "1.000" không có số 1
        assertThat(RuleExtractionVerifier.numbersIn("mức 2,5 lần")).doesNotContain("5", "2");
        assertThat(RuleExtractionVerifier.numbersIn("tối đa 1.000 đồng")).doesNotContain("1", "000");
    }

    @Test
    void expectedStatus_keptOnlyWhenTheQuoteStatesThatExactCode() {
        String quote = "Số năm kinh nghiệm phải nằm trong khoảng từ 0 đến 50. Nếu sai hệ thống trả lỗi 422.";
        assertThat(RuleExtractionVerifier.verify(proposed("VALUE_RANGE", range(0, 50), 422, quote), ENDPOINTS, SOURCES).expectedStatus()).isEqualTo(422);
        assertThat(RuleExtractionVerifier.verify(proposed("VALUE_RANGE", range(0, 50), 409, quote), ENDPOINTS, SOURCES).expectedStatus()).isNull();
        assertThat(RuleExtractionVerifier.verify(proposed("VALUE_RANGE", range(0, 50), 200, quote), ENDPOINTS, SOURCES).expectedStatus()).isNull();
        // mã 42 không được coi là có trong "422"
        assertThat(RuleExtractionVerifier.verify(proposed("VALUE_RANGE", range(0, 50), 42, quote), ENDPOINTS, SOURCES).expectedStatus()).isNull();
    }

    @Test
    void invalidEndpointTemplateOrParams_areDroppedWithTheirOwnReason() {
        Verdict unknownEndpoint = RuleExtractionVerifier.verify(
                new Proposed("GET /khong/co", "COMPARE_FIELDS", compare(), null, null, null, null), ENDPOINTS, SOURCES);
        assertThat(unknownEndpoint.dropped()).isEqualTo(DropReason.UNKNOWN_ENDPOINT);

        Verdict unknownTemplate = RuleExtractionVerifier.verify(proposed("MAGIC_RULE", compare(), null, null), ENDPOINTS, SOURCES);
        assertThat(unknownTemplate.dropped()).isEqualTo(DropReason.UNKNOWN_TEMPLATE);

        RuleParams badField = new RuleParams("salaryMin", "LTE", "khongCoField", null, null, null, null, null, null, null, null, null, null, null);
        Verdict invalid = RuleExtractionVerifier.verify(proposed("COMPARE_FIELDS", badField, null, null), ENDPOINTS, SOURCES);
        assertThat(invalid.dropped()).isEqualTo(DropReason.INVALID_PARAMS);
        assertThat(invalid.dropDetail()).contains("khongCoField");
    }

    @Test
    void templateNameIsCaseInsensitive_andParamsAreNormalized() {
        Verdict v = RuleExtractionVerifier.verify(proposed("compare_fields", new RuleParams(" salaryMin ", "lte", "salaryMax", null, null, null, null, null,
                null, null, null, null, null, null), null, null), ENDPOINTS, SOURCES);
        assertThat(v.dropped()).isNull();
        assertThat(v.template()).isEqualTo(RuleTemplate.COMPARE_FIELDS);
        assertThat(v.params().left()).isEqualTo("salaryMin");
        assertThat(v.params().op()).isEqualTo("LTE");
    }

    @Test
    void collapse_mapsEveryNormalizedCharBackToTheOriginalIndex() {
        String original = "A  “xin”\n\nchào";
        var collapsed = RuleExtractionVerifier.collapse(original);
        assertThat(collapsed.text()).isEqualTo("A \"xin\" chào");
        assertThat(collapsed.map()).hasSize(collapsed.text().length());
        assertThat(original.charAt(collapsed.map()[0])).isEqualTo('A');
        assertThat(original.charAt(collapsed.map()[collapsed.text().indexOf('c')])).isEqualTo('c');
    }
}
