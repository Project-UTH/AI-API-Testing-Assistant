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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Module 17 (17d) - quản lý + xác thực token API cho CI. Token = "ci_" + 32 byte ngẫu nhiên (base64url) = 256-bit entropy nên băm
 * SHA-256 không cần muối/bcrypt; tra DB theo hash (unique) rồi so lại bằng {@link MessageDigest#isEqual} (hằng thời gian). Token thô
 * chỉ tồn tại trong phản hồi tạo, không lưu, không log.
 */
@Service
public class ApiTokenService {

    static final String TOKEN_PREFIX = "ci_";
    static final int MAX_ACTIVE_TOKENS_PER_PROJECT = 10;
    private static final int RANDOM_BYTES = 32;
    /** Ghi lastUsedAt tối đa 1 lần / khoảng này cho mỗi token để không ghi DB ở MỌI request CI. */
    private static final Duration LAST_USED_RESOLUTION = Duration.ofMinutes(1);

    private final ProjectService projectService;
    private final ApiTokenRepository apiTokenRepository;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public ApiTokenService(ProjectService projectService, ApiTokenRepository apiTokenRepository) {
        this(projectService, apiTokenRepository, Clock.systemUTC());
    }

    ApiTokenService(ProjectService projectService, ApiTokenRepository apiTokenRepository, Clock clock) {
        this.projectService = projectService;
        this.apiTokenRepository = apiTokenRepository;
        this.clock = clock;
    }

    public List<ApiTokenResponse> list(UUID projectId) {
        Project project = projectService.getOwnedProject(projectId);
        Instant now = clock.instant();
        return apiTokenRepository.findAllByProjectOrderByCreatedAtDesc(project).stream().map(t -> ApiTokenResponse.from(t, now)).toList();
    }

    @Transactional
    public CreatedApiTokenResponse create(UUID projectId, CreateApiTokenRequest request) {
        Project project = projectService.getOwnedProject(projectId);
        Instant now = clock.instant();
        if (apiTokenRepository.countActive(project, now) >= MAX_ACTIVE_TOKENS_PER_PROJECT) {
            throw new InvalidRequestException("Mỗi project tối đa " + MAX_ACTIVE_TOKENS_PER_PROJECT + " token còn hiệu lực - hãy thu hồi token không dùng");
        }
        byte[] random = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(random);
        String raw = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);

        ApiToken token = ApiToken.builder()
                .project(project)
                .name(request.name().trim())
                .prefix(raw.substring(0, Math.min(raw.length(), TOKEN_PREFIX.length() + 6)))
                .tokenHash(hash(raw))
                .expiresAt(request.expiresInDays() == null ? null : now.plus(request.expiresInDays(), ChronoUnit.DAYS))
                .build();
        return new CreatedApiTokenResponse(ApiTokenResponse.from(apiTokenRepository.save(token), now), raw);
    }

    /** Thu hồi (mềm, idempotent): thu hồi lần 2 không lỗi. Token của project khác → 400 như không tồn tại. */
    @Transactional
    public ApiTokenResponse revoke(UUID projectId, UUID tokenId) {
        Project project = projectService.getOwnedProject(projectId);
        ApiToken token = apiTokenRepository.findByIdAndProject(tokenId, project)
                .orElseThrow(() -> new InvalidRequestException("Không tìm thấy token"));
        if (token.getRevokedAt() == null) {
            token.setRevokedAt(clock.instant());
            apiTokenRepository.save(token);
        }
        return ApiTokenResponse.from(token, clock.instant());
    }

    /**
     * Xác thực token thô từ header. Trả rỗng cho MỌI lý do thất bại (sai định dạng/không tồn tại/thu hồi/hết hạn) - không cho phép
     * phân biệt bên ngoài.
     */
    @Transactional
    public Optional<CiPrincipal> authenticate(String rawToken) {
        if (rawToken == null || !rawToken.startsWith(TOKEN_PREFIX) || rawToken.length() > 200) {
            return Optional.empty();
        }
        String hash = hash(rawToken);
        Optional<ApiToken> found = apiTokenRepository.findByTokenHashWithProject(hash);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ApiToken token = found.get();
        if (!MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII), token.getTokenHash().getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (token.getRevokedAt() != null || (token.getExpiresAt() != null && !token.getExpiresAt().isAfter(now))) {
            return Optional.empty();
        }
        if (token.getLastUsedAt() == null || Duration.between(token.getLastUsedAt(), now).compareTo(LAST_USED_RESOLUTION) >= 0) {
            token.setLastUsedAt(now);
            apiTokenRepository.save(token);
        }
        return Optional.of(new CiPrincipal(token.getId(), token.getProject().getId()));
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 luôn có sẵn trong JDK", e);
        }
    }
}
