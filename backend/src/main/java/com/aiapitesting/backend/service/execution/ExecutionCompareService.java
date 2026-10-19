package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.dto.response.ExecutionCompareResponse;
import com.aiapitesting.backend.dto.response.ExecutionDiffCategory;
import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.exception.ExecutionInProgressException;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.exception.TestExecutionNotFoundException;
import com.aiapitesting.backend.repository.TestExecutionRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.aiapitesting.backend.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Module 17 (17c) - so sánh 2 lượt chạy của cùng 1 project. Khớp kết quả theo testCaseId (duy nhất trong mỗi lượt), phân loại bằng
 * {@link ExecutionDiffClassifier}. Chỉ đọc vài cột nhẹ (không responseBody), 2 truy vấn cho cả 2 lượt, phân trang trong bộ nhớ
 * (vài nghìn dòng nhẹ) - không gọi AI.
 */
@Service
@RequiredArgsConstructor
public class ExecutionCompareService {

    static final int MAX_PAGE_SIZE = 200;

    private final ProjectService projectService;
    private final TestExecutionRepository testExecutionRepository;
    private final TestResultRepository testResultRepository;

    public ExecutionCompareResponse compare(UUID projectId, UUID baseId, UUID targetId, ExecutionDiffCategory category, int page, int size) {
        if (Objects.equals(baseId, targetId)) {
            throw new InvalidRequestException("Hãy chọn hai lần chạy khác nhau để so sánh");
        }
        Project project = projectService.getOwnedProject(projectId);
        TestExecution base = findFinished(baseId, project);
        TestExecution target = findFinished(targetId, project);

        Map<UUID, TestResultRepository.DiffRow> baseRows = indexByTestCase(testResultRepository.findDiffRowsByExecution(base));
        Map<UUID, TestResultRepository.DiffRow> targetRows = indexByTestCase(testResultRepository.findDiffRowsByExecution(target));

        Set<UUID> testCaseIds = new LinkedHashSet<>(baseRows.keySet());
        testCaseIds.addAll(targetRows.keySet());

        Map<ExecutionDiffCategory, Long> counts = new EnumMap<>(ExecutionDiffCategory.class);
        List<ExecutionCompareResponse.Item> items = new ArrayList<>();
        for (UUID testCaseId : testCaseIds) {
            TestResultRepository.DiffRow b = baseRows.get(testCaseId);
            TestResultRepository.DiffRow t = targetRows.get(testCaseId);
            ExecutionDiffClassifier.Verdict verdict = ExecutionDiffClassifier.classify(sideOf(b), sideOf(t));
            counts.merge(verdict.category(), 1L, Long::sum);
            boolean listed = category != null ? verdict.category() == category : verdict.category() != ExecutionDiffCategory.STILL_PASSING;
            if (listed) {
                TestResultRepository.DiffRow any = t != null ? t : b;
                items.add(new ExecutionCompareResponse.Item(
                        testCaseId, any.getTestCaseName(), any.getEndpointId(), any.getEndpointMethod(), any.getEndpointPath(),
                        verdict.category(),
                        b == null ? null : b.getStatus(), t == null ? null : t.getStatus(),
                        b == null ? null : b.getResponseStatus(), t == null ? null : t.getResponseStatus(),
                        b == null ? null : b.getFailureCause(), t == null ? null : t.getFailureCause(),
                        verdict.note()));
            }
        }
        // Hồi quy nổi bật trước, rồi theo endpoint để các case cùng endpoint nằm gần nhau.
        items.sort(Comparator.comparing(ExecutionCompareResponse.Item::category)
                .thenComparing(i -> i.endpointPath() == null ? "" : i.endpointPath())
                .thenComparing(i -> i.endpointMethod() == null ? "" : i.endpointMethod())
                .thenComparing(i -> i.testCaseName() == null ? "" : i.testCaseName()));

        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int safePage = Math.max(0, page);
        int from = (int) Math.min((long) safePage * safeSize, items.size());
        List<ExecutionCompareResponse.Item> slice = items.subList(from, Math.min(from + safeSize, items.size()));
        PageResponse<ExecutionCompareResponse.Item> paged = new PageResponse<>(
                List.copyOf(slice), safePage, safeSize, items.size(), (int) Math.ceil(items.size() / (double) safeSize));

        boolean environmentChanged = !Objects.equals(base.getTargetBaseUrlSnapshot(), target.getTargetBaseUrlSnapshot())
                || !Objects.equals(base.getTargetAuthUpdatedAtSnapshot(), target.getTargetAuthUpdatedAtSnapshot());
        ExecutionCompareResponse.Summary summary = new ExecutionCompareResponse.Summary(
                counts.getOrDefault(ExecutionDiffCategory.REGRESSED, 0L), counts.getOrDefault(ExecutionDiffCategory.FIXED, 0L),
                counts.getOrDefault(ExecutionDiffCategory.STILL_FAILING, 0L), counts.getOrDefault(ExecutionDiffCategory.CHANGED_TEST, 0L),
                counts.getOrDefault(ExecutionDiffCategory.UNRELIABLE, 0L), counts.getOrDefault(ExecutionDiffCategory.INCONCLUSIVE, 0L),
                counts.getOrDefault(ExecutionDiffCategory.ONLY_IN_TARGET, 0L), counts.getOrDefault(ExecutionDiffCategory.ONLY_IN_BASE, 0L),
                counts.getOrDefault(ExecutionDiffCategory.STILL_PASSING, 0L), environmentChanged);
        return new ExecutionCompareResponse(refOf(base), refOf(target), summary, paged);
    }

