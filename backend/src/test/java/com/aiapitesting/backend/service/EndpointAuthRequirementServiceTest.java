package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.request.BulkRequiresAuthRequest;
import com.aiapitesting.backend.dto.response.BulkUpdateResponse;
import com.aiapitesting.backend.dto.response.EndpointResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.exception.EndpointNotFoundException;
import com.aiapitesting.backend.repository.EndpointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EndpointAuthRequirementServiceTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private EndpointRepository endpointRepository;

    @InjectMocks
    private EndpointAuthRequirementService service;

    private UUID projectId;
    private Project project;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        project = Project.builder().id(projectId).build();
        lenient().when(projectService.getOwnedProject(projectId)).thenReturn(project);
    }

    private Endpoint endpoint(String method, String path, boolean fromSpec) {
        return Endpoint.builder().id(UUID.randomUUID()).project(project).method(method).path(path).requiresAuth(fromSpec).build();
    }

    @Test
    void effectiveRequiresAuth_manualOverridesSpec_nullFallsBackToSpec() {
        Endpoint e = endpoint("GET", "/api/hr/ping", false);
        assertThat(e.effectiveRequiresAuth()).isFalse();

        e.setRequiresAuthManual(true);
        assertThat(e.effectiveRequiresAuth()).isTrue();

        e.setRequiresAuthManual(false);
        Endpoint specSaysAuth = endpoint("GET", "/me", true);
        specSaysAuth.setRequiresAuthManual(false);
        assertThat(specSaysAuth.effectiveRequiresAuth()).isFalse();

        specSaysAuth.setRequiresAuthManual(null);
        assertThat(specSaysAuth.effectiveRequiresAuth()).isTrue();
    }

    @Test
    void setRequiresAuth_savesManualValue_andResponseSaysSourceIsManual() {
        Endpoint e = endpoint("GET", "/api/hr/ping", false);
        when(endpointRepository.findByIdAndProject(e.getId(), project)).thenReturn(Optional.of(e));
        when(endpointRepository.save(any(Endpoint.class))).thenAnswer(inv -> inv.getArgument(0));

        EndpointResponse response = service.setRequiresAuth(projectId, e.getId(), true);

        assertThat(e.getRequiresAuthManual()).isTrue();
        assertThat(response.requiresAuth()).isTrue();
        assertThat(response.requiresAuthSource()).isEqualTo("MANUAL");
    }

    @Test
    void setRequiresAuth_null_clearsManualValue_backToSpec() {
        Endpoint e = endpoint("GET", "/api/hr/ping", false);
        e.setRequiresAuthManual(true);
        when(endpointRepository.findByIdAndProject(e.getId(), project)).thenReturn(Optional.of(e));
        when(endpointRepository.save(any(Endpoint.class))).thenAnswer(inv -> inv.getArgument(0));

        EndpointResponse response = service.setRequiresAuth(projectId, e.getId(), null);

        assertThat(e.getRequiresAuthManual()).isNull();
        assertThat(response.requiresAuth()).isFalse();
        assertThat(response.requiresAuthSource()).isEqualTo("SPEC");
    }

    @Test
    void setRequiresAuth_unknownEndpoint_notFound() {
        UUID missing = UUID.randomUUID();
        when(endpointRepository.findByIdAndProject(missing, project)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setRequiresAuth(projectId, missing, true)).isInstanceOf(EndpointNotFoundException.class);
    }

    @Test
    void bulk_noCondition_appliesToEveryEndpointOfProject() {
        Endpoint a = endpoint("GET", "/api/hr/jobs", false);
        Endpoint b = endpoint("POST", "/api/public/jobs", false);
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(a, b));

        BulkUpdateResponse response = service.bulkSetRequiresAuth(projectId, new BulkRequiresAuthRequest(null, null, null, true));

        assertThat(response.updated()).isEqualTo(2);
        assertThat(a.getRequiresAuthManual()).isTrue();
        assertThat(b.getRequiresAuthManual()).isTrue();
    }

    @Test
    void bulk_pathPrefixAndMethod_mustBothMatch() {
        Endpoint hrGet = endpoint("GET", "/api/hr/jobs", false);
        Endpoint hrPost = endpoint("POST", "/api/hr/jobs", false);
        Endpoint publicGet = endpoint("GET", "/api/public/jobs", false);
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(hrGet, hrPost, publicGet));

        BulkUpdateResponse response = service.bulkSetRequiresAuth(projectId,
                new BulkRequiresAuthRequest(null, "/api/hr/", "get", true));

        assertThat(response.updated()).isEqualTo(1);
        assertThat(hrGet.getRequiresAuthManual()).isTrue();
        assertThat(hrPost.getRequiresAuthManual()).isNull();
        assertThat(publicGet.getRequiresAuthManual()).isNull();
    }

    @Test
    void bulk_explicitIds_onlyThoseEndpoints_andNullValueClearsManual() {
        Endpoint a = endpoint("GET", "/a", false);
        Endpoint b = endpoint("GET", "/b", false);
        a.setRequiresAuthManual(true);
        b.setRequiresAuthManual(true);
        when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(a, b));

        BulkUpdateResponse response = service.bulkSetRequiresAuth(projectId,
                new BulkRequiresAuthRequest(List.of(a.getId()), null, null, null));

        assertThat(response.updated()).isEqualTo(1);
        assertThat(a.getRequiresAuthManual()).isNull();
        assertThat(b.getRequiresAuthManual()).isTrue();
    }
}
