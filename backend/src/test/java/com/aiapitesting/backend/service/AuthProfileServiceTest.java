package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.request.AuthProfileRequest;
import com.aiapitesting.backend.dto.response.AuthProfileResponse;
import com.aiapitesting.backend.entity.AuthProfile;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TargetAuthType;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.repository.AuthProfileRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.security.AesEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthProfileServiceTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private AuthProfileRepository authProfileRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private com.aiapitesting.backend.repository.EndpointRoleRepository endpointRoleRepository;

    private final AesEncryptionService aes = new AesEncryptionService(Base64.getEncoder().encodeToString(new byte[32]));
    private AuthProfileService service;
    private UUID projectId;
    private Project project;

    @BeforeEach
    void setUp() {
        service = new AuthProfileService(projectService, authProfileRepository, testCaseRepository, endpointRoleRepository, aes);
        projectId = UUID.randomUUID();
        project = Project.builder().id(projectId).build();
        lenient().when(projectService.getOwnedProject(projectId)).thenReturn(project);
        lenient().doAnswer(inv -> {
            TargetAuthType type = inv.getArgument(0);
            String value = inv.getArgument(1);
            if (type != null && type != TargetAuthType.NONE && (value == null || value.isBlank())) {
                throw new InvalidRequestException("Thiếu giá trị xác thực cho loại xác thực đã chọn");
            }
            return null;
        }).when(projectService).validateTargetAuthValue(any(), any());
        lenient().when(authProfileRepository.save(any(AuthProfile.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void create_encryptsTokenAndDerivesRoleKeyFromName_responseNeverCarriesTheToken() {
        AuthProfileResponse response = service.create(projectId,
                new AuthProfileRequest("Nhà tuyển dụng (HR)", null, TargetAuthType.BEARER_TOKEN, "hr-secret"));

        ArgumentCaptor<AuthProfile> saved = ArgumentCaptor.forClass(AuthProfile.class);
        verify(authProfileRepository).save(saved.capture());
        assertThat(saved.getValue().getRoleKey()).isEqualTo("NHA_TUYEN_DUNG_HR");
        assertThat(saved.getValue().getAuthValueEncrypted()).isNotEqualTo("hr-secret");
        assertThat(aes.decrypt(saved.getValue().getAuthValueEncrypted())).isEqualTo("hr-secret");
        assertThat(response.hasValue()).isTrue();
        assertThat(response.toString()).doesNotContain("hr-secret").doesNotContain(saved.getValue().getAuthValueEncrypted());
    }

    @Test
    void create_explicitRoleKeyIsNormalised() {
        service.create(projectId, new AuthProfileRequest("Admin", "admin role", TargetAuthType.API_KEY, "k"));

        ArgumentCaptor<AuthProfile> saved = ArgumentCaptor.forClass(AuthProfile.class);
        verify(authProfileRepository).save(saved.capture());
        assertThat(saved.getValue().getRoleKey()).isEqualTo("ADMIN_ROLE");
    }

    @Test
    void create_duplicateRoleKey_rejected() {
        when(authProfileRepository.existsByProjectAndRoleKey(project, "HR")).thenReturn(true);

        assertThatThrownBy(() -> service.create(projectId, new AuthProfileRequest("HR", null, TargetAuthType.BEARER_TOKEN, "t")))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("HR");
        verify(authProfileRepository, never()).save(any());
    }

    @Test
    void create_reservedDefaultKey_rejected() {
        assertThatThrownBy(() -> service.create(projectId, new AuthProfileRequest("Default", null, TargetAuthType.BEARER_TOKEN, "t")))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("DEFAULT");
    }

    @Test
    void create_bearerWithoutToken_rejected_butNoneTypeNeedsNoToken() {
        assertThatThrownBy(() -> service.create(projectId, new AuthProfileRequest("HR", null, TargetAuthType.BEARER_TOKEN, " ")))
                .isInstanceOf(InvalidRequestException.class);

        AuthProfileResponse anonymous = service.create(projectId, new AuthProfileRequest("Anonymous", null, TargetAuthType.NONE, null));
        assertThat(anonymous.hasValue()).isFalse();
        assertThat(anonymous.roleKey()).isEqualTo("ANONYMOUS");
    }

    @Test
    void create_nameThatNormalisesToNothing_rejected() {
        assertThatThrownBy(() -> service.create(projectId, new AuthProfileRequest("???", null, TargetAuthType.NONE, null)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void update_withoutNewToken_keepsOldToken_andRoleKeyStaysTheSame() {
        UUID id = UUID.randomUUID();
        String encrypted = aes.encrypt("old-token");
        AuthProfile existing = AuthProfile.builder().id(id).project(project).name("HR").roleKey("HR")
                .authType(TargetAuthType.BEARER_TOKEN).authValueEncrypted(encrypted).build();
        when(authProfileRepository.findByIdAndProject(id, project)).thenReturn(Optional.of(existing));

        service.update(projectId, id, new AuthProfileRequest("Nhà tuyển dụng", "OTHER", TargetAuthType.BEARER_TOKEN, null));

        assertThat(existing.getName()).isEqualTo("Nhà tuyển dụng");
        assertThat(existing.getRoleKey()).isEqualTo("HR");
        assertThat(existing.getAuthValueEncrypted()).isEqualTo(encrypted);
    }

    @Test
    void update_withNewToken_replacesEncryptedValue() {
        UUID id = UUID.randomUUID();
        AuthProfile existing = AuthProfile.builder().id(id).project(project).name("HR").roleKey("HR")
                .authType(TargetAuthType.BEARER_TOKEN).authValueEncrypted(aes.encrypt("old")).build();
        when(authProfileRepository.findByIdAndProject(id, project)).thenReturn(Optional.of(existing));

        service.update(projectId, id, new AuthProfileRequest("HR", null, TargetAuthType.BEARER_TOKEN, "new-token"));

        assertThat(aes.decrypt(existing.getAuthValueEncrypted())).isEqualTo("new-token");
        assertThat(existing.getAuthUpdatedAt()).isNotNull();
    }

    @Test
    void update_toNoneType_clearsToken() {
        UUID id = UUID.randomUUID();
        AuthProfile existing = AuthProfile.builder().id(id).project(project).name("HR").roleKey("HR")
                .authType(TargetAuthType.BEARER_TOKEN).authValueEncrypted(aes.encrypt("old")).build();
        when(authProfileRepository.findByIdAndProject(id, project)).thenReturn(Optional.of(existing));

        service.update(projectId, id, new AuthProfileRequest("HR", null, TargetAuthType.NONE, null));

        assertThat(existing.getAuthType()).isEqualTo(TargetAuthType.NONE);
        assertThat(existing.getAuthValueEncrypted()).isNull();
    }

    @Test
    void delete_profileStillUsedByTestCases_blockedInsteadOfSilentlySwitchingRole() {
        UUID id = UUID.randomUUID();
        AuthProfile existing = AuthProfile.builder().id(id).project(project).name("HR").roleKey("HR").authType(TargetAuthType.NONE).build();
        when(authProfileRepository.findByIdAndProject(id, project)).thenReturn(Optional.of(existing));
        when(testCaseRepository.countByAuthProfileId(id)).thenReturn(4L);

        assertThatThrownBy(() -> service.delete(projectId, id))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("4 test case");
        verify(authProfileRepository, never()).delete(any());
    }

    @Test
    void delete_unusedProfile_deleted() {
        UUID id = UUID.randomUUID();
        AuthProfile existing = AuthProfile.builder().id(id).project(project).name("HR").roleKey("HR").authType(TargetAuthType.NONE).build();
        when(authProfileRepository.findByIdAndProject(id, project)).thenReturn(Optional.of(existing));
        when(testCaseRepository.countByAuthProfileId(id)).thenReturn(0L);

        service.delete(projectId, id);

        verify(authProfileRepository).delete(existing);
    }

    @Test
    void list_includesHowManyTestCasesUseEachProfile() {
        AuthProfile p = AuthProfile.builder().id(UUID.randomUUID()).project(project).name("HR").roleKey("HR")
                .authType(TargetAuthType.BEARER_TOKEN).authValueEncrypted(aes.encrypt("t")).build();
        when(authProfileRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(List.of(p));
        when(testCaseRepository.countByAuthProfileId(p.getId())).thenReturn(7L);

        List<AuthProfileResponse> result = service.list(projectId);

        assertThat(result).singleElement().satisfies(r -> {
            assertThat(r.testCaseCount()).isEqualTo(7);
            assertThat(r.hasValue()).isTrue();
        });
    }
}
