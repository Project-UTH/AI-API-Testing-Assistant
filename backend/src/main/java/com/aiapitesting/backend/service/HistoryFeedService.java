package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.dto.request.HistoryStatusFilter;
import com.aiapitesting.backend.dto.response.GenerationSnapshotItemResponse;
import com.aiapitesting.backend.dto.response.HistoryFeedItemResponse;
import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.entity.BugReportEvent;
import com.aiapitesting.backend.entity.BugReportEventType;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestExecutionEndpoint;
import com.aiapitesting.backend.entity.TestGenerationEvent;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.repository.BugReportEventRepository;
import com.aiapitesting.backend.repository.TestExecutionEndpointRepository;
import com.aiapitesting.backend.repository.TestGenerationEventRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Đọc-tổng hợp Lịch sử tổng (Module 8) - giống {@link TestHistoryService} nhưng gộp MỌI project
 * thuộc user hiện tại (không phải 1 project) thành 1 feed phẳng, có filter + phân trang. Cách gộp
 * dữ liệu giữ nguyên kiểu "load rồi gộp/sắp xếp trong Java" như TestHistoryService - dữ liệu vẫn
 * giới hạn theo owner nên không cần native SQL UNION + phân trang thật ở DB.
 */
@Service
@RequiredArgsConstructor
public class HistoryFeedService {

    private final CurrentUserService currentUserService;
    private final TestGenerationEventRepository testGenerationEventRepository;
    private final TestExecutionEndpointRepository testExecutionEndpointRepository;
    private final TestResultRepository testResultRepository;
    private final BugReportEventRepository bugReportEventRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PageResponse<HistoryFeedItemResponse> getFeed(
            UUID projectId, UUID endpointId, Instant from, Instant to,
            HistoryStatusFilter status, Pageable pageable
    ) {
        User owner = currentUserService.getCurrentUser();
        HistoryStatusFilter effectiveStatus = status == null ? HistoryStatusFilter.ALL : status;

        // status != ALL chỉ áp dụng cho sự kiện "Chạy test" - sự kiện "Sinh test case"/"Bug Report"
        // không có khái niệm pass/fail nên bị ẩn hẳn khi lọc trạng thái; không cần tốn công truy vấn.
        List<TestGenerationEvent> generations = effectiveStatus == HistoryStatusFilter.ALL
                ? testGenerationEventRepository.findAllForHistoryFeed(owner, projectId, endpointId, from, to)
                : List.of();

        List<BugReportEvent> bugReportEvents = effectiveStatus == HistoryStatusFilter.ALL
                ? bugReportEventRepository.findAllForHistoryFeed(owner, projectId, endpointId, from, to)
                : List.of();

        // CỐ Ý không lọc endpointId ở đây (xem ghi chú trong TestExecutionEndpointRepository) -
        // otherEndpointCount phải tính trên toàn bộ endpoint 1 execution chạm tới trước khi lọc.
        List<TestExecutionEndpoint> executionLinks =
                testExecutionEndpointRepository.findAllForHistoryFeed(owner, projectId, from, to);

        Map<UUID, Long> endpointCountByExecutionId = executionLinks.stream()
                .collect(Collectors.groupingBy(link -> link.getExecution().getId(), Collectors.counting()));

        Map<UUID, TestExecution> distinctExecutionsById = new LinkedHashMap<>();
        for (TestExecutionEndpoint link : executionLinks) {
            distinctExecutionsById.putIfAbsent(link.getExecution().getId(), link.getExecution());
        }
        List<TestResult> allResults = distinctExecutionsById.isEmpty()
                ? List.of()
                : testResultRepository.findAllByExecutionIn(List.copyOf(distinctExecutionsById.values()));
        Map<UUID, Map<UUID, List<TestResult>>> resultsByExecutionThenEndpoint = new LinkedHashMap<>();
        for (TestResult result : allResults) {
            resultsByExecutionThenEndpoint
                    .computeIfAbsent(result.getExecution().getId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(result.getTestCase().getEndpoint().getId(), k -> new ArrayList<>())
                    .add(result);
        }

        List<HistoryFeedItemResponse> items = new ArrayList<>();

        for (TestGenerationEvent event : generations) {
            Endpoint endpoint = event.getEndpoint();
            Project project = endpoint.getProject();
            items.add(HistoryFeedItemResponse.generation(
                    event.getId(), event.getCreatedAt(), project.getId(), project.getName(),
                    endpoint.getId(), endpoint.getMethod(), endpoint.getPath(),
                    event.getTestCaseCount(), parseSnapshot(event.getSnapshotJson())));
        }

        for (BugReportEvent event : bugReportEvents) {
            Endpoint endpoint = event.getEndpoint();
            Project project = endpoint.getProject();
            String type = event.getEventType() == BugReportEventType.CREATED
                    ? "BUG_REPORT_CREATED" : "BUG_REPORT_DELETED";
            items.add(HistoryFeedItemResponse.bugReportEvent(
                    event.getId(), type, event.getOccurredAt(), project.getId(), project.getName(),
                    endpoint.getId(), endpoint.getMethod(), endpoint.getPath(),
                    event.getBugId(), event.getSummary(), event.getBugReportId()));
        }

        for (TestExecutionEndpoint link : executionLinks) {
            Endpoint endpoint = link.getEndpoint();
            if (endpointId != null && !endpoint.getId().equals(endpointId)) {
                continue;
            }
            TestExecution execution = link.getExecution();
            List<TestResult> resultsForEndpoint = resultsByExecutionThenEndpoint
                    .getOrDefault(execution.getId(), Map.of())
                    .getOrDefault(endpoint.getId(), List.of());
            int selectedCount = resultsForEndpoint.size();
            int passCount = (int) resultsForEndpoint.stream()
                    .filter(r -> r.getStatus() == TestResultStatus.PASSED).count();
            int failCount = selectedCount - passCount;

            if (effectiveStatus == HistoryStatusFilter.HAS_FAIL && failCount == 0) {
                continue;
            }
            if (effectiveStatus == HistoryStatusFilter.ALL_PASS && failCount > 0) {
                continue;
            }

            int otherEndpointCount = (int) (endpointCountByExecutionId.getOrDefault(execution.getId(), 1L) - 1);
            Project project = endpoint.getProject();
            items.add(HistoryFeedItemResponse.execution(
                    link.getId(), execution.getStartedAt(), project.getId(), project.getName(),
                    endpoint.getId(), endpoint.getMethod(), endpoint.getPath(),
                    execution.getId(), selectedCount, passCount, failCount, otherEndpointCount));
        }

        items.sort(Comparator.comparing(HistoryFeedItemResponse::occurredAt).reversed());

        // pageable.getSort() cố ý không dùng - feed luôn sắp mới nhất lên đầu, chỉ page/size áp dụng.
        int total = items.size();
        int start = Math.min(pageable.getPageNumber() * pageable.getPageSize(), total);
        int end = Math.min(start + pageable.getPageSize(), total);
        Page<HistoryFeedItemResponse> page = new PageImpl<>(items.subList(start, end), pageable, total);
        return PageResponse.from(page, Function.identity());
    }

    private List<GenerationSnapshotItemResponse> parseSnapshot(String snapshotJson) {
        try {
            List<GeneratedTestCase> snapshot = objectMapper.readValue(snapshotJson, new TypeReference<>() {
            });
            return snapshot.stream().map(GenerationSnapshotItemResponse::from).toList();
        } catch (Exception e) {
            throw new IllegalStateException("Không đọc được snapshot lịch sử sinh test case", e);
        }
    }
}
