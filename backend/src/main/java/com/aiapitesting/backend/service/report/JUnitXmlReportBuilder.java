package com.aiapitesting.backend.service.report;

import com.aiapitesting.backend.entity.TestResultStatus;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Locale;

/**
 * Module 17 (17d) - JUnit XML cho CI (GitHub Actions/GitLab/Jenkins đọc được). Dùng XMLStreamWriter nên mọi giá trị tự được escape;
 * thêm lọc ký tự không hợp lệ trong XML 1.0. Quy ước: mỗi endpoint là 1 testsuite, mỗi test case là 1 testcase;
 * PASSED = rỗng, FAILED = failure, ERROR = error, BLOCKED/SKIPPED = skipped.
 */
public final class JUnitXmlReportBuilder {

    private JUnitXmlReportBuilder() {
    }

    public static String build(ReportModel model) {
        try {
            StringWriter out = new StringWriter();
            XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(out);
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeCharacters("\n");
            xml.writeStartElement("testsuites");
            attr(xml, "name", model.projectName());
            attr(xml, "tests", model.summary().total());
            attr(xml, "failures", model.summary().failed());
            attr(xml, "errors", model.summary().error());
            attr(xml, "skipped", model.summary().blocked() + model.summary().skipped());
            attr(xml, "time", seconds(totalMillis(model)));
            for (ReportModel.EndpointGroup group : model.endpoints()) {
                writeSuite(xml, model, group);
            }
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.close();
            return out.toString();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Không tạo được JUnit XML", e);
        }
    }

    private static void writeSuite(XMLStreamWriter xml, ReportModel model, ReportModel.EndpointGroup group) throws XMLStreamException {
        int failures = 0, errors = 0, skipped = 0;
        long millis = 0;
        for (ReportModel.Case c : group.cases()) {
            millis += c.durationMs() == null ? 0 : c.durationMs();
            switch (c.status()) {
                case FAILED -> failures++;
                case ERROR -> errors++;
                case BLOCKED, SKIPPED -> skipped++;
                default -> {
                }
            }
        }
        String suiteName = group.method() + " " + group.path();
        xml.writeCharacters("\n  ");
        xml.writeStartElement("testsuite");
        attr(xml, "name", suiteName);
        attr(xml, "tests", group.cases().size());
        attr(xml, "failures", failures);
        attr(xml, "errors", errors);
        attr(xml, "skipped", skipped);
        attr(xml, "time", seconds(millis));
        if (model.startedAt() != null) {
            attr(xml, "timestamp", model.startedAt().toString());
        }
        for (ReportModel.Case c : group.cases()) {
            xml.writeCharacters("\n    ");
            xml.writeStartElement("testcase");
            attr(xml, "classname", suiteName);
            attr(xml, "name", c.name());
            attr(xml, "time", seconds(c.durationMs() == null ? 0 : c.durationMs()));
            writeOutcome(xml, c);
            xml.writeEndElement();
        }
        xml.writeCharacters("\n  ");
        xml.writeEndElement();
    }

    private static void writeOutcome(XMLStreamWriter xml, ReportModel.Case c) throws XMLStreamException {
        TestResultStatus status = c.status();
        if (status == TestResultStatus.PASSED) {
            return;
        }
        String element = switch (status) {
            case FAILED -> "failure";
            case ERROR -> "error";
            default -> "skipped";
        };
        xml.writeStartElement(element);
        String label = c.failureCauseLabel() == null ? status.name() : c.failureCauseLabel();
        attr(xml, "message", c.message() == null ? label : label + ": " + c.message());
        if (status != TestResultStatus.BLOCKED && status != TestResultStatus.SKIPPED) {
            attr(xml, "type", c.failureCause() == null ? status.name() : c.failureCause());
        }
        List<String> evidence = c.evidence();
        StringBuilder body = new StringBuilder();
        if (c.notApiFault()) {
            body.append("[Không phải do API: môi trường/token/cấu hình test]\n");
        }
        if (c.unstable()) {
            body.append("[Test không ổn định]\n");
        }
        evidence.forEach(line -> body.append("- ").append(line).append('\n'));
        if (body.length() > 0) {
            xml.writeCharacters(xmlSafe(body.toString()));
        }
        xml.writeEndElement();
    }

    private static void attr(XMLStreamWriter xml, String name, String value) throws XMLStreamException {
        xml.writeAttribute(name, xmlSafe(value == null ? "" : value));
    }

    private static void attr(XMLStreamWriter xml, String name, long value) throws XMLStreamException {
        xml.writeAttribute(name, Long.toString(value));
    }

    private static long totalMillis(ReportModel model) {
        return model.endpoints().stream().flatMap(g -> g.cases().stream()).mapToLong(c -> c.durationMs() == null ? 0 : c.durationMs()).sum();
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.3f", millis / 1000.0);
    }

    /** Bỏ ký tự không hợp lệ trong XML 1.0 (điều khiển, surrogate lẻ, U+FFFE/U+FFFF). */
    static String xmlSafe(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            boolean ok = cp == 0x9 || cp == 0xA || cp == 0xD || (cp >= 0x20 && cp <= 0xD7FF)
                    || (cp >= 0xE000 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0x10FFFF);
            if (ok) {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString();
    }
}
