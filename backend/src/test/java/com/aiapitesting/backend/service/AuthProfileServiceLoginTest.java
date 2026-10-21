package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.request.AuthProfileRequest;
import com.aiapitesting.backend.dto.response.AuthProfileResponse;
import com.aiapitesting.backend.entity.AuthProfile;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TargetAuthType;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.repository.AuthProfileRepository;
import com.aiapitesting.backend.repository.EndpointRoleRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.security.AesEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Module 17 (G5, P1-4) - cấu hình profile chế độ "Đăng nhập". */
@ExtendWith(MockitoExtension.class)
class AuthProfileServiceLoginTest {

    private static final String BODY = "{\"email\":\"hr@x.com\",\"password\":\"Secret123!\"}";

    @Mock
    private ProjectService projectService;
    @Mock
    private AuthProfileRepository authProfileRepository;
    @Mock
    private TestCaseRepository testCaseRepository;
    @Mock
    private EndpointRoleRepository endpointRoleRepository;

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
        lenient().when(authProfileRepository.save(any(AuthProfile.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static AuthProfileRequest login(String path, String body, String jsonPath) {
        return new AuthProfileRequest("HR", null, TargetAuthType.BEARER_TOKEN, null, path, body, jsonPath);
    }

    @Test
    void create_loginMode_encryptsBodyAndResponseNeverCarriesCredentials() {
        AuthProfileResponse response = service.create(projectId, login("/auth/login", BODY, "data.accessToken"));

        ArgumentCaptor<AuthProfile> saved = ArgumentCaptor.forClass(AuthProfile.class);
        verify(authProfileRepository).save(saved.capture());
        AuthProfile profile = saved.getValue();
        assertThat(profile.isLoginMode()).isTrue();
        assertThat(profile.getAuthValueEncrypted()).isNull();
        assertThat(profile.getLoginBodyEncrypted()).doesNotContain("Secret123");
        assertThat(aes.decrypt(profile.getLoginBodyEncrypted())).isEqualTo(BODY);
        assertThat(response.loginMode()).isTrue();
        assertThat(response.hasValue()).isTrue();
        assertThat(response.toString()).doesNotContain("Secret123").doesNotContain("hr@x.com").doesNotContain(profile.getLoginBodyEncrypted());
    }

    @Test
    void create_loginMode_rejectsAbsoluteUrlsMissingJsonPathAndBadBody() {
        for (String badPath : new String[]{"http://evil.com/login", "//evil.com/x", "auth/login", "/a b"}) {
            assertThatThrownBy(() -> service.create(projectId, login(badPath, BODY, "token"))).isInstanceOf(InvalidRequestException.class);
        }
        assertThatThrownBy(() -> service.create(projectId, login("/auth/login", BODY, " "))).isInstanceOf(InvalidRequestException.class);
        // GPath thực thi được biểu thức Groovy -> chỉ cho đường dẫn đơn giản.
        for (String evil : new String[]{"find{it}", "a.b()", "data['x']", "x y", "a;b"}) {
            assertThatThrownBy(() -> service.create(projectId, login("/auth/login", BODY, evil))).isInstanceOf(InvalidRequestException.class);
        }
        assertThatThrownBy(() -> service.create(projectId, login("/auth/login", "not json", "token"))).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.create(projectId, login("/auth/login", "[1]", "token"))).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.create(projectId, login("/auth/login", null, "token"))).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void update_loginMode_blankBodyKeepsOldBody_andSwitchingToStaticTokenClearsLogin() {
        UUID id = UUID.randomUUID();
        AuthProfile existing = AuthProfile.builder().id(id).project(project).name("HR").roleKey("HR").authType(TargetAuthType.BEARER_TOKEN)
                .loginPath("/auth/login").tokenJsonPath("token").loginBodyEncrypted(aes.encrypt(BODY)).build();
        when(authProfileRepository.findByIdAndProject(id, project)).thenReturn(Optional.of(existing));

        String oldCipher = existing.getLoginBodyEncrypted();
        service.update(projectId, id, login("/v2/login", null, "data.token"));
        assertThat(existing.getLoginPath()).isEqualTo("/v2/login");
        assertThat(existing.getLoginBodyEncrypted()).isEqualTo(oldCipher);

        service.update(projectId, id, new AuthProfileRequest("HR", null, TargetAuthType.BEARER_TOKEN, "static-token"));
        assertThat(existing.isLoginMode()).isFalse();
        assertThat(existing.getLoginBodyEncrypted()).isNull();
        assertThat(aes.decrypt(existing.getAuthValueEncrypted())).isEqualTo("static-token");
    }
}
