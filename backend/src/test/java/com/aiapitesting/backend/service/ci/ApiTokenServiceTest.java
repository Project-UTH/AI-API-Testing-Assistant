package com.aiapitesting.backend.service.ci;

import com.aiapitesting.backend.dto.request.CreateApiTokenRequest;
import com.aiapitesting.backend.dto.response.ApiTokenResponse;
import com.aiapitesting.backend.dto.response.CreatedApiTokenResponse;
import com.aiapitesting.backend.entity.ApiToken;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.repository.ApiTokenRepository;
import com.aiapitesting.backend.security.CiPrincipal;
import com.aiapitesting.backend.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
class ApiTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");

    @Mock
    private ProjectService projectService;

    @Mock
    private ApiTokenRepository apiTokenRepository;

    private ApiTokenService service;
    private Project project;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        service = new ApiTokenService(projectService, apiTokenRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        projectId = UUID.randomUUID();
        project = Project.builder().id(projectId).build();
        lenient().when(projectService.getOwnedProject(projectId)).thenReturn(project);
        lenient().when(apiTokenRepository.save(any(ApiToken.class))).thenAnswer(inv -> {
            ApiToken t = inv.getArgument(0);
            if (t.getId() == null) {
                t.setId(UUID.randomUUID());
            }
            if (t.getCreatedAt() == null) {
                t.setCreatedAt(NOW);
            }
            return t;
        });
    }

    private CreatedApiTokenResponse create(Integer days) {
        return service.create(projectId, new CreateApiTokenRequest("GitHub Actions", days));
    }

    @Test
    void create_storesOnlyTheHash_returnsRawTokenOnce_withPrefixAndExpiry() {
        CreatedApiTokenResponse created = create(30);

        ArgumentCaptor<ApiToken> saved = ArgumentCaptor.forClass(ApiToken.class);
        verify(apiTokenRepository).save(saved.capture());
        assertThat(created.value()).startsWith("ci_").hasSizeGreaterThan(40);
        assertThat(saved.getValue().getTokenHash()).hasSize(64).isEqualTo(ApiTokenService.hash(created.value()));
        assertThat(saved.getValue().getTokenHash()).doesNotContain(created.value());
        assertThat(created.token().prefix()).isEqualTo(created.value().substring(0, 9));
        assertThat(created.token().expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(created.token().status()).isEqualTo("ACTIVE");
        // Thông tin trả về khi LIỆT KÊ không bao giờ chứa token hay hash.
        assertThat(created.token().toString()).doesNotContain(created.value()).doesNotContain(saved.getValue().getTokenHash());
    }

    @Test
    void create_twoTokensAreDifferent_andNoExpiryWhenNotAsked() {
        CreatedApiTokenResponse a = create(null);
        CreatedApiTokenResponse b = create(null);

        assertThat(a.value()).isNotEqualTo(b.value());
        assertThat(a.token().expiresAt()).isNull();
    }

    @Test
    void create_limitsActiveTokensPerProject() {
        when(apiTokenRepository.countActive(project, NOW)).thenReturn((long) ApiTokenService.MAX_ACTIVE_TOKENS_PER_PROJECT);

        assertThatThrownBy(() -> create(null)).isInstanceOf(InvalidRequestException.class).hasMessageContaining("10 token");
        verify(apiTokenRepository, never()).save(any());
    }

    private ApiToken storedFor(String raw, Instant expiresAt, Instant revokedAt, Instant lastUsedAt) {
        return ApiToken.builder().id(UUID.randomUUID()).project(project).name("x").prefix("ci_abc").tokenHash(ApiTokenService.hash(raw))
                .expiresAt(expiresAt).revokedAt(revokedAt).lastUsedAt(lastUsedAt).createdAt(NOW).build();
    }

    @Test
    void authenticate_validToken_returnsPrincipalOfItsProjectOnly_andRecordsLastUsed() {
        String raw = "ci_validtoken0123456789";
        ApiToken token = storedFor(raw, null, null, null);
        when(apiTokenRepository.findByTokenHashWithProject(ApiTokenService.hash(raw))).thenReturn(Optional.of(token));

        Optional<CiPrincipal> principal = service.authenticate(raw);

        assertThat(principal).get().satisfies(p -> {
            assertThat(p.projectId()).isEqualTo(projectId);
            assertThat(p.tokenId()).isEqualTo(token.getId());
        });
        assertThat(token.getLastUsedAt()).isEqualTo(NOW);
    }

    @Test
    void authenticate_recentlyUsedToken_doesNotWriteToDbAgain() {
        String raw = "ci_validtoken0123456789";
        ApiToken token = storedFor(raw, null, null, NOW.minusSeconds(10));
        when(apiTokenRepository.findByTokenHashWithProject(ApiTokenService.hash(raw))).thenReturn(Optional.of(token));

        assertThat(service.authenticate(raw)).isPresent();

        verify(apiTokenRepository, never()).save(any());
    }

    @Test
    void authenticate_revokedExpiredUnknownOrMalformed_allReturnEmptyWithoutDistinguishing() {
        String revoked = "ci_revoked0123456789";
        String expired = "ci_expired0123456789";
        when(apiTokenRepository.findByTokenHashWithProject(ApiTokenService.hash(revoked)))
                .thenReturn(Optional.of(storedFor(revoked, null, NOW.minusSeconds(5), null)));
        when(apiTokenRepository.findByTokenHashWithProject(ApiTokenService.hash(expired)))
                .thenReturn(Optional.of(storedFor(expired, NOW.minusSeconds(1), null, null)));
        when(apiTokenRepository.findByTokenHashWithProject(ApiTokenService.hash("ci_unknown0123456789"))).thenReturn(Optional.empty());

        assertThat(service.authenticate(revoked)).isEmpty();
        assertThat(service.authenticate(expired)).isEmpty();
        assertThat(service.authenticate("ci_unknown0123456789")).isEmpty();
        assertThat(service.authenticate("eyJhbGciOiJIUzI1NiJ9.jwt.like")).isEmpty(); // không phải định dạng token CI (JWT không dùng được ở đây)
        assertThat(service.authenticate("")).isEmpty();
        assertThat(service.authenticate(null)).isEmpty();
        assertThat(service.authenticate("ci_" + "x".repeat(500))).isEmpty(); // quá dài: bỏ qua trước khi tra DB
    }

    @Test
    void revoke_setsRevokedAt_andIsIdempotent() {
        String raw = "ci_tokentorevoke01234";
        ApiToken token = storedFor(raw, null, null, null);
        when(apiTokenRepository.findByIdAndProject(token.getId(), project)).thenReturn(Optional.of(token));

        ApiTokenResponse first = service.revoke(projectId, token.getId());
        ApiTokenResponse second = service.revoke(projectId, token.getId());

        assertThat(first.status()).isEqualTo("REVOKED");
        assertThat(second.status()).isEqualTo("REVOKED");
        assertThat(token.getRevokedAt()).isEqualTo(NOW);
        verify(apiTokenRepository).save(token); // chỉ lưu ở lần đầu
    }

    @Test
    void revoke_tokenOfAnotherProject_isRejected() {
        UUID other = UUID.randomUUID();
        when(apiTokenRepository.findByIdAndProject(other, project)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(projectId, other)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void status_expiredIsReportedAsExpired() {
        ApiToken token = storedFor("ci_x0123456789012345", NOW.minusSeconds(1), null, null);

        assertThat(ApiTokenResponse.from(token, NOW).status()).isEqualTo("EXPIRED");
    }
}
