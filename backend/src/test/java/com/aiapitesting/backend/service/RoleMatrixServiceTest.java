package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.request.RoleRulesRequest;
import com.aiapitesting.backend.dto.request.RoleRulesRequest.RuleInput;
import com.aiapitesting.backend.dto.response.BulkUpdateResponse;
import com.aiapitesting.backend.dto.response.RoleRulesPreviewResponse;
import com.aiapitesting.backend.dto.response.RoleMatrixResponse;
import com.aiapitesting.backend.entity.AuthProfile;
import com.aiapitesting.backend.entity.AuthzExpectation;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.EndpointRole;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TargetAuthType;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.repository.AuthProfileRepository;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.EndpointRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleMatrixServiceTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private EndpointRepository endpointRepository;

    @Mock
    private AuthProfileRepository authProfileRepository;

    @Mock
    private EndpointRoleRepository endpointRoleRepository;

    @InjectMocks
    private RoleMatrixService service;

    private UUID projectId;
    private Project project;
    private AuthProfile hr;
    private AuthProfile candidate;
    private Endpoint hrJobs;
    private Endpoint hrJobsPost;
    private Endpoint publicJobs;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        project = Project.builder().id(projectId).build();
        lenient().when(projectService.getOwnedProject(projectId)).thenReturn(project);
        hr = AuthProfile.builder().id(UUID.randomUUID()).project(project).name("HR").roleKey("HR").authType(TargetAuthType.BEARER_TOKEN).build();
        candidate = AuthProfile.builder().id(UUID.randomUUID()).project(project).name("Ứng viên").roleKey("CANDIDATE")
                .authType(TargetAuthType.BEARER_TOKEN).build();
        lenient().when(authProfileRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(hr, candidate));
        hrJobs = Endpoint.builder().id(UUID.randomUUID()).project(project).method("GET").path("/api/hr/jobs").build();
        hrJobsPost = Endpoint.builder().id(UUID.randomUUID()).project(project).method("POST").path("/api/hr/jobs").build();
        publicJobs = Endpoint.builder().id(UUID.randomUUID()).project(project).method("GET").path("/api/public/jobs").build();
        lenient().when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(hrJobs, hrJobsPost, publicJobs));
        lenient().when(endpointRoleRepository.findAllByEndpointProject(project)).thenReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    private List<EndpointRole> savedRoles() {
        ArgumentCaptor<List<EndpointRole>> captor = ArgumentCaptor.forClass(List.class);
        verify(endpointRoleRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    void update_prefixAndMethod_setsOnlyMatchingCells() {
        BulkUpdateResponse response = service.update(projectId, new RoleRulesRequest(List.of(
                new RuleInput(null, "/api/hr/", "GET", candidate.getId(), AuthzExpectation.DENY))));

        assertThat(response.updated()).isEqualTo(1);
        assertThat(savedRoles()).singleElement().satisfies(r -> {
            assertThat(r.getEndpoint()).isSameAs(hrJobs);
            assertThat(r.getAuthProfileId()).isEqualTo(candidate.getId());
            assertThat(r.getExpectation()).isEqualTo(AuthzExpectation.DENY);
        });
    }

    @Test
    void update_laterRuleOverridesEarlierOneForTheSameCell() {
        service.update(projectId, new RoleRulesRequest(List.of(
                new RuleInput(null, "/api/hr/", null, candidate.getId(), AuthzExpectation.DENY),
                new RuleInput(List.of(hrJobsPost.getId()), null, null, candidate.getId(), AuthzExpectation.ALLOW))));

        List<EndpointRole> saved = savedRoles();
        assertThat(saved).hasSize(2);
        assertThat(saved).filteredOn(r -> r.getEndpoint() == hrJobsPost).singleElement()
                .satisfies(r -> assertThat(r.getExpectation()).isEqualTo(AuthzExpectation.ALLOW));
        assertThat(saved).filteredOn(r -> r.getEndpoint() == hrJobs).singleElement()
                .satisfies(r -> assertThat(r.getExpectation()).isEqualTo(AuthzExpectation.DENY));
    }

    @Test
    void update_existingCellWithOtherExpectation_isChangedNotDuplicated() {
        EndpointRole existing = EndpointRole.builder().id(UUID.randomUUID()).endpoint(publicJobs).authProfileId(hr.getId())
                .expectation(AuthzExpectation.DENY).build();
        when(endpointRoleRepository.findAllByEndpointProject(project)).thenReturn(List.of(existing));

        service.update(projectId, new RoleRulesRequest(List.of(
                new RuleInput(List.of(publicJobs.getId()), null, null, hr.getId(), AuthzExpectation.ALLOW))));

        assertThat(savedRoles()).containsExactly(existing);
        assertThat(existing.getExpectation()).isEqualTo(AuthzExpectation.ALLOW);
    }

    @Test
    void update_nullExpectation_clearsExistingCells() {
        EndpointRole existing = EndpointRole.builder().id(UUID.randomUUID()).endpoint(publicJobs).authProfileId(hr.getId())
                .expectation(AuthzExpectation.DENY).build();
        when(endpointRoleRepository.findAllByEndpointProject(project)).thenReturn(List.of(existing));

        BulkUpdateResponse response = service.update(projectId, new RoleRulesRequest(List.of(
                new RuleInput(null, "/api/public/", null, hr.getId(), null))));

        assertThat(response.updated()).isEqualTo(1);
        verify(endpointRoleRepository).deleteAll(List.of(existing));
    }

    @Test
    void update_profileFromAnotherProject_rejected() {
        assertThatThrownBy(() -> service.update(projectId, new RoleRulesRequest(List.of(
                new RuleInput(null, null, null, UUID.randomUUID(), AuthzExpectation.ALLOW)))))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void update_emptyRules_rejected() {
        assertThatThrownBy(() -> service.update(projectId, new RoleRulesRequest(List.of()))).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void get_listsEveryEndpointWithItsDeclaredRulesByProfile() {
        EndpointRole role = EndpointRole.builder().id(UUID.randomUUID()).endpoint(hrJobs).authProfileId(candidate.getId())
                .expectation(AuthzExpectation.DENY).build();
        when(endpointRoleRepository.findAllByEndpointProject(project)).thenReturn(List.of(role));

        RoleMatrixResponse response = service.get(projectId);

        assertThat(response.profiles()).extracting(RoleMatrixResponse.ProfileRef::roleKey).containsExactly("HR", "CANDIDATE");
        assertThat(response.endpoints()).hasSize(3);
        assertThat(response.endpoints()).filteredOn(r -> r.endpointId().equals(hrJobs.getId())).singleElement()
                .satisfies(r -> assertThat(r.rules()).containsEntry(candidate.getId(), AuthzExpectation.DENY));
        assertThat(response.endpoints()).filteredOn(r -> r.endpointId().equals(publicJobs.getId())).singleElement()
                .satisfies(r -> assertThat(r.rules()).isEmpty());
    }

    // ---- Module 17 (S5) - xem trước quy tắc ----

    @Test
    void preview_reportsMatchedEndpointsSamplesAndWritesNothing() {
        RoleRulesPreviewResponse preview = service.preview(projectId, new RoleRulesRequest(List.of(
                new RuleInput(null, "/api/hr/", null, candidate.getId(), AuthzExpectation.DENY),
                new RuleInput(null, "/api/nothing/", null, hr.getId(), AuthzExpectation.ALLOW))));

        assertThat(preview.rules()).hasSize(2);
        RoleRulesPreviewResponse.RuleReport first = preview.rules().get(0);
        assertThat(first.ruleIndex()).isEqualTo(1);
        assertThat(first.profileName()).isEqualTo("Ứng viên");
        assertThat(first.matchedEndpoints()).isEqualTo(2);
        assertThat(first.samplePaths()).containsExactly("GET /api/hr/jobs", "POST /api/hr/jobs");
        assertThat(preview.rules().get(1).matchedNothing()).isTrue();
        assertThat(preview.cellsToCreate()).isEqualTo(2);
        assertThat(preview.totalCells()).isEqualTo(2);
        verify(endpointRoleRepository, never()).saveAll(any());
        verify(endpointRoleRepository, never()).deleteAll(any());
    }

    @Test
    void preview_laterRuleOverridingEarlierOne_andExistingCell_areCountedAsOverrides() {
        EndpointRole existing = EndpointRole.builder().id(UUID.randomUUID()).endpoint(publicJobs).authProfileId(hr.getId())
                .expectation(AuthzExpectation.DENY).build();
        when(endpointRoleRepository.findAllByEndpointProject(project)).thenReturn(List.of(existing));

        RoleRulesPreviewResponse preview = service.preview(projectId, new RoleRulesRequest(List.of(
                new RuleInput(null, "/api/hr/", null, candidate.getId(), AuthzExpectation.DENY),
                new RuleInput(List.of(hrJobsPost.getId()), null, null, candidate.getId(), AuthzExpectation.ALLOW),
                new RuleInput(List.of(publicJobs.getId()), null, null, hr.getId(), AuthzExpectation.ALLOW))));

        assertThat(preview.rules().get(1).overridesEarlier()).isEqualTo(1);
        assertThat(preview.rules().get(1).overridesExisting()).isZero();
        assertThat(preview.rules().get(2).overridesExisting()).isEqualTo(1);
        assertThat(preview.cellsToCreate()).isEqualTo(2);
        assertThat(preview.cellsToChange()).isEqualTo(1);
        assertThat(existing.getExpectation()).isEqualTo(AuthzExpectation.DENY); // preview không sửa entity
    }

    @Test
    void preview_totalEqualsWhatUpdateWrites_includingDeletesAndNoOpRules() {
        EndpointRole existing = EndpointRole.builder().id(UUID.randomUUID()).endpoint(publicJobs).authProfileId(hr.getId())
                .expectation(AuthzExpectation.ALLOW).build();
        when(endpointRoleRepository.findAllByEndpointProject(project)).thenReturn(List.of(existing));
        RoleRulesRequest request = new RoleRulesRequest(List.of(
                new RuleInput(null, "/api/hr/", null, candidate.getId(), AuthzExpectation.DENY),
                new RuleInput(List.of(publicJobs.getId()), null, null, hr.getId(), AuthzExpectation.ALLOW), // trùng giá trị đã lưu: không ghi
                new RuleInput(List.of(hrJobs.getId()), null, null, hr.getId(), null)));                    // xoá ô chưa khai: không ghi

        int previewTotal = service.preview(projectId, request).totalCells();
        BulkUpdateResponse saved = service.update(projectId, request);

        assertThat(previewTotal).isEqualTo(2);
        assertThat(saved.updated()).isEqualTo(previewTotal);
    }
}
