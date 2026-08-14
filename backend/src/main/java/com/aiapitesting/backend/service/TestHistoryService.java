package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;
import com.aiapitesting.backend.dto.response.EndpointHistoryResponse;
import com.aiapitesting.backend.dto.response.GenerationSnapshotItemResponse;
import com.aiapitesting.backend.dto.response.HistoryEventResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestExecutionEndpoint;
import com.aiapitesting.backend.entity.TestGenerationEvent;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestExecutionEndpointRepository;
import com.aiapitesting.backend.repository.TestGenerationEventRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Đọc-tổng hợp lịch sử kiểm thử theo endpoint (Module 8) - gộp sự kiện "Sinh test case"
 * (TestGenerationEvent) và "Chạy test" (TestExecution, qua bảng nối TestExecutionEndpoint) thành 1
 * timeline/endpoint, không phải logic AI cũng không phải engine thực thi nên đặt ở service/ gốc.
 */
@Service
@RequiredArgsConstructor
public class TestHistoryService {

    private final ProjectService projectService;
    private final EndpointRepository endpointRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestGenerationEventRepository testGenerationEventRepository;
    private final TestExecutionEndpointRepository testExecutionEndpointRepository;
    private final TestResultRepository testResultRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<EndpointHistoryResponse> getHistory(UUID projectId) {
        Project project = projectService.getOwnedProject(projectId);
        List<Endpoint> endpoints = endpointRepository.findAllByProjectOrderByCreatedAtAsc(project);
        List<UUID> endpointIds = endpoints.stream().map(Endpoint::getId).toList();

        Map<UUID, Long> testCaseCountByEndpointId = testCaseRepository.countByEndpointIds(endpointIds).stream()
                .collect(Collectors.toMap(
                        TestCaseRepository.EndpointTestCaseCount::getEndpointId,
                        TestCaseRepository.EndpointTestCaseCount::getCount));

        Map<UUID, List<TestGenerationEvent>> generationsByEndpointId = testGenerationEventRepository
                .findAllByEndpointProject(project).stream()
                .collect(Collectors.groupingBy(e -> e.getEndpoint().getId()));

        List<TestExecutionEndpoint> executionLinks = testExecutionEndpointRepository.findAllByEndpointProject(project);
        Map<UUID, List<TestExecutionEndpoint>> executionLinksByEndpointId = executionLinks.stream()
                .collect(Collectors.groupingBy(link -> link.getEndpoint().getId()));
        // Số endpoint mà mỗi execution chạm tới - dùng tính otherEndpointCount (ghi chú "chạy chung
        // với endpoint khác"). Unique constraint (execution_id, endpoint_id) đảm bảo mỗi cặp chỉ 1 dòng.
        Map<UUID, Long> endpointCountByExecutionId = executionLinks.stream()
                .collect(Collectors.groupingBy(link -> link.getExecution().getId(), Collectors.counting()));

        Map<UUID, TestExecution> distinctExecutionsById = new LinkedHashMap<>();
        for (TestExecutionEndpoint link : executionLinks) {
            distinctExecutionsById.putIfAbsent(link.getExecution().getId(), link.getExecution());
        }
        List<TestResult> allResults = distinctExecutionsById.isEmpty()
                ? List.of()
                : testResultRepository.findAllByExecutionIn(List.copyOf(distinctExecutionsById.values()));
        // Nhóm theo (executionId, endpointId) để tính selectedCount/passCount/failCount riêng cho
        // từng endpoint trong 1 execution đa-endpoint - không N+1 query theo từng execution.
        Map<UUID, Map<UUID, List<TestResult>>> resultsByExecutionThenEndpoint = new LinkedHashMap<>();
        for (TestResult result : allResults) {
            resultsByExecutionThenEndpoint
                    .computeIfAbsent(result.getExecution().getId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(result.getTestCase().getEndpoint().getId(), k -> new ArrayList<>())
                    .add(result);
        }

        List<EndpointHistoryResponse> response = new ArrayList<>();
        for (Endpoint endpoint : endpoints) {
            List<HistoryEventResponse> events = new ArrayList<>();

            for (TestGenerationEvent event : generationsByEndpointId.getOrDefault(endpoint.getId(), List.of())) {
                events.add(HistoryEventResponse.generation(
                        event.getId(), event.getCreatedAt(), event.getTestCaseCount(), parseSnapshot(event.getSnapshotJson())));
            }

            List<TestExecutionEndpoint> links = executionLinksByEndpointId.getOrDefault(endpoint.getId(), List.of());
            for (TestExecutionEndpoint link : links) {
                TestExecution execution = link.getExecution();
                List<TestResult> resultsForEndpoint = resultsByExecutionThenEndpoint
                        .getOrDefault(execution.getId(), Map.of())
                        .getOrDefault(endpoint.getId(), List.of());
                int selectedCount = resultsForEndpoint.size();
                int passCount = (int) resultsForEndpoint.stream()
                        .filter(r -> r.getStatus() == TestResultStatus.PASSED).count();
                int failCount = selectedCount - passCount;
                int otherEndpointCount = (int) (endpointCountByExecutionId.getOrDefault(execution.getId(), 1L) - 1);
                events.add(HistoryEventResponse.execution(
                        link.getId(), execution.getStartedAt(), execution.getId(),
                        selectedCount, passCount, failCount, otherEndpointCount));
            }

            if (events.isEmpty()) {
                continue; // Endpoint chưa có lịch sử gì - không hiện trong trang Lịch sử kiểm thử.
            }
            events.sort(Comparator.comparing(HistoryEventResponse::occurredAt));

            response.add(new EndpointHistoryResponse(
                    endpoint.getId(), endpoint.getMethod(), endpoint.getPath(), endpoint.getSummary(),
                    testCaseCountByEndpointId.getOrDefault(endpoint.getId(), 0L).intValue(),
                    links.size(),
                    events));
        }
        return response;
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
