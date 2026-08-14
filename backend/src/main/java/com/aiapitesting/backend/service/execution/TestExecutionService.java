package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.dto.request.TestExecutionRequest;
import com.aiapitesting.backend.dto.response.TestExecutionResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseDependency;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestExecutionEndpoint;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.exception.TestExecutionNotFoundException;
import com.aiapitesting.backend.repository.TestCaseDependencyRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestExecutionEndpointRepository;
import com.aiapitesting.backend.repository.TestExecutionRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.aiapitesting.backend.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestration đồng bộ: validate, tính tập test case cần chạy (kể cả tự động kéo theo nguồn phụ
 * thuộc - Test Data Chaining, Module 7), sắp thứ tự chạy, tạo TestExecution (PENDING), rồi giao
 * cho TestExecutionRunner (bean riêng, có @Async) chạy nền - tách riêng 2 class vì self-invocation
 * trong cùng 1 bean khiến @Async không có tác dụng (bỏ qua proxy AOP của Spring khi gọi qua "this").
 */
@Service
@RequiredArgsConstructor
public class TestExecutionService {

    private final ProjectService projectService;
    private final TestCaseRepository testCaseRepository;
    private final TestCaseDependencyRepository testCaseDependencyRepository;
    private final TestExecutionRepository testExecutionRepository;
    private final TestResultRepository testResultRepository;
    private final TestExecutionEndpointRepository testExecutionEndpointRepository;
    private final TestExecutionRunner testExecutionRunner;

    public TestExecutionResponse trigger(UUID projectId, TestExecutionRequest request) {
        Project project = projectService.getOwnedProject(projectId);
        if (project.getTargetBaseUrl() == null || project.getTargetBaseUrl().isBlank()) {
            throw new InvalidRequestException(
                    "Project chưa cấu hình Target Base URL, vui lòng import lại và điền URL");
        }

        List<TestCase> selected = resolveOwnedTestCases(project, request.testCaseIds());
        Set<UUID> selectedIds = selected.stream().map(TestCase::getId).collect(Collectors.toSet());

        // Toàn bộ dependency trong project, load 1 lần - BFS bao đóng bắc cầu chạy hoàn toàn trong
        // bộ nhớ, không cần query thêm cho từng vòng lặp.
        List<TestCaseDependency> allDependencies = testCaseDependencyRepository.findAllByTestCaseEndpointProject(project);
        Map<UUID, List<TestCaseDependency>> dependenciesByConsumerId = allDependencies.stream()
                .collect(Collectors.groupingBy(d -> d.getTestCase().getId()));

        Map<UUID, TestCase> testCaseById = new HashMap<>();
        selected.forEach(tc -> testCaseById.put(tc.getId(), tc));

        Set<UUID> fullSetIds = new LinkedHashSet<>(selectedIds);
        Deque<UUID> queue = new ArrayDeque<>(selectedIds);
        while (!queue.isEmpty()) {
            UUID currentId = queue.poll();
            for (TestCaseDependency dep : dependenciesByConsumerId.getOrDefault(currentId, List.of())) {
                TestCase source = dep.getDependsOnTestCase();
                testCaseById.putIfAbsent(source.getId(), source);
                if (fullSetIds.add(source.getId())) {
                    queue.add(source.getId());
                }
            }
        }
        Set<UUID> autoIncludedIds = fullSetIds.stream()
                .filter(id -> !selectedIds.contains(id))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<UUID, Set<UUID>> graph = buildDependencyGraph(fullSetIds, testCaseById, dependenciesByConsumerId);
        detectCycle(graph);
        List<TestCase> ordered = topologicalSort(graph, testCaseById);

        Map<UUID, List<DependencyEdge>> edgesByConsumerId = fullSetIds.stream()
                .collect(Collectors.toMap(id -> id, id -> dependenciesByConsumerId.getOrDefault(id, List.of()).stream()
                        .map(d -> new DependencyEdge(
                                d.getDependsOnTestCase().getId(), d.getDependsOnTestCase().getName(),
                                d.getJsonPath(), d.getPlaceholderName()))
                        .toList()));

        TestExecution execution = testExecutionRepository.save(
                TestExecution.builder().project(project).status(ExecutionStatus.PENDING).build());

        // Lịch sử kiểm thử (Module 8) - ghi 1 dòng cho MỌI endpoint có mặt trong tập test case đã
        // chạy (tự chọn lẫn auto-included do chaining), để xem lịch sử của bất kỳ endpoint nào trong
        // số đó cũng thấy đúng sự kiện chạy này, không chỉ endpoint được chọn tường minh.
        Map<UUID, Endpoint> endpointsInvolved = new LinkedHashMap<>();
        for (UUID testCaseId : fullSetIds) {
            Endpoint endpoint = testCaseById.get(testCaseId).getEndpoint();
            endpointsInvolved.putIfAbsent(endpoint.getId(), endpoint);
        }
        testExecutionEndpointRepository.saveAll(endpointsInvolved.values().stream()
                .map(endpoint -> TestExecutionEndpoint.builder().execution(execution).endpoint(endpoint).build())
                .toList());

        testExecutionRunner.runInBackground(execution, ordered, project, edgesByConsumerId, autoIncludedIds);

        return TestExecutionResponse.pending(execution, List.copyOf(autoIncludedIds));
    }

