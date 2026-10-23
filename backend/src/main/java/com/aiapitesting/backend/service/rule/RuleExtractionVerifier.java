package com.aiapitesting.backend.service.rule;

import com.aiapitesting.backend.entity.RuleSource;
import com.aiapitesting.backend.entity.RuleTemplate;
import com.aiapitesting.backend.exception.BusinessRuleException;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner.Spec;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 17q G3.3 - KIỂM CHỨNG TẤT ĐỊNH đầu ra của AI trước khi lưu luật (hàm thuần, không đụng DB). AI chỉ ĐỀ XUẤT; mọi thứ nó nói đều phải qua cổng:
 * endpoint phải thuộc lô đã gửi; mẫu phải thuộc enum; tham số phải qua {@link RuleParamsValidator} (field có thật, kiểu khớp); đoạn trích phải là
 * chuỗi con NGUYÊN VĂN của tài liệu/chỉ dẫn (sau chuẩn hoá khoảng trắng, nháy cong); mọi hằng số trong tham số phải có trong đoạn trích; mã lỗi chỉ
 * giữ khi đoạn trích nêu đúng mã đó. Không đạt phần trích dẫn → hạ xuống INFERRED (GIẢ ĐỊNH, không có trích dẫn) thay vì bỏ - người duyệt quyết định.
 */
public final class RuleExtractionVerifier {

    public static final int QUOTE_MIN = 15;
    public static final int QUOTE_MAX = 400;

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");

    private RuleExtractionVerifier() {
    }

    /** Luật AI đề xuất (chưa tin). template để String để giá trị lạ không làm hỏng cả lần parse. */
    public record Proposed(String endpoint, String template, RuleParams params, Integer expectedStatus, String quote, String rationale) {
    }

    /** Nguồn văn bản để kiểm đoạn trích (tài liệu đã chuẩn hoá NFC, hoặc ô chỉ dẫn). */
    public record Source(UUID documentId, String name, String text, RuleSource kind) {
    }

    /** Thông tin endpoint của lô: khoá + spec + method (cho UNIQUE). */
    public record EndpointInfo(Spec spec, String method) {
    }

    public enum DropReason {
        UNKNOWN_ENDPOINT, UNKNOWN_TEMPLATE, INVALID_PARAMS
    }

    /** @param dropped != null → bỏ luật (không lưu được); ngược lại lưu với nguồn/đoạn trích đã chốt. */
    public record Verdict(
            DropReason dropped, String dropDetail, String endpointKey, RuleTemplate template, RuleParams params, RuleSource source,
            UUID documentId, String quote, Integer quoteOffset, Integer expectedStatus, String rationale, String note
    ) {
        static Verdict drop(DropReason reason, String detail) {
            return new Verdict(reason, detail, null, null, null, null, null, null, null, null, null, null);
        }

        public boolean hasQuote() {
            return quote != null;
        }
    }

