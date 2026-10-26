package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.entity.BusinessRule;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.RequirementDocument;
import com.aiapitesting.backend.entity.RuleSource;
import com.aiapitesting.backend.entity.RuleStatus;
import com.aiapitesting.backend.entity.RuleTemplate;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.repository.BusinessRuleRepository;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.GenerationJobRepository;
import com.aiapitesting.backend.repository.ProjectRepository;
import com.aiapitesting.backend.repository.RequirementDocumentRepository;
import com.aiapitesting.backend.repository.RuleReviewTimingRepository;
import com.aiapitesting.backend.repository.UserRepository;
import com.aiapitesting.backend.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 17q - PHÂN QUYỀN thật (Spring Security + JWT + service + MySQL) của API luật nghiệp vụ và tài liệu nghiệp vụ: người dùng B không được đọc, sửa,
 * duyệt, xoá, tải lên hay chạy job trên project của người dùng A, và không dùng id luật/tài liệu của A trong project của chính B. Dữ liệu thử có
 * email ngẫu nhiên và được dọn sau mỗi test; không đụng dữ liệu của tài khoản nào khác.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RuleAndDocumentOwnershipTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private ProjectRepository projects;
    @Autowired
    private EndpointRepository endpoints;
    @Autowired
    private BusinessRuleRepository rules;
    @Autowired
    private RequirementDocumentRepository documents;
    @Autowired
    private RuleReviewTimingRepository timings;
    @Autowired
    private GenerationJobRepository jobs;
    @Autowired
    private JwtService jwt;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private User alice;
    private User bob;
    private Project aliceProject;
    private Project bobProject;
    private Endpoint aliceEndpoint;
    private BusinessRule aliceRule;
    private RequirementDocument aliceDoc;
    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() {
        alice = newUser("alice");
        bob = newUser("bob");
        aliceProject = projects.save(Project.builder().name("idor-alice").owner(alice).build());
        bobProject = projects.save(Project.builder().name("idor-bob").owner(bob).build());
        aliceEndpoint = endpoints.save(Endpoint.builder().project(aliceProject).method("POST").path("/jobs").build());
        aliceRule = rules.save(BusinessRule.builder().project(aliceProject).endpointKey("POST /jobs").endpointId(aliceEndpoint.getId())
                .template(RuleTemplate.OR).paramsJson("{\"fields\":[\"a\",\"b\"]}").status(RuleStatus.PROPOSED).source(RuleSource.DOC)
                .quote("đoạn trích nguyên văn đủ dài").quoteOffset(0).fingerprint("fp-" + UUID.randomUUID()).build());
        aliceDoc = documents.save(RequirementDocument.builder().project(aliceProject).fileName("srs.md").content("Nội dung tài liệu của Alice")
                .sizeBytes(30).sha256("0".repeat(64)).active(true).build());
        aliceToken = jwt.generateToken(alice.getEmail());
        bobToken = jwt.generateToken(bob.getEmail());
    }

    @AfterEach
    void cleanUp() {
        // các câu DELETE @Modifying cần transaction
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (Project p : List.of(aliceProject, bobProject)) {
                jobs.deleteAllByProjectId(p.getId());
                timings.deleteAllByProjectId(p.getId());
                rules.deleteAllByProject(p);
                documents.deleteAllByProject(p);
                endpoints.deleteAllByProject(p);
                projects.deleteById(p.getId());
            }
            users.deleteAllById(List.of(alice.getId(), bob.getId()));
        });
    }

    private User newUser(String prefix) {
        return users.save(User.builder().email(prefix + "-" + UUID.randomUUID() + "@idor.test").password("{noop}khong-dung-de-dang-nhap").build());
    }

    private String rulesUrl(Project project) {
        return "/api/v1/projects/" + project.getId() + "/business-rules";
    }

    private String docsUrl(Project project) {
        return "/api/v1/projects/" + project.getId() + "/requirement-documents";
    }

    private MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + token).contentType(JSON);
    }

    // ---- B tấn công project của A: mọi route đều 403 ----

    @Test
    void anotherUserCannotTouchRulesOfSomeoneElsesProject() throws Exception {
        String base = rulesUrl(aliceProject);
        String ruleBase = base + "/" + aliceRule.getId();
        String body = "{\"ruleIds\":[\"" + aliceRule.getId() + "\"]}";

        mvc.perform(as(bobToken, get(base))).andExpect(status().isForbidden());
        mvc.perform(as(bobToken, get(ruleBase))).andExpect(status().isForbidden());
        mvc.perform(as(bobToken, post(base)).content("{\"endpointId\":\"" + aliceEndpoint.getId() + "\",\"template\":\"OR\",\"params\":{\"fields\":[\"a\",\"b\"]}}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(bobToken, put(ruleBase)).content("{\"params\":{\"fields\":[\"a\",\"b\"]}}")).andExpect(status().isForbidden());
        mvc.perform(as(bobToken, post(ruleBase + "/approve")).content("{}")).andExpect(status().isForbidden());
        mvc.perform(as(bobToken, post(ruleBase + "/reject")).content("{\"reason\":\"WRONG_MEANING\"}")).andExpect(status().isForbidden());
        mvc.perform(as(bobToken, post(base + "/approve-batch")).content(body)).andExpect(status().isForbidden());
        mvc.perform(as(bobToken, post(base + "/generate-tests")).content("{}")).andExpect(status().isForbidden());
        mvc.perform(as(bobToken, post(base + "/detect"))).andExpect(status().isForbidden());
        mvc.perform(as(bobToken, delete(ruleBase))).andExpect(status().isForbidden());
        mvc.perform(as(bobToken, get(base + "/endpoint-fields").param("endpointId", aliceEndpoint.getId().toString()))).andExpect(status().isForbidden());
        mvc.perform(as(bobToken, post(ruleBase + "/review-timing")).content("{\"decision\":\"APPROVED\",\"focusedMs\":1000}")).andExpect(status().isForbidden());
        mvc.perform(as(bobToken, get(base + "/review-timing"))).andExpect(status().isForbidden());

        BusinessRule after = rules.findById(aliceRule.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RuleStatus.PROPOSED);
        assertThat(after.getParamsJson()).isEqualTo("{\"fields\":[\"a\",\"b\"]}");
        assertThat(rules.findAllByProject(aliceProject)).hasSize(1);
        assertThat(timings.findAllByProjectIdOrderByCreatedAtAsc(aliceProject.getId())).isEmpty();
    }

    @Test
    void anotherUserCannotReadUploadOrDeleteDocumentsOfSomeoneElsesProject() throws Exception {
        String base = docsUrl(aliceProject);
        mvc.perform(as(bobToken, get(base))).andExpect(status().isForbidden());
        mvc.perform(as(bobToken, get(base + "/" + aliceDoc.getId() + "/content"))).andExpect(status().isForbidden());
        mvc.perform(as(bobToken, delete(base + "/" + aliceDoc.getId()))).andExpect(status().isForbidden());
        MockMultipartFile file = new MockMultipartFile("file", "hack.md", "text/markdown", "noi dung".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart(base).file(file).header("Authorization", "Bearer " + bobToken)).andExpect(status().isForbidden());

        RequirementDocument after = documents.findById(aliceDoc.getId()).orElseThrow();
        assertThat(after.isActive()).isTrue();
        assertThat(after.getContent()).isEqualTo("Nội dung tài liệu của Alice");
        assertThat(documents.countByProjectAndActiveTrue(aliceProject)).isEqualTo(1);
    }

    @Test
    void anotherUserCannotStartOrReadAnExtractionJobOnSomeoneElsesProject() throws Exception {
        String jobsUrl = "/api/v1/projects/" + aliceProject.getId() + "/generation-jobs";
        mvc.perform(as(bobToken, post(jobsUrl)).content("{\"type\":\"RULES\",\"instruction\":\"Chỉ dẫn của kẻ tấn công\",\"dryRun\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(bobToken, get(jobsUrl + "/" + UUID.randomUUID()))).andExpect(status().isForbidden());
        mvc.perform(as(bobToken, get(jobsUrl))).andExpect(status().isForbidden());
        assertThat(jobs.findTop20ByProjectIdOrderByCreatedAtDesc(aliceProject.getId())).isEmpty();
    }

    // ---- B dùng id của A trong project của CHÍNH B: không được chạm vào dữ liệu của A ----

    @Test
    void ruleAndDocumentIdsOfAnotherProjectAreNotUsableFromOwnProject() throws Exception {
        String base = rulesUrl(bobProject);
        String ruleBase = base + "/" + aliceRule.getId();

        for (var request : List.of(
                get(ruleBase),
                put(ruleBase),
                post(ruleBase + "/approve"),
                post(ruleBase + "/reject"),
                delete(ruleBase),
                post(ruleBase + "/review-timing"))) {
            int code = mvc.perform(as(bobToken, request).content("{\"params\":{\"fields\":[\"a\",\"b\"]},\"reason\":\"WRONG_MEANING\",\"decision\":\"APPROVED\",\"focusedMs\":1}"))
                    .andReturn().getResponse().getStatus();
            assertThat(code).as("%s %s", request, ruleBase).isBetween(400, 499);
        }
        // duyệt hàng loạt / sinh test với id lạ project: bỏ qua, không đổi gì của A
        mvc.perform(as(bobToken, post(base + "/approve-batch")).content("{\"ruleIds\":[\"" + aliceRule.getId() + "\"]}")).andExpect(status().isOk());
        mvc.perform(as(bobToken, post(base + "/generate-tests")).content("{\"ruleIds\":[\"" + aliceRule.getId() + "\"]}")).andExpect(status().isCreated());

        String docBase = docsUrl(bobProject) + "/" + aliceDoc.getId();
        mvc.perform(as(bobToken, get(docBase + "/content"))).andExpect(status().isNotFound());
        mvc.perform(as(bobToken, delete(docBase))).andExpect(status().isNotFound());
        // job trích luật trên project của B nhưng chỉ vào tài liệu của A
        int jobCode = mvc.perform(as(bobToken, post("/api/v1/projects/" + bobProject.getId() + "/generation-jobs"))
                .content("{\"type\":\"RULES\",\"documentIds\":[\"" + aliceDoc.getId() + "\"],\"instruction\":\"x\"}")).andReturn().getResponse().getStatus();
        assertThat(jobCode).isBetween(400, 499);

        BusinessRule after = rules.findById(aliceRule.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RuleStatus.PROPOSED);
        assertThat(after.getReviewedBy()).isNull();
        assertThat(documents.findById(aliceDoc.getId()).orElseThrow().isActive()).isTrue();
        assertThat(jobs.findTop20ByProjectIdOrderByCreatedAtDesc(bobProject.getId())).isEmpty();
        assertThat(timings.findAllByProjectIdOrderByCreatedAtAsc(bobProject.getId())).isEmpty();
    }

    // ---- không đăng nhập, và chủ sở hữu vẫn dùng được ----

    @Test
    void withoutATokenEveryRouteIsRejected() throws Exception {
        mvc.perform(get(rulesUrl(aliceProject))).andExpect(status().isUnauthorized());
        mvc.perform(post(rulesUrl(aliceProject) + "/approve-batch").contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(get(docsUrl(aliceProject))).andExpect(status().isUnauthorized());
        mvc.perform(get(docsUrl(aliceProject) + "/" + aliceDoc.getId() + "/content")).andExpect(status().isUnauthorized());
    }

    @Test
    void theOwnerCanUseTheSameRoutes() throws Exception {
        mvc.perform(as(aliceToken, get(rulesUrl(aliceProject)))).andExpect(status().isOk());
        mvc.perform(as(aliceToken, get(rulesUrl(aliceProject) + "/" + aliceRule.getId()))).andExpect(status().isOk());
        mvc.perform(as(aliceToken, get(docsUrl(aliceProject)))).andExpect(status().isOk());
        mvc.perform(as(aliceToken, get(docsUrl(aliceProject) + "/" + aliceDoc.getId() + "/content"))).andExpect(status().isOk());
    }
}
