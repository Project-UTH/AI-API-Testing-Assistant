package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.response.DependencySuggestionResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseDependency;
import com.aiapitesting.backend.exception.EndpointNotFoundException;
import com.aiapitesting.backend.exception.TestCaseNotFoundException;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.TestCaseDependencyRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Gợi ý tự động liên kết dữ liệu thật (Test Data Chaining, Module 7) - thuật toán tất định (không
 * gọi AI): dò endpoint POST tạo cùng resource, đề xuất test case 2xx tạo sớm nhất của endpoint đó
 * làm nguồn. Người dùng luôn phải tự bấm "Áp dụng" mới thật sự tạo TestCaseDependency.
 */
@Service
@RequiredArgsConstructor
public class DependencySuggestionService {

    private static final int MIN_2XX = 200;
    private static final int MAX_2XX = 299;

    private final ProjectService projectService;
    private final EndpointRepository endpointRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestCaseDependencyRepository testCaseDependencyRepository;
    private final TestCasePathValidator testCasePathValidator;

    public List<DependencySuggestionResponse> suggest(UUID projectId, UUID endpointId, UUID testCaseId) {
        Project project = projectService.getOwnedProject(projectId);
        Endpoint endpoint = endpointRepository.findByIdAndProject(endpointId, project)
                .orElseThrow(() -> new EndpointNotFoundException("Không tìm thấy endpoint với id đã cho"));
        TestCase testCase = testCaseRepository.findByIdAndEndpoint(testCaseId, endpoint)
                .orElseThrow(() -> new TestCaseNotFoundException("Không tìm thấy test case với id đã cho"));

        Set<String> placeholders = testCasePathValidator.extractPlaceholders(testCase.getResolvedPath());
        Set<String> alreadyCovered = testCaseDependencyRepository.findAllByTestCase(testCase).stream()
                .map(TestCaseDependency::getPlaceholderName)
                .collect(Collectors.toSet());

        List<DependencySuggestionResponse> suggestions = new ArrayList<>();
        for (String paramName : placeholders) {
            if (alreadyCovered.contains(paramName)) {
                continue;
            }
            findSuggestion(project, endpoint, paramName).ifPresent(suggestions::add);
        }
        return suggestions;
    }

    private Optional<DependencySuggestionResponse> findSuggestion(Project project, Endpoint endpoint, String paramName) {
        String candidatePath = cutPathBeforeParam(endpoint.getPath(), paramName);
        if (candidatePath == null) {
            return Optional.empty();
        }

        Optional<Endpoint> creatorEndpoint = endpointRepository.findByProjectAndPathAndMethod(project, candidatePath, "POST");
        if (creatorEndpoint.isEmpty()) {
            return Optional.empty();
        }

        Optional<TestCase> source = testCaseRepository.findAllByEndpointOrderByCreatedAtAsc(creatorEndpoint.get()).stream()
                .filter(tc -> tc.getExpectedStatus() != null && tc.getExpectedStatus() >= MIN_2XX && tc.getExpectedStatus() <= MAX_2XX)
                .findFirst();
        if (source.isEmpty()) {
            return Optional.empty();
        }

        String sourceLabel = creatorEndpoint.get().getMethod() + " " + creatorEndpoint.get().getPath()
                + " — " + source.get().getName();
        return Optional.of(new DependencySuggestionResponse(paramName, source.get().getId(), sourceLabel, "$.id"));
    }

    /**
     * Cắt path OpenAPI gốc (dấu {tenThamSo} 1 ngoặc) tới ngay trước đúng vị trí tham số đang xét -
     * xử lý được tham số ở bất kỳ vị trí nào, không chỉ cuối path. Vd "/owner/{ownerId}/pet/{petId}",
     * paramName "petId" -> "/owner/{ownerId}/pet"; paramName "ownerId" -> "/owner".
     */
    private String cutPathBeforeParam(String endpointPath, String paramName) {
        String[] segments = endpointPath.split("/");
        String target = "{" + paramName + "}";
        int paramIndex = -1;
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].equals(target)) {
                paramIndex = i;
                break;
            }
        }
        if (paramIndex < 0) {
            return null;
        }
        return String.join("/", Arrays.copyOfRange(segments, 0, paramIndex));
    }
}