    public static Verdict verify(Proposed p, Map<String, EndpointInfo> endpoints, List<Source> sources) {
        if (p == null || p.endpoint() == null || !endpoints.containsKey(p.endpoint().trim())) {
            return Verdict.drop(DropReason.UNKNOWN_ENDPOINT, p == null ? null : p.endpoint());
        }
        String endpointKey = p.endpoint().trim();
        RuleTemplate template;
        try {
            template = RuleTemplate.valueOf(p.template() == null ? "" : p.template().trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Verdict.drop(DropReason.UNKNOWN_TEMPLATE, p.template());
        }
        EndpointInfo info = endpoints.get(endpointKey);
        RuleParams params;
        try {
            params = RuleParamsValidator.validate(template, p.params(), info.spec(), info.method());
        } catch (BusinessRuleException e) {
            return Verdict.drop(DropReason.INVALID_PARAMS, e.getMessage());
        }

        QuoteMatch match = findQuote(p.quote(), sources);
        RuleSource source = RuleSource.INFERRED;
        UUID documentId = null;
        String quote = null;
        Integer offset = null;
        String note = null;
        if (match != null && constantsAppearIn(template, params, match.quote())) {
            source = match.source().kind();
            documentId = match.source().documentId();
            quote = match.quote();
            offset = match.offset();
        } else if (match != null) {
            note = "Đoạn trích có thật nhưng không chứa các con số của luật - hạ xuống GIẢ ĐỊNH, hãy đối chiếu tay";
        } else if (p.quote() != null && !p.quote().isBlank()) {
            note = "Đoạn trích AI đưa ra không có nguyên văn trong tài liệu/chỉ dẫn - hạ xuống GIẢ ĐỊNH";
        }

        Integer expected = null;
        if (quote != null && p.expectedStatus() != null && p.expectedStatus() >= 400 && p.expectedStatus() <= 499
                && Pattern.compile("(?<!\\d)" + p.expectedStatus() + "(?!\\d)").matcher(quote).find()) {
            expected = p.expectedStatus(); // chỉ giữ mã khi tài liệu nêu đúng mã đó; còn lại là "bị từ chối" do người duyệt quyết
        }
        String rationale = p.rationale() == null ? null : p.rationale().trim();
        if (source == RuleSource.INFERRED) {
            rationale = "GIẢ ĐỊNH: " + (rationale == null || rationale.isBlank() ? "AI suy đoán, không có trích dẫn hợp lệ" : rationale);
        }
        return new Verdict(null, null, endpointKey, template, params, source, documentId, quote, offset, expected, rationale, note);
    }

    // ---- đoạn trích ----

    private record QuoteMatch(Source source, String quote, int offset) {
    }

    private static QuoteMatch findQuote(String raw, List<Source> sources) {
        if (raw == null) {
            return null;
        }
        String needle = collapse(raw).text();
        if (needle.length() < QUOTE_MIN || needle.length() > QUOTE_MAX) {
            return null;
        }
        // Tài liệu trước (thứ tự tải lên), chỉ dẫn sau.
        for (Source source : sources) {
            if (source.text() == null) {
                continue;
            }
            Collapsed hay = collapse(source.text());
            int index = hay.text().indexOf(needle);
            if (index >= 0) {
                int start = hay.map()[index];
                int end = hay.map()[index + needle.length() - 1] + 1;
                return new QuoteMatch(source, source.text().substring(start, end), start);
            }
        }
        return null;
    }

    /** Văn bản đã chuẩn hoá để so khớp + bảng ánh xạ chỉ số về văn bản gốc (để lấy offset thật và lấy lại đúng đoạn gốc). */
    record Collapsed(String text, int[] map) {
    }

    static Collapsed collapse(String original) {
        String nfc = Normalizer.normalize(original, Normalizer.Form.NFC);
        // NFC có thể đổi độ dài; tài liệu đã lưu ở dạng NFC nên với tài liệu bản đồ khớp chỉ số gốc. Chuỗi AI không cần bản đồ.
        boolean sameLength = nfc.length() == original.length();
        String base = sameLength ? original : nfc;
        StringBuilder out = new StringBuilder(base.length());
        int[] map = new int[base.length()];
        boolean lastSpace = true;
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (Character.isWhitespace(c) || c == ' ') {
                if (!lastSpace) {
                    map[out.length()] = i;
                    out.append(' ');
                    lastSpace = true;
                }
                continue;
            }
            lastSpace = false;
            map[out.length()] = i;
            out.append(straighten(c));
        }
        String text = out.toString();
        int trimmed = text.length();
        while (trimmed > 0 && text.charAt(trimmed - 1) == ' ') {
            trimmed--;
        }
        return new Collapsed(text.substring(0, trimmed), java.util.Arrays.copyOf(map, trimmed));
    }

    private static char straighten(char c) {
        return switch (c) {
            case '“', '”', '„', '«', '»' -> '"';
            case '‘', '’', '‚' -> '\'';
            case '–', '—' -> '-';
            default -> c;
        };
    }

    // ---- hằng số trong tham số phải có trong đoạn trích ----

    static boolean constantsAppearIn(RuleTemplate template, RuleParams p, String quote) {
        Set<String> numbers = numbersIn(quote);
        List<BigDecimal> constants = new java.util.ArrayList<>();
        if (template == RuleTemplate.VALUE_RANGE) {
            addIfPresent(constants, p.min());
            addIfPresent(constants, p.max());
        }
        if (template == RuleTemplate.ARRAY_AGGREGATE && p.value() != null) {
            constants.add(p.value());
        }
        if (template == RuleTemplate.DATE_RELATIVE && p.offset() != null && p.offset() != 0) {
            constants.add(BigDecimal.valueOf(Math.abs(p.offset())));
        }
        if (template == RuleTemplate.REQUIRES && p.fieldValue() != null && p.fieldValue().matches("-?\\d+(\\.\\d+)?")) {
            constants.add(new BigDecimal(p.fieldValue()));
        }
        for (BigDecimal constant : constants) {
            if (!numbers.contains(constant.abs().stripTrailingZeros().toPlainString())) {
                return false;
            }
        }
        return true;
    }

    private static void addIfPresent(List<BigDecimal> list, BigDecimal value) {
        if (value != null) {
            list.add(value);
        }
    }

    /** Các số trong văn bản, đã bỏ dấu phân cách nghìn/thập phân ("1.000" và "1,000" cùng ra "1000"; "2,5" và "2.5" ra "2.5" và "25"). */
    static Set<String> numbersIn(String text) {
        Set<String> result = new HashSet<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            String token = m.group();
            result.add(token.replaceAll("[.,]", ""));
            if (token.matches("\\d+[.,]\\d+")) {
                result.add(token.replace(',', '.'));
            }
            for (String part : token.split("[.,]")) {
                result.add(part);
            }
        }
        return result;
    }
}
