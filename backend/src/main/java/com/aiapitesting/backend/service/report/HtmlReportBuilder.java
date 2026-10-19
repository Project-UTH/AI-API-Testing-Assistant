package com.aiapitesting.backend.service.report;

import com.aiapitesting.backend.entity.TestResultStatus;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Module 17 (17d) - báo cáo HTML TỰ CHỨA (1 file, CSS inline, không script, không tài nguyên ngoài) để mở/in/đính kèm. Mọi chuỗi đều
 * đi qua {@link #esc(String)} - nơi DUY NHẤT chèn dữ liệu vào HTML - và có CSP chặn mọi thứ ngoài style inline.
 */
public final class HtmlReportBuilder {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private HtmlReportBuilder() {
    }

    public static String build(ReportModel model) {
        StringBuilder h = new StringBuilder(8_192);
        h.append("<!DOCTYPE html>\n<html lang=\"vi\"><head><meta charset=\"utf-8\">")
                .append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>Báo cáo kiểm thử - ").append(esc(model.projectName())).append("</title>")
                .append("<style>")
                .append("body{font:14px/1.5 system-ui,sans-serif;margin:24px;color:#1b1b1f}h1{font-size:20px;margin:0 0 4px}")
                .append("h2{font-size:15px;margin:22px 0 6px;font-family:ui-monospace,monospace}table{border-collapse:collapse;width:100%}")
                .append("th,td{border:1px solid #d8d8de;padding:5px 8px;text-align:left;vertical-align:top}th{background:#f3f3f6}")
                .append(".pill{display:inline-block;padding:1px 8px;border-radius:10px;font-size:12px;font-weight:600}")
                .append(".PASSED{background:#dcfce7;color:#166534}.FAILED{background:#fee2e2;color:#991b1b}")
                .append(".ERROR{background:#ffedd5;color:#9a3412}.BLOCKED,.SKIPPED,.PENDING{background:#e5e7eb;color:#374151}")
                .append(".muted{color:#6b7280;font-size:12px}.sum td{text-align:center;font-size:18px;font-weight:600}")
                .append("</style></head><body>");

        h.append("<h1>Báo cáo kiểm thử: ").append(esc(model.projectName())).append("</h1>");
        h.append("<p class=\"muted\">Lượt chạy ").append(esc(String.valueOf(model.executionId())));
        if (model.startedAt() != null) {
            h.append(" · bắt đầu ").append(esc(TIME.format(model.startedAt())));
        }
        if (model.finishedAt() != null) {
            h.append(" · kết thúc ").append(esc(TIME.format(model.finishedAt())));
        }
        if (model.targetBaseUrl() != null) {
            h.append(" · đích ").append(esc(model.targetBaseUrl()));
        }
        h.append("</p>");
        h.append("<p>Kết luận: <span class=\"pill ").append(model.conclusion() == ExecutionConclusionCalculator.Conclusion.PASSED ? "PASSED"
                : model.conclusion() == ExecutionConclusionCalculator.Conclusion.PENDING ? "PENDING" : "FAILED").append("\">")
                .append(esc(model.conclusion().name())).append("</span></p>");

        ExecutionConclusionCalculator.Summary s = model.summary();
        h.append("<table class=\"sum\"><tr><th>Tổng</th><th>Pass</th><th>Fail</th><th>Fail do API</th><th>Fail không do API</th>")
                .append("<th>Lỗi</th><th>Bị chặn</th><th>Không ổn định</th></tr><tr>")
                .append(cell(s.total())).append(cell(s.passed())).append(cell(s.failed())).append(cell(s.apiFailed()))
                .append(cell(s.notApiFault())).append(cell(s.error())).append(cell(s.blocked() + s.skipped())).append(cell(s.flaky()))
                .append("</tr></table>");

        for (ReportModel.EndpointGroup group : model.endpoints()) {
            h.append("<h2>").append(esc(group.method())).append(' ').append(esc(group.path())).append("</h2>");
            h.append("<table><tr><th>Test case</th><th>Kết quả</th><th>Mã trả về</th><th>Thời gian</th><th>Nguyên nhân / bằng chứng</th></tr>");
            for (ReportModel.Case c : group.cases()) {
                h.append("<tr><td>").append(esc(c.name())).append("</td><td><span class=\"pill ").append(esc(c.status().name())).append("\">")
                        .append(esc(c.status().name())).append("</span></td><td>")
                        .append(c.responseStatus() == null ? "—" : esc(String.valueOf(c.responseStatus())))
                        .append(c.expectedStatus() == null || c.status() == TestResultStatus.PASSED ? "" : " <span class=\"muted\">(kỳ vọng "
                                + esc(String.valueOf(c.expectedStatus())) + ")</span>")
                        .append("</td><td>").append(c.durationMs() == null ? "—" : esc(c.durationMs() + " ms")).append("</td><td>");
                if (c.failureCauseLabel() != null) {
                    h.append("<strong>").append(esc(c.failureCauseLabel())).append("</strong>");
                    if (c.notApiFault()) {
                        h.append(" <span class=\"muted\">(không phải do API)</span>");
                    }
                }
                if (c.unstable()) {
                    h.append(" <span class=\"muted\">(test không ổn định)</span>");
                }
                if (c.message() != null) {
                    h.append("<div class=\"muted\">").append(esc(c.message())).append("</div>");
                }
                for (String line : c.evidence()) {
                    h.append("<div class=\"muted\">• ").append(esc(line)).append("</div>");
                }
                h.append("</td></tr>");
            }
            h.append("</table>");
        }
        h.append("</body></html>");
        return h.toString();
    }

    private static String cell(int value) {
        return "<td>" + value + "</td>";
    }

    /** Escape HTML - NƠI DUY NHẤT dữ liệu động được chèn vào HTML. */
    static String esc(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }
}
