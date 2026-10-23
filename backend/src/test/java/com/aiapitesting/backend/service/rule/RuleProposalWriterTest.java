package com.aiapitesting.backend.service.rule;

import com.aiapitesting.backend.entity.BusinessRule;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.RuleSource;
import com.aiapitesting.backend.entity.RuleStatus;
import com.aiapitesting.backend.entity.RuleTemplate;
import com.aiapitesting.backend.repository.BusinessRuleRepository;
import com.aiapitesting.backend.service.rule.RuleExtractionVerifier.DropReason;
import com.aiapitesting.backend.service.rule.RuleExtractionVerifier.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuleProposalWriterTest {

    @Mock
    private BusinessRuleService ruleService;
    @Mock
    private BusinessRuleRepository ruleRepository;

    private RuleProposalWriter writer;
    private Project project;
    private Endpoint endpoint;
    private Map<String, Endpoint> endpoints;
    private final UUID jobId = UUID.randomUUID();
    private int counter;

    @BeforeEach
    void setUp() {
        writer = new RuleProposalWriter(ruleService, ruleRepository);
        project = Project.builder().id(UUID.randomUUID()).build();
        endpoint = Endpoint.builder().id(UUID.randomUUID()).project(project).method("POST").path("/jobs").build();
        endpoints = Map.of("POST /jobs", endpoint);
        counter = 0;
        // build() giả lập: trạng thái theo cờ trong rationale để test các nhánh
        lenient().when(ruleService.build(any(), any(), any(), any(), any(), any(), any())).thenAnswer(i -> {
            String rationale = i.getArgument(5);
            RuleStatus status = rationale != null && rationale.contains("TRUNG_SPEC") ? RuleStatus.REJECTED
                    : rationale != null && rationale.contains("MAU_THUAN") ? RuleStatus.CONFLICT : RuleStatus.PROPOSED;
            return BusinessRule.builder().project(project).endpointKey("POST /jobs").template(i.getArgument(2)).status(status)
                    .source(i.getArgument(6)).paramsJson("{}").fingerprint("fp-" + (counter++)).build();
        });
        lenient().when(ruleRepository.save(any(BusinessRule.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Verdict verdict(RuleSource source, String quote, String rationale) {
        return new Verdict(null, null, "POST /jobs", RuleTemplate.OR, new RuleParams(null, null, null, null, null, List.of("a", "b"), null, null, null,
                null, null, null, null, null), source, source == RuleSource.DOC ? UUID.randomUUID() : null, quote, quote == null ? null : 10, null,
                rationale, null);
    }

    @Test
    void savesWithJobQuoteAndDocument_andCountsBySource() {
        List<Verdict> verdicts = List.of(verdict(RuleSource.DOC, "đoạn trích từ tài liệu số một", "r1"),
                verdict(RuleSource.INSTRUCTION, "đoạn trích từ chỉ dẫn của bạn", "r2"), verdict(RuleSource.INFERRED, null, "GIẢ ĐỊNH: r3"));

        RuleProposalWriter.Outcome outcome = writer.write(project, endpoints, verdicts, jobId, 8);

        assertThat(outcome.fromDocument()).isEqualTo(1);
        assertThat(outcome.fromInstruction()).isEqualTo(1);
        assertThat(outcome.inferred()).isEqualTo(1);
        assertThat(outcome.proposed()).isEqualTo(3);
        ArgumentCaptor<BusinessRule> saved = ArgumentCaptor.forClass(BusinessRule.class);
        verify(ruleRepository, times(3)).save(saved.capture());
        BusinessRule first = saved.getAllValues().get(0);
        assertThat(first.getExtractionJobId()).isEqualTo(jobId);
        assertThat(first.getQuote()).isEqualTo("đoạn trích từ tài liệu số một");
        assertThat(first.getQuoteOffset()).isEqualTo(10);
        assertThat(first.getDocumentId()).isNotNull();
    }

    @Test
    void perEndpointCap_keepsRulesWithQuotesFirst_andCapsInferredAtThree() {
        List<Verdict> verdicts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            verdicts.add(verdict(RuleSource.INFERRED, null, "GIẢ ĐỊNH: i" + i));
        }
        verdicts.add(verdict(RuleSource.DOC, "đoạn trích hợp lệ đủ dài nhé", "doc1"));
        verdicts.add(verdict(RuleSource.DOC, "một đoạn trích khác cũng đủ dài", "doc2"));

        RuleProposalWriter.Outcome outcome = writer.write(project, endpoints, verdicts, jobId, 4);

        assertThat(outcome.fromDocument()).isEqualTo(2);   // luật có trích dẫn được giữ trước
        assertThat(outcome.inferred()).isEqualTo(2);       // còn 2 chỗ trong trần 4
        assertThat(outcome.skippedOverCap()).isEqualTo(3);
        verify(ruleRepository, times(4)).save(any());
    }

    @Test
    void inferredCapIsThreePerEndpointEvenWhenTheTotalCapIsLarger() {
        List<Verdict> verdicts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            verdicts.add(verdict(RuleSource.INFERRED, null, "GIẢ ĐỊNH: i" + i));
        }
        RuleProposalWriter.Outcome outcome = writer.write(project, endpoints, verdicts, jobId, 8);
        assertThat(outcome.inferred()).isEqualTo(RuleProposalWriter.INFERRED_CAP_PER_ENDPOINT);
        assertThat(outcome.skippedOverCap()).isEqualTo(3);
    }

    @Test
    void existingFingerprintAndRaceOnInsert_areSkipped_notDuplicated() {
        when(ruleRepository.existsByProjectAndFingerprint(eq(project), eq("fp-0"))).thenReturn(true);
        when(ruleRepository.save(any(BusinessRule.class))).thenThrow(new DataIntegrityViolationException("dup"));

        RuleProposalWriter.Outcome outcome = writer.write(project, endpoints,
                List.of(verdict(RuleSource.DOC, "trích dẫn số một đủ dài", "a"), verdict(RuleSource.DOC, "trích dẫn số hai đủ dài", "b")), jobId, 8);

        assertThat(outcome.skippedExisting()).isEqualTo(2);
        assertThat(outcome.proposed()).isZero();
    }

    @Test
    void duplicateOfSpecAndConflict_areStoredButCountedApart_andDroppedAreTallied() {
        List<Verdict> verdicts = List.of(verdict(RuleSource.DOC, "trích dẫn trùng spec đủ dài", "TRUNG_SPEC"),
                verdict(RuleSource.DOC, "trích dẫn mâu thuẫn đủ dài", "MAU_THUAN"),
                Verdict.class.cast(new Verdict(DropReason.UNKNOWN_ENDPOINT, "x", null, null, null, null, null, null, null, null, null, null)),
                Verdict.class.cast(new Verdict(DropReason.UNKNOWN_ENDPOINT, "y", null, null, null, null, null, null, null, null, null, null)),
                Verdict.class.cast(new Verdict(DropReason.INVALID_PARAMS, "z", null, null, null, null, null, null, null, null, null, null)));

        RuleProposalWriter.Outcome outcome = writer.write(project, endpoints, verdicts, jobId, 8);

        assertThat(outcome.rejectedAsDuplicateOfSpec()).isEqualTo(1);
        assertThat(outcome.conflicts()).isEqualTo(1);
        assertThat(outcome.fromDocument()).isZero();
        assertThat(outcome.dropped()).containsEntry(DropReason.UNKNOWN_ENDPOINT, 2).containsEntry(DropReason.INVALID_PARAMS, 1);
        verify(ruleRepository, times(2)).save(any()); // luật trùng spec vẫn được lưu (đo precision)
    }

    @Test
    void verdictForEndpointOutsideTheMap_isIgnored() {
        Verdict stray = new Verdict(null, null, "GET /khac", RuleTemplate.OR, null, RuleSource.DOC, null, "q", 1, null, "r", null);
        RuleProposalWriter.Outcome outcome = writer.write(project, endpoints, List.of(stray), jobId, 8);
        assertThat(outcome.proposed()).isZero();
        verify(ruleRepository, never()).save(any());
    }
}