    /** Các lượt chạy gần nhất (mới nhất trước) kèm số kết quả - để chọn lượt cần so sánh. */
    public List<com.aiapitesting.backend.dto.response.ExecutionSummaryResponse> listRecent(UUID projectId) {
        Project project = projectService.getOwnedProject(projectId);
        List<TestExecution> executions = testExecutionRepository.findTop50ByProjectOrderByStartedAtDesc(project);
        if (executions.isEmpty()) {
            return List.of();
        }
        Map<UUID, long[]> counts = new java.util.HashMap<>(); // [total, passed, failed]
        for (TestResultRepository.ExecutionStatusCount row : testResultRepository.countByExecutionsGroupedByStatus(executions)) {
            long[] c = counts.computeIfAbsent(row.getExecutionId(), k -> new long[3]);
            c[0] += row.getCount();
            if (row.getStatus() == com.aiapitesting.backend.entity.TestResultStatus.PASSED) {
                c[1] += row.getCount();
            } else if (row.getStatus() == com.aiapitesting.backend.entity.TestResultStatus.FAILED) {
                c[2] += row.getCount();
            }
        }
        return executions.stream().map(e -> {
            long[] c = counts.getOrDefault(e.getId(), new long[3]);
            return new com.aiapitesting.backend.dto.response.ExecutionSummaryResponse(
                    e.getId(), e.getStartedAt(), e.getFinishedAt(), e.getStatus(), c[0], c[1], c[2]);
        }).toList();
    }

    private TestExecution findFinished(UUID executionId, Project project) {
        TestExecution execution = testExecutionRepository.findByIdAndProject(executionId, project)
                .orElseThrow(() -> new TestExecutionNotFoundException("Không tìm thấy lần thực thi với id đã cho"));
        if (execution.getStatus() == ExecutionStatus.PENDING || execution.getStatus() == ExecutionStatus.RUNNING) {
            throw new ExecutionInProgressException("Lần chạy chưa xong, hãy đợi chạy xong rồi so sánh");
        }
        return execution;
    }

    private Map<UUID, TestResultRepository.DiffRow> indexByTestCase(List<TestResultRepository.DiffRow> rows) {
        return rows.stream().collect(Collectors.toMap(TestResultRepository.DiffRow::getTestCaseId, Function.identity(), (a, b) -> a));
    }

    private ExecutionDiffClassifier.Side sideOf(TestResultRepository.DiffRow row) {
        return row == null ? null : new ExecutionDiffClassifier.Side(row.getStatus(), row.getFailureCause(), row.getUnstable(), row.getFingerprint());
    }

    private ExecutionCompareResponse.ExecutionRef refOf(TestExecution execution) {
        return new ExecutionCompareResponse.ExecutionRef(execution.getId(), execution.getStartedAt(), execution.getStatus(),
                execution.getTargetBaseUrlSnapshot());
    }
}
