package com.aiapitesting.backend.service.rule;

import com.aiapitesting.backend.dto.rule.RequirementDocumentDtos.DocumentResponse;
import com.aiapitesting.backend.entity.BusinessRule;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.RequirementDocument;
import com.aiapitesting.backend.entity.RuleSource;
import com.aiapitesting.backend.entity.RuleStatus;
import com.aiapitesting.backend.entity.RuleTemplate;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.exception.RequirementDocumentException;
import com.aiapitesting.backend.repository.BusinessRuleRepository;
import com.aiapitesting.backend.repository.RequirementDocumentRepository;
import com.aiapitesting.backend.service.CurrentUserService;
import com.aiapitesting.backend.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequirementDocumentServiceTest {

    @Mock
    private ProjectService projectService;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private RequirementDocumentRepository documentRepository;
    @Mock
    private BusinessRuleRepository ruleRepository;
    @Mock
    private com.aiapitesting.backend.repository.RequirementDocumentUsageRepository usageRepository;
    @Mock
    private com.aiapitesting.backend.repository.EndpointRepository endpointRepository;

    private RequirementDocumentService service;
    private Project project;

    @BeforeEach
    void setUp() {
        service = new RequirementDocumentService(projectService, currentUserService, documentRepository, ruleRepository, usageRepository, endpointRepository);
        project = Project.builder().id(UUID.randomUUID()).build();
        lenient().when(projectService.getOwnedProject(project.getId())).thenReturn(project);
        lenient().when(currentUserService.getCurrentUser()).thenReturn(User.builder().id(UUID.randomUUID()).build());
        lenient().when(documentRepository.save(any(RequirementDocument.class))).thenAnswer(i -> {
            RequirementDocument d = i.getArgument(0);
            if (d.getId() == null) {
                d.setId(UUID.randomUUID());
            }
            return d;
        });
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private String codeOf(Runnable action) {
        try {
            action.run();
            return null;
        } catch (RequirementDocumentException e) {
            return e.getCode();
        }
    }

    @Test
    void upload_normalizesContent_bomCrlfAndNfc_andStoresShaOfNormalizedText() {
        // "é" dạng tổ hợp (e + dấu) phải thành NFC; có BOM và CRLF
        String raw = "\uFEFFTitle\r\nLương tối thiểu không được lớn hơn tối đa\r\ne\u0301\r\n";

        DocumentResponse response = service.upload(project.getId(), "yeu-cau.md", utf8(raw));

        ArgumentCaptor<RequirementDocument> saved = ArgumentCaptor.forClass(RequirementDocument.class);
        verify(documentRepository).save(saved.capture());
        assertThat(saved.getValue().getContent()).isEqualTo("Title\nLương tối thiểu không được lớn hơn tối đa\né\n");
        assertThat(saved.getValue().getSha256()).hasSize(64);
        assertThat(response.lineCount()).isEqualTo(3);
        assertThat(response.fileName()).isEqualTo("yeu-cau.md");
    }

    @Test
    void upload_rejectsWrongExtensionEmptyTooLargeAndBrokenUtf8() {
        assertThat(codeOf(() -> service.upload(project.getId(), "a.pdf", utf8("x")))).isEqualTo("DOCUMENT_TYPE_INVALID");
        assertThat(codeOf(() -> service.upload(project.getId(), "a.md", new byte[0]))).isEqualTo("DOCUMENT_TYPE_INVALID");
        assertThat(codeOf(() -> service.upload(project.getId(), "a.md", new byte[RequirementDocumentService.MAX_BYTES + 1])))
                .isEqualTo("DOCUMENT_TOO_LARGE");
        assertThat(codeOf(() -> service.upload(project.getId(), "a.txt", new byte[]{(byte) 0xC3, (byte) 0x28, 'x'}))).isEqualTo("DOCUMENT_ENCODING_INVALID");
        assertThat(codeOf(() -> service.upload(project.getId(), "a.txt", utf8("ab\u0000cd")))).isEqualTo("DOCUMENT_ENCODING_INVALID");
        verify(documentRepository, never()).save(any());
    }

    @Test
    void upload_pathInFileNameIsStripped_andExtensionCheckIsCaseInsensitive() {
        DocumentResponse response = service.upload(project.getId(), "C:\\docs\\SRS.MD", utf8("noi dung"));
        assertThat(response.fileName()).isEqualTo("SRS.MD");
    }

    @Test
    void upload_overTheLimit_isRejected_butSameNameReplacesTheOldOne() {
        when(documentRepository.findFirstByProjectAndFileNameAndActiveTrue(project, "new.md")).thenReturn(Optional.empty());
        when(documentRepository.countByProjectAndActiveTrue(project)).thenReturn((long) RequirementDocumentService.MAX_ACTIVE_DOCUMENTS);
        assertThat(codeOf(() -> service.upload(project.getId(), "new.md", utf8("x")))).isEqualTo("DOCUMENT_LIMIT_REACHED");

        RequirementDocument old = RequirementDocument.builder().id(UUID.randomUUID()).project(project).fileName("a.md").content("cu").build();
        when(documentRepository.findFirstByProjectAndFileNameAndActiveTrue(project, "a.md")).thenReturn(Optional.of(old));
        BusinessRule fromOld = BusinessRule.builder().id(UUID.randomUUID()).project(project).documentId(old.getId()).status(RuleStatus.APPROVED)
                .source(RuleSource.DOC).template(RuleTemplate.OR).endpointKey("POST /x").paramsJson("{}").fingerprint("f").build();
        BusinessRule rejected = BusinessRule.builder().id(UUID.randomUUID()).project(project).documentId(old.getId()).status(RuleStatus.REJECTED)
                .source(RuleSource.DOC).template(RuleTemplate.OR).endpointKey("POST /x").paramsJson("{}").fingerprint("g").build();
        when(ruleRepository.findAllByProject(project)).thenReturn(List.of(fromOld, rejected));

        service.upload(project.getId(), "a.md", utf8("moi"));

        assertThat(old.isActive()).isFalse();
        assertThat(old.getContent()).isNull();
        assertThat(fromOld.getStatus()).isEqualTo(RuleStatus.STALE);
        assertThat(fromOld.getStatusNote()).contains("Tài liệu nguồn đã được thay");
        assertThat(rejected.getStatus()).isEqualTo(RuleStatus.REJECTED); // luật đã loại giữ nguyên để đo precision
    }

    @Test
    void delete_dropsContentButKeepsTheRow_andContentOfDeletedDocumentIs404() {
        RequirementDocument doc = RequirementDocument.builder().id(UUID.randomUUID()).project(project).fileName("a.md").content("noi dung").build();
        when(documentRepository.findByIdAndProject(doc.getId(), project)).thenReturn(Optional.of(doc));

        service.delete(project.getId(), doc.getId());

        assertThat(doc.isActive()).isFalse();
        assertThat(doc.getContent()).isNull();
        assertThat(codeOf(() -> service.content(project.getId(), doc.getId()))).isEqualTo("DOCUMENT_NOT_FOUND");
        assertThat(codeOf(() -> service.content(project.getId(), UUID.randomUUID()))).isEqualTo("DOCUMENT_NOT_FOUND");
    }

    @Test
    void setEndpoints_acceptsOnlyKnownEndpointKeys_andReplaceCarriesThemOver() {
        RequirementDocument doc = RequirementDocument.builder().id(UUID.randomUUID()).project(project).fileName("a.md").content("x").build();
        when(documentRepository.findByIdAndProject(doc.getId(), project)).thenReturn(Optional.of(doc));
        com.aiapitesting.backend.entity.Endpoint known = com.aiapitesting.backend.entity.Endpoint.builder().id(UUID.randomUUID()).method("POST").path("/jobs").build();
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(known));

        var response = service.setEndpoints(project.getId(), doc.getId(), List.of("POST /jobs", "POST /jobs", " "));
        assertThat(response.forEndpoints()).containsExactly("POST /jobs");

        assertThat(codeOf(() -> service.setEndpoints(project.getId(), doc.getId(), List.of("GET /khong-co")))).isEqualTo("DOCUMENT_ENDPOINTS_INVALID");
        assertThat(service.setEndpoints(project.getId(), doc.getId(), List.of()).forEndpoints()).isEmpty();

        // thay bản (tải trùng tên) giữ khai báo "viết cho endpoint nào"
        doc.setForEndpointsJson("[\"POST /jobs\"]");
        when(documentRepository.findFirstByProjectAndFileNameAndActiveTrue(project, "a.md")).thenReturn(Optional.of(doc));
        when(ruleRepository.findAllByProject(project)).thenReturn(List.of());
        assertThat(service.upload(project.getId(), "a.md", utf8("moi")).forEndpoints()).containsExactly("POST /jobs");
    }

    @Test
    void usage_namesActiveAndDeletedDocuments_newestFirst() {
        RequirementDocument alive = RequirementDocument.builder().id(UUID.randomUUID()).project(project).fileName("a.md").content("x").build();
        RequirementDocument gone = RequirementDocument.builder().id(UUID.randomUUID()).project(project).fileName("b.md").active(false).build();
        when(documentRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(java.util.List.of(alive, gone));
        java.time.Instant older = java.time.Instant.parse("2026-10-20T00:00:00Z");
        java.time.Instant newer = java.time.Instant.parse("2026-10-26T00:00:00Z");
        when(usageRepository.summarize(project.getId())).thenReturn(java.util.List.of(row(gone.getId(), "POST /x", older, 1), row(alive.getId(), "PUT /y", newer, 3)));

        var entries = service.usage(project.getId());

        assertThat(entries).extracting("endpointKey").containsExactly("PUT /y", "POST /x");
        assertThat(entries.get(0).documentActive()).isTrue();
        assertThat(entries.get(0).fileName()).isEqualTo("a.md");
        assertThat(entries.get(0).timesUsed()).isEqualTo(3);
        assertThat(entries.get(1).documentActive()).isFalse();
    }

    private static com.aiapitesting.backend.repository.RequirementDocumentUsageRepository.UsageRow row(UUID doc, String key, java.time.Instant at, long times) {
        return new com.aiapitesting.backend.repository.RequirementDocumentUsageRepository.UsageRow() {
            public UUID getDocumentId() {
                return doc;
            }

            public String getEndpointKey() {
                return key;
            }

            public java.time.Instant getLastUsedAt() {
                return at;
            }

            public long getTimesUsed() {
                return times;
            }
        };
    }

    @Test
    void activeDocuments_skipsDeletedAndCanFilterByIds() {
        RequirementDocument a = RequirementDocument.builder().id(UUID.randomUUID()).fileName("a.md").content("A").build();
        RequirementDocument b = RequirementDocument.builder().id(UUID.randomUUID()).fileName("b.md").content("B").build();
        RequirementDocument gone = RequirementDocument.builder().id(UUID.randomUUID()).fileName("c.md").content(null).build();
        when(documentRepository.findAllByProjectAndActiveTrueOrderByCreatedAtAsc(project)).thenReturn(List.of(a, b, gone));

        assertThat(service.activeDocuments(project, null)).containsExactly(a, b);
        assertThat(service.activeDocuments(project, List.of(b.getId()))).containsExactly(b);
    }

    @Test
    void listWarnsAboutSecretsWithoutRevealingTheirValue() {
        String content = "Quy tắc\ntoken = abcdef1234567890xyz\nkết thúc";
        RequirementDocument doc = RequirementDocument.builder().id(UUID.randomUUID()).fileName("a.md").content(content).sizeBytes(40).active(true).build();
        when(documentRepository.findAllByProjectAndActiveTrueOrderByCreatedAtAsc(project)).thenReturn(List.of(doc));

        DocumentResponse response = service.list(project.getId()).get(0);

        assertThat(response.warnings()).singleElement().satisfies(w -> {
            assertThat(w.line()).isEqualTo(2);
            assertThat(w.rule()).isEqualTo("ASSIGNED_SECRET");
        });
        assertThat(response.toString()).doesNotContain("abcdef1234567890xyz");
    }
}