    public TestExecutionResponse getExecution(UUID projectId, UUID executionId) {
        Project project = projectService.getOwnedProject(projectId);
        TestExecution execution = testExecutionRepository.findByIdAndProject(executionId, project)
                .orElseThrow(() -> new TestExecutionNotFoundException("Không tìm thấy lần thực thi với id đã cho"));
        return TestExecutionResponse.from(execution, testResultRepository.findAllByExecutionOrderByTestCaseCreatedAt(execution));
    }

    private List<TestCase> resolveOwnedTestCases(Project project, List<UUID> testCaseIds) {
        Set<UUID> requestedIds = new HashSet<>(testCaseIds);
        List<TestCase> testCases = testCaseRepository.findAllByIdInAndEndpointProject(testCaseIds, project);
        if (testCases.size() != requestedIds.size()) {
            throw new InvalidRequestException("Một số test case không thuộc project này");
        }
        return testCases;
    }

    /**
     * Đồ thị phụ thuộc: node -> tập node phải chạy TRƯỚC nó (node "chờ" các node trong tập).
     * Gồm cạnh tường minh (TestCaseDependency) + cạnh ngầm định: mọi test case DELETE tự động
     * chờ mọi test case khác (khác DELETE) cùng phụ thuộc ít nhất 1 nguồn chung - tránh xoá dữ liệu
     * trước khi các test dùng chung nguồn đó kịp chạy.
     */
    private Map<UUID, Set<UUID>> buildDependencyGraph(
            Set<UUID> fullSetIds, Map<UUID, TestCase> testCaseById, Map<UUID, List<TestCaseDependency>> dependenciesByConsumerId
    ) {
        Map<UUID, Set<UUID>> graph = new HashMap<>();
        Map<UUID, Set<UUID>> sourcesOf = new HashMap<>();
        for (UUID id : fullSetIds) {
            Set<UUID> sources = dependenciesByConsumerId.getOrDefault(id, List.of()).stream()
                    .map(d -> d.getDependsOnTestCase().getId())
                    .collect(Collectors.toCollection(HashSet::new));
            graph.put(id, new HashSet<>(sources));
            sourcesOf.put(id, sources);
        }

        for (UUID id : fullSetIds) {
            if (!isDelete(testCaseById.get(id))) {
                continue;
            }
            Set<UUID> deleteSources = sourcesOf.get(id);
            if (deleteSources.isEmpty()) {
                continue;
            }
            for (UUID otherId : fullSetIds) {
                if (otherId.equals(id) || isDelete(testCaseById.get(otherId))) {
                    continue;
                }
                boolean sharesSource = sourcesOf.get(otherId).stream().anyMatch(deleteSources::contains);
                if (sharesSource) {
                    graph.get(id).add(otherId);
                }
            }
        }
        return graph;
    }

    private boolean isDelete(TestCase testCase) {
        return "DELETE".equalsIgnoreCase(testCase.getEndpoint().getMethod());
    }

    private void detectCycle(Map<UUID, Set<UUID>> graph) {
        Set<UUID> visiting = new HashSet<>();
        Set<UUID> visited = new HashSet<>();
        for (UUID node : graph.keySet()) {
            if (!visited.contains(node)) {
                detectCycle(node, graph, visiting, visited);
            }
        }
    }

    private void detectCycle(UUID node, Map<UUID, Set<UUID>> graph, Set<UUID> visiting, Set<UUID> visited) {
        visiting.add(node);
        for (UUID dependsOn : graph.getOrDefault(node, Set.of())) {
            if (visiting.contains(dependsOn)) {
                throw new InvalidRequestException("Phát hiện phụ thuộc vòng giữa các test case đã chọn");
            }
            if (!visited.contains(dependsOn)) {
                detectCycle(dependsOn, graph, visiting, visited);
            }
        }
        visiting.remove(node);
        visited.add(node);
    }

    /**
     * Kahn's algorithm ra đúng 1 thứ tự tuyến tính duy nhất (không chia wave/song song) - tie-break
     * bằng createdAt tăng dần khi nhiều node cùng sẵn sàng 1 lúc, cho kết quả tất định.
     */
    private List<TestCase> topologicalSort(Map<UUID, Set<UUID>> graph, Map<UUID, TestCase> testCaseById) {
        Map<UUID, Set<UUID>> remaining = new HashMap<>();
        graph.forEach((id, deps) -> remaining.put(id, new HashSet<>(deps)));

        List<TestCase> ordered = new ArrayList<>();
        Set<UUID> done = new HashSet<>();
        while (ordered.size() < graph.size()) {
            List<UUID> ready = remaining.entrySet().stream()
                    .filter(e -> !done.contains(e.getKey()) && e.getValue().isEmpty())
                    .map(Map.Entry::getKey)
                    .sorted(Comparator.comparing(id -> testCaseById.get(id).getCreatedAt()))
                    .toList();
            if (ready.isEmpty()) {
                // Đã soi cycle trước đó nên về lý thuyết không tới đây - chặn phòng hờ.
                throw new InvalidRequestException("Không thể sắp xếp thứ tự chạy do phụ thuộc vòng");
            }
            for (UUID id : ready) {
                ordered.add(testCaseById.get(id));
                done.add(id);
            }
            remaining.values().forEach(deps -> deps.removeAll(done));
        }
        return ordered;
    }
}
