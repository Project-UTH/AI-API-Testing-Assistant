package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.response.AiUsageResponse;
import com.aiapitesting.backend.dto.response.BugDashboardSummaryResponse;
import com.aiapitesting.backend.dto.response.BugReportPageResponse;
import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.dto.response.ProjectResponse;
import com.aiapitesting.backend.dto.response.TestCaseResponse;
import com.aiapitesting.backend.dto.response.TestResultHistoryItemResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseSource;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.exception.ProjectNotFoundException;
import com.aiapitesting.backend.exception.UserNotFoundException;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.ProjectRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserDataServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private EndpointRepository endpointRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private BugReportService bugReportService;

    @Mock
    private AiUsageService aiUsageService;

    @InjectMocks
    private AdminUserDataService adminUserDataService;

    private User targetUser;
    private Project project;

    @BeforeEach
    void setUp() {
        targetUser = User.builder().id(UUID.randomUUID()).email("target@test.com").build();
        project = Project.builder().id(UUID.randomUUID()).name("Shop API").owner(targetUser).build();
    }

    @Test
    void listProjects_userNotFound_throwsUserNotFound() {
        UUID missingUserId = UUID.randomUUID();
        when(userRepository.findById(missingUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminUserDataService.listProjects(missingUserId, PageRequest.of(0, 20)))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void listProjects_scopedToTargetUserNotCurrentAdmin() {
        Pageable pageable = PageRequest.of(0, 20);
        when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));
        when(projectRepository.findAllByOwner(targetUser, pageable))
                .thenReturn(new PageImpl<>(List.of(project), pageable, 1));

        PageResponse<ProjectResponse> result = adminUserDataService.listProjects(targetUser.getId(), pageable);

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).id()).isEqualTo(project.getId());
    }

    @Test
    void getProject_notOwnedByTargetUser_throwsProjectNotFound() {
        UUID otherProjectId = UUID.randomUUID();
        when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));
        when(projectRepository.findByIdAndOwner(otherProjectId, targetUser)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminUserDataService.getProject(targetUser.getId(), otherProjectId))
                .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void listTestCases_mapsEndpointInfoFromEntity() {
        Endpoint endpoint = Endpoint.builder().id(UUID.randomUUID()).project(project)
                .path("/api/products").method("GET").build();
        TestCase testCase = TestCase.builder().id(UUID.randomUUID()).endpoint(endpoint)
                .name("Positive case").source(TestCaseSource.AI_GENERATED).expectedStatus(200).build();

        when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));
        when(projectRepository.findByIdAndOwner(project.getId(), targetUser)).thenReturn(Optional.of(project));
        when(testCaseRepository.findAllByEndpointProject(project)).thenReturn(List.of(testCase));

        List<TestCaseResponse> result = adminUserDataService.listTestCases(targetUser.getId(), project.getId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).endpointPath()).isEqualTo("/api/products");
        assertThat(result.get(0).name()).isEqualTo("Positive case");
    }

    @Test
    void getBugReports_delegatesToBugReportServiceWithProjectResolvedByTargetUser() {
        BugReportPageResponse expected = new BugReportPageResponse(
                new BugDashboardSummaryResponse(Map.of(), List.of(), 0, 0, 0), List.of());
        when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));
        when(projectRepository.findByIdAndOwner(project.getId(), targetUser)).thenReturn(Optional.of(project));
        when(bugReportService.getBugReportsForProject(project)).thenReturn(expected);

        BugReportPageResponse result = adminUserDataService.getBugReports(targetUser.getId(), project.getId());

        assertThat(result).isSameAs(expected);
    }

    @Test
    void getBugReports_projectNotOwnedByTargetUser_throwsProjectNotFound_neverCallsBugReportService() {
        UUID otherProjectId = UUID.randomUUID();
        when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));
        when(projectRepository.findByIdAndOwner(otherProjectId, targetUser)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminUserDataService.getBugReports(targetUser.getId(), otherProjectId))
                .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void getRunHistory_delegatesToBugReportServiceWithProjectResolvedByTargetUser() {
        UUID testCaseId = UUID.randomUUID();
        List<TestResultHistoryItemResponse> expected = List.of();
        when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));
        when(projectRepository.findByIdAndOwner(project.getId(), targetUser)).thenReturn(Optional.of(project));
        when(bugReportService.getRunHistoryForProject(project, testCaseId)).thenReturn(expected);

        List<TestResultHistoryItemResponse> result =
                adminUserDataService.getRunHistory(targetUser.getId(), project.getId(), testCaseId);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void getAiUsage_delegatesToAiUsageServiceWithResolvedTargetUser() {
        AiUsageResponse expected = new AiUsageResponse(List.of());
        when(userRepository.findById(targetUser.getId())).thenReturn(Optional.of(targetUser));
        when(aiUsageService.getUsageForOwner(targetUser)).thenReturn(expected);

        AiUsageResponse result = adminUserDataService.getAiUsage(targetUser.getId());

        assertThat(result).isSameAs(expected);
    }
}
