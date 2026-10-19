package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.dto.response.ExecutionCompareResponse;
import com.aiapitesting.backend.dto.response.ExecutionDiffCategory;
import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.entity.FailureCause;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.exception.ExecutionInProgressException;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.exception.TestExecutionNotFoundException;
import com.aiapitesting.backend.repository.TestExecutionRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.aiapitesting.backend.repository.TestResultRepository.DiffRow;
import com.aiapitesting.backend.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionCompareServiceTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private TestExecutionRepository testExecutionRepository;

    @Mock
    private TestResultRepository testResultRepository;

    @InjectMocks
    private ExecutionCompareService service;

    private UUID projectId;
    private Project project;
    private TestExecution base;
    private TestExecution target;

    private record Row(UUID testCaseId, String testCaseName, UUID endpointId, String endpointMethod, String endpointPath,
                       TestResultStatus status, Integer responseStatus, FailureCause failureCause, Boolean unstable,
                       String fingerprint) implements DiffRow {
        @Override public UUID getTestCaseId() { return testCaseId; }
        @Override public String getTestCaseName() { return testCaseName; }
        @Override public UUID getEndpointId() { return endpointId; }
        @Override public String getEndpointMethod() { return endpointMethod; }
        @Override public String getEndpointPath() { return endpointPath; }
        @Override public TestResultStatus getStatus() { return status; }
        @Override public Integer getResponseStatus() { return responseStatus; }
        @Override public FailureCause getFailureCause() { return failureCause; }
        @Override public Boolean getUnstable() { return unstable; }
        @Override public String getFingerprint() { return fingerprint; }
    }

    private static Row row(UUID id, String name, String path, TestResultStatus status, FailureCause cause) {
        return new Row(id, name, UUID.randomUUID(), "GET", path, status, status == TestResultStatus.PASSED ? 200 : 500, cause, null, "fp-" + id);
    }

    private static Row sameTest(Row original, TestResultStatus status, FailureCause cause) {
        return new Row(original.testCaseId(), original.testCaseName(), original.endpointId(), original.endpointMethod(), original.endpointPath(),
                status, status == TestResultStatus.PASSED ? 200 : 500, cause, null, original.fingerprint());
    }

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        project = Project.builder().id(projectId).build();
        lenient().when(projectService.getOwnedProject(projectId)).thenReturn(project);
        base = execution(ExecutionStatus.COMPLETED, "http://target");
        target = execution(ExecutionStatus.COMPLETED, "http://target");
        lenient().when(testExecutionRepository.findByIdAndProject(base.getId(), project)).thenReturn(Optional.of(base));
        lenient().when(testExecutionRepository.findByIdAndProject(target.getId(), project)).thenReturn(Optional.of(target));
    }

    private TestExecution execution(ExecutionStatus status, String baseUrl) {
        return TestExecution.builder().id(UUID.randomUUID()).project(project).status(status).startedAt(Instant.now())
                .targetBaseUrlSnapshot(baseUrl).build();
    }

    private void results(List<DiffRow> baseRows, List<DiffRow> targetRows) {
        when(testResultRepository.findDiffRowsByExecution(base)).thenReturn(baseRows);
        when(testResultRepository.findDiffRowsByExecution(target)).thenReturn(targetRows);
    }

    @Test
    void compare_summarisesEveryGroup_andListsEverythingExceptStillPassingByDefault() {
        Row a = row(UUID.randomUUID(), "A", "/a", TestResultStatus.PASSED, null);
        Row b = row(UUID.randomUUID(), "B", "/b", TestResultStatus.FAILED, FailureCause.STABLE_FAILURE);
        Row c = row(UUID.randomUUID(), "C", "/c", TestResultStatus.PASSED, null);
        Row d = row(UUID.randomUUID(), "D", "/d", TestResultStatus.PASSED, null);
        Row onlyBase = row(UUID.randomUUID(), "OnlyBase", "/e", TestResultStatus.PASSED, null);
        Row onlyTarget = row(UUID.randomUUID(), "OnlyTarget", "/f", TestResultStatus.PASSED, null);
        results(List.of(a, b, c, d, onlyBase),
                List.of(sameTest(a, TestResultStatus.FAILED, FailureCause.STABLE_FAILURE), // hồi quy
                        sameTest(b, TestResultStatus.PASSED, null),                          // đã sửa
                        sameTest(c, TestResultStatus.PASSED, null),                          // vẫn pass
                        sameTest(d, TestResultStatus.FAILED, FailureCause.AUTH_CONFIG),      // token hỏng: không kết luận
                        onlyTarget));

        ExecutionCompareResponse result = service.compare(projectId, base.getId(), target.getId(), null, 0, 50);

        assertThat(result.summary().regressed()).isEqualTo(1);
        assertThat(result.summary().fixed()).isEqualTo(1);
        assertThat(result.summary().stillPassing()).isEqualTo(1);
        assertThat(result.summary().inconclusive()).isEqualTo(1);
        assertThat(result.summary().onlyInBase()).isEqualTo(1);
        assertThat(result.summary().onlyInTarget()).isEqualTo(1);
        assertThat(result.items().totalElements()).isEqualTo(5); // STILL_PASSING không được liệt kê
        assertThat(result.items().data().get(0).category()).isEqualTo(ExecutionDiffCategory.REGRESSED); // hồi quy lên đầu
        assertThat(result.items().data()).noneMatch(i -> i.category() == ExecutionDiffCategory.STILL_PASSING);
    }

    @Test
    void compare_categoryFilter_returnsOnlyThatGroup_butSummaryStaysComplete() {
        Row a = row(UUID.randomUUID(), "A", "/a", TestResultStatus.PASSED, null);
        Row b = row(UUID.randomUUID(), "B", "/b", TestResultStatus.PASSED, null);
        results(List.of(a, b), List.of(sameTest(a, TestResultStatus.FAILED, FailureCause.STABLE_FAILURE), sameTest(b, TestResultStatus.PASSED, null)));

        ExecutionCompareResponse result = service.compare(projectId, base.getId(), target.getId(), ExecutionDiffCategory.STILL_PASSING, 0, 50);

        assertThat(result.items().data()).singleElement().satisfies(i -> assertThat(i.testCaseName()).isEqualTo("B"));
        assertThat(result.summary().regressed()).isEqualTo(1);
    }

    @Test
    void compare_pagination_slicesItemsAndReportsTotals() {
        List<DiffRow> baseRows = new ArrayList<>();
        List<DiffRow> targetRows = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            Row r = row(UUID.randomUUID(), "T" + i, "/p" + i, TestResultStatus.PASSED, null);
            baseRows.add(r);
            targetRows.add(sameTest(r, TestResultStatus.FAILED, FailureCause.STABLE_FAILURE));
        }
        results(baseRows, targetRows);

        ExecutionCompareResponse page1 = service.compare(projectId, base.getId(), target.getId(), null, 1, 3);

        assertThat(page1.items().data()).hasSize(3);
        assertThat(page1.items().page()).isEqualTo(1);
        assertThat(page1.items().totalElements()).isEqualTo(7);
        assertThat(page1.items().totalPages()).isEqualTo(3);
    }

    @Test
    void compare_environmentChangedBetweenRuns_isReported() {
        target.setTargetBaseUrlSnapshot("http://other-target");
        results(List.of(), List.of());

        assertThat(service.compare(projectId, base.getId(), target.getId(), null, 0, 50).summary().environmentChanged()).isTrue();
    }

    @Test
    void compare_sameExecutionTwice_rejected() {
        assertThatThrownBy(() -> service.compare(projectId, base.getId(), base.getId(), null, 0, 50)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void compare_unknownExecution_notFound() {
        UUID missing = UUID.randomUUID();
        when(testExecutionRepository.findByIdAndProject(missing, project)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.compare(projectId, base.getId(), missing, null, 0, 50)).isInstanceOf(TestExecutionNotFoundException.class);
    }

    @Test
    void compare_runStillInProgress_rejected() {
        target.setStatus(ExecutionStatus.RUNNING);

        assertThatThrownBy(() -> service.compare(projectId, base.getId(), target.getId(), null, 0, 50)).isInstanceOf(ExecutionInProgressException.class);
    }

    @Test
    void compare_pageSizeIsCapped() {
        results(List.of(), List.of());

        assertThat(service.compare(projectId, base.getId(), target.getId(), null, 0, 100_000).items().size()).isEqualTo(ExecutionCompareService.MAX_PAGE_SIZE);
    }

    @Test
    void listRecent_countsTotalPassedAndFailedPerExecution() {
        when(testExecutionRepository.findTop50ByProjectOrderByStartedAtDesc(project)).thenReturn(List.of(target, base));
        TestResultRepository.ExecutionStatusCount passed = statusCount(target.getId(), TestResultStatus.PASSED, 7);
        TestResultRepository.ExecutionStatusCount failed = statusCount(target.getId(), TestResultStatus.FAILED, 2);
        TestResultRepository.ExecutionStatusCount blocked = statusCount(target.getId(), TestResultStatus.BLOCKED, 1);
        when(testResultRepository.countByExecutionsGroupedByStatus(List.of(target, base))).thenReturn(List.of(passed, failed, blocked));

        var list = service.listRecent(projectId);

        assertThat(list).hasSize(2);
        assertThat(list.get(0).id()).isEqualTo(target.getId());
        assertThat(list.get(0).totalCount()).isEqualTo(10);
        assertThat(list.get(0).passedCount()).isEqualTo(7);
        assertThat(list.get(0).failedCount()).isEqualTo(2);
        assertThat(list.get(1).totalCount()).isZero(); // lượt không có kết quả nào
    }

    private static TestResultRepository.ExecutionStatusCount statusCount(UUID executionId, TestResultStatus status, long count) {
        return new TestResultRepository.ExecutionStatusCount() {
            @Override public UUID getExecutionId() { return executionId; }
            @Override public TestResultStatus getStatus() { return status; }
            @Override public long getCount() { return count; }
        };
    }
}
