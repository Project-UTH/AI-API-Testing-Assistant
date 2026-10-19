package com.aiapitesting.backend.service.report;

import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.entity.FailureCause;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.service.report.ExecutionConclusionCalculator.FailOn;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Mô hình + JUnit XML + HTML dựng từ cùng 1 bộ dữ liệu có chứa bí mật/ký tự độc hại ở khắp nơi. */
class ReportBuildersTest {

    private final ReportModelFactory factory = new ReportModelFactory();

    private ReportModel model() {
        Project project = ReportTestSupport.project("Dự án <script>alert(1)</script> & \"co\"");
        TestExecution execution = ReportTestSupport.execution(ExecutionStatus.COMPLETED);
        List<TestResult> results = List.of(
                ReportTestSupport.result("GET", "/pets", "Ok", TestResultStatus.PASSED, 200, 200, null, null),
                ReportTestSupport.result("GET", "/pets", "Lỗi <img src=x onerror=alert(1)>", TestResultStatus.FAILED, 500, 200,
                        FailureCause.STABLE_FAILURE,
                        "[\"API trả 500 kèm Authorization: Bearer EVIDENCE-LEAKED-TOKEN\",\"ký tự lạ \\u0001 và <b>đậm</b>\"]"),
                ReportTestSupport.result("POST", "/pets", "Bị chặn", TestResultStatus.BLOCKED, null, 201, null, null),
                ReportTestSupport.result("POST", "/pets", "Token hỏng", TestResultStatus.FAILED, 401, 201, FailureCause.AUTH_CONFIG, null));
        return factory.build(project, execution, results, FailOn.API_FAIL);
    }

    @Test
    void model_neverContainsRequestOrResponseSecrets_norEvidenceTokens() {
        ReportModel model = model();
        String everything = model.toString();

        assertThat(everything).doesNotContain("SECRET-REQUEST-HEADER-TOKEN").doesNotContain("SECRET-REQUEST-BODY-PASSWORD")
                .doesNotContain("SECRET-RESPONSE-BODY").doesNotContain("EVIDENCE-LEAKED-TOKEN");
        assertThat(model.endpoints()).extracting(ReportModel.EndpointGroup::path).containsExactly("/pets", "/pets");
    }

    @Test
    void junit_isWellFormedXml_withCorrectCounts_andEscapedContent() throws Exception {
        String xml = JUnitXmlReportBuilder.build(model());
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(xml)));

        Element root = doc.getDocumentElement();
        assertThat(root.getTagName()).isEqualTo("testsuites");
        assertThat(root.getAttribute("tests")).isEqualTo("4");
        assertThat(root.getAttribute("failures")).isEqualTo("2");
        assertThat(root.getAttribute("skipped")).isEqualTo("1");
        assertThat(root.getAttribute("name")).contains("<script>"); // đã được giải mã khi parse = đã escape đúng khi ghi
        NodeList failures = doc.getElementsByTagName("failure");
        assertThat(failures.getLength()).isEqualTo(2);
        assertThat(((Element) failures.item(0)).getAttribute("type")).isEqualTo("STABLE_FAILURE");
        assertThat(doc.getElementsByTagName("skipped").getLength()).isEqualTo(1);
        assertThat(xml).doesNotContain("EVIDENCE-LEAKED-TOKEN").doesNotContain("\u0001");
        // Không tự đóng vai trò thẻ thật trong chuỗi thô
        assertThat(xml).doesNotContain("<img src=x").doesNotContain("<script>");
    }

    @Test
    void securityFindings_appearInBothReportsAsWarningsOnly_neverWithValues() {
        TestResult withFinding = ReportTestSupport.result("GET", "/users/1", "Lấy user", TestResultStatus.PASSED, 200, 200, null, null);
        withFinding.setSecurityFindingsJson("[{\"path\":\"user.passwordHash\",\"rule\":\"FIELD_PASSWORD\",\"severity\":\"HIGH\","
                + "\"detail\":\"Tên field gợi ý dữ liệu nhạy cảm (kiểu chuỗi, dài 60 ký tự)\"}]");
        ReportModel model = factory.build(ReportTestSupport.project("P"), ReportTestSupport.execution(ExecutionStatus.COMPLETED),
                List.of(withFinding), FailOn.API_FAIL);

        String xml = JUnitXmlReportBuilder.build(model);
        String html = HtmlReportBuilder.build(model);

        assertThat(xml).contains("<system-out>").contains("user.passwordHash [FIELD_PASSWORD]").doesNotContain("<failure");
        assertThat(html).contains("Cảnh báo bảo mật").contains("user.passwordHash [FIELD_PASSWORD]");
        assertThat(model.summary().failed()).isZero(); // không đổi kết quả
    }

    @Test
    void junit_marksNonApiFailuresSoCiCanTellThemApart() {
        String xml = JUnitXmlReportBuilder.build(model());

        assertThat(xml).contains("Không phải do API");
    }

    @Test
    void html_escapesEverythingAndCarriesCspWithoutScripts() {
        String html = HtmlReportBuilder.build(model());

        // "onerror=alert(1)" còn lại chỉ là CHỮ (đã escape dấu < >), không thành thẻ/thuộc tính thật.
        assertThat(html).doesNotContain("<script").doesNotContain("<img")
                .doesNotContain("SECRET-REQUEST-HEADER-TOKEN").doesNotContain("SECRET-RESPONSE-BODY").doesNotContain("EVIDENCE-LEAKED-TOKEN");
        assertThat(html).contains("&lt;script&gt;").contains("&lt;img src=x onerror=alert(1)&gt;")
                .contains("Content-Security-Policy").contains("default-src 'none'");
        assertThat(html).contains("Kết luận").contains("FAILED"); // có Fail do API
    }
}
