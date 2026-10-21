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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Module 17 (3.1) - quản lý các profile xác thực (vai trò) của target API trong 1 project. Token mã hoá AES-256 ngay từ đây,
 * không đi qua bất kỳ lớp nào ở dạng rõ, và không bao giờ có trong response/log.
 */
@Service
@RequiredArgsConstructor
public class AuthProfileService {

    /** "DEFAULT" dành cho token mặc định của project (không phải 1 AuthProfile) - tránh nhầm khi lập ma trận phân quyền. */
    static final String RESERVED_ROLE_KEY = "DEFAULT";
    private static final Pattern VALID_ROLE_KEY = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");

    private final ProjectService projectService;
    private final AuthProfileRepository authProfileRepository;
    private final TestCaseRepository testCaseRepository;
    private final EndpointRoleRepository endpointRoleRepository;
    private final AesEncryptionService aesEncryptionService;

    public List<AuthProfileResponse> list(UUID projectId) {
        Project project = projectService.getOwnedProject(projectId);
        return authProfileRepository.findAllByProjectOrderByCreatedAtAsc(project).stream()
                .map(p -> AuthProfileResponse.from(p, testCaseRepository.countByAuthProfileId(p.getId())))
                .toList();
    }

    @Transactional
    public AuthProfileResponse create(UUID projectId, AuthProfileRequest request) {
        Project project = projectService.getOwnedProject(projectId);
        boolean loginMode = request.loginPath() != null && !request.loginPath().isBlank();
        if (loginMode) {
            validateLogin(request, request.loginBody());
        } else {
            projectService.validateTargetAuthValue(request.authType(), request.authValue());
        }
        String roleKey = normalizeRoleKey(request.roleKey() != null && !request.roleKey().isBlank() ? request.roleKey() : request.name());
        if (authProfileRepository.existsByProjectAndRoleKey(project, roleKey)) {
            throw new InvalidRequestException("Khoá vai trò '" + roleKey + "' đã có trong project này");
        }
        AuthProfile profile = AuthProfile.builder()
                .project(project)
                .name(request.name().trim())
                .roleKey(roleKey)
                .authType(loginMode ? TargetAuthType.BEARER_TOKEN : request.authType())
                .authValueEncrypted(loginMode || request.authType() == TargetAuthType.NONE ? null : aesEncryptionService.encrypt(request.authValue()))
                .authUpdatedAt(Instant.now())
                .build();
        if (loginMode) {
            applyLogin(profile, request, request.loginBody());
        }
        return AuthProfileResponse.from(authProfileRepository.save(profile), 0);
    }

    @Transactional
    public AuthProfileResponse update(UUID projectId, UUID profileId, AuthProfileRequest request) {
        Project project = projectService.getOwnedProject(projectId);
        AuthProfile profile = getOwned(project, profileId);
        profile.setName(request.name().trim());
        // roleKey bất biến: các mục ma trận phân quyền/khoá checklist dựa vào nó.
        boolean valueGiven = request.authValue() != null && !request.authValue().isBlank();
        boolean loginMode = request.loginPath() != null && !request.loginPath().isBlank();
        if (loginMode) {
            boolean bodyGiven = request.loginBody() != null && !request.loginBody().isBlank();
            if (!bodyGiven && !(profile.isLoginMode() && profile.getLoginBodyEncrypted() != null)) {
                throw new InvalidRequestException("Thiếu body đăng nhập (tài khoản/mật khẩu)");
            }
            validateLogin(request, bodyGiven ? request.loginBody() : "{}");
            profile.setAuthType(TargetAuthType.BEARER_TOKEN);
            profile.setAuthValueEncrypted(null);
            applyLogin(profile, request, bodyGiven ? request.loginBody() : null);
            profile.setAuthUpdatedAt(Instant.now());
        } else if (request.authType() == TargetAuthType.NONE) {
            clearLogin(profile);
            profile.setAuthType(TargetAuthType.NONE);
            profile.setAuthValueEncrypted(null);
            profile.setAuthUpdatedAt(Instant.now());
        } else if (valueGiven) {
            clearLogin(profile);
            profile.setAuthType(request.authType());
            profile.setAuthValueEncrypted(aesEncryptionService.encrypt(request.authValue()));
            profile.setAuthUpdatedAt(Instant.now());
        } else if (profile.isLoginMode()) {
            throw new InvalidRequestException("Profile đang ở chế độ đăng nhập tự động - nhập đường dẫn đăng nhập, hoặc nhập token để chuyển sang token tĩnh");
        } else if (profile.getAuthValueEncrypted() == null || profile.getAuthValueEncrypted().isBlank()) {
            throw new InvalidRequestException("Thiếu giá trị xác thực cho loại xác thực đã chọn");
        } else {
            profile.setAuthType(request.authType()); // đổi loại header, giữ nguyên token cũ
        }
        return AuthProfileResponse.from(authProfileRepository.save(profile), testCaseRepository.countByAuthProfileId(profileId));
    }

    /**
     * Chặn xoá khi còn test case dùng: tự gỡ profile khỏi test case sẽ ÂM THẦM đổi vai trò của test sang token mặc định và cho kết
     * quả phân quyền sai mà không ai hay.
     */
    @Transactional
    public void delete(UUID projectId, UUID profileId) {
        Project project = projectService.getOwnedProject(projectId);
        AuthProfile profile = getOwned(project, profileId);
        long inUse = testCaseRepository.countByAuthProfileId(profileId);
        if (inUse > 0) {
            throw new InvalidRequestException("Còn " + inUse + " test case đang dùng profile này - đổi sang profile khác hoặc xoá chúng trước");
        }
        // Khai báo ma trận phân quyền của vai trò này là cấu hình (không phải công sức người dùng) nên xoá theo.
        endpointRoleRepository.deleteAllByAuthProfileId(profileId);
        authProfileRepository.delete(profile);
    }

    /** Chế độ đăng nhập (G5/P1-4): loginPath chỉ là đường dẫn tương đối trên base URL của project, body là JSON object, có JSONPath tới token. */
    private void validateLogin(AuthProfileRequest request, String loginBody) {
        String path = request.loginPath().trim();
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("://") || path.chars().anyMatch(Character::isWhitespace)) {
            throw new InvalidRequestException("Đường dẫn đăng nhập phải là đường dẫn tương đối bắt đầu bằng '/' (vd /auth/login)");
        }
        if (request.tokenJsonPath() == null || request.tokenJsonPath().isBlank()) {
            throw new InvalidRequestException("Thiếu JSONPath tới token trong response đăng nhập (vd data.accessToken)");
        }
        if (!com.aiapitesting.backend.service.execution.AuthTokenService.isSafePath(request.tokenJsonPath().trim())) {
            throw new InvalidRequestException("JSONPath tới token chỉ gồm tên field, dấu chấm và chỉ số mảng (vd data.accessToken, tokens[0].value)");
        }
        try {
            if (!new com.fasterxml.jackson.databind.ObjectMapper().readTree(loginBody).isObject()) {
                throw new InvalidRequestException("Body đăng nhập phải là JSON object");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
            throw new InvalidRequestException("Body đăng nhập không phải JSON hợp lệ");
        }
    }

    private void applyLogin(AuthProfile profile, AuthProfileRequest request, String loginBody) {
        profile.setLoginPath(request.loginPath().trim());
        profile.setTokenJsonPath(request.tokenJsonPath().trim());
        if (loginBody != null && !loginBody.isBlank()) {
            profile.setLoginBodyEncrypted(aesEncryptionService.encrypt(loginBody));
        }
    }

    private void clearLogin(AuthProfile profile) {
        profile.setLoginPath(null);
        profile.setLoginBodyEncrypted(null);
        profile.setTokenJsonPath(null);
    }

    private AuthProfile getOwned(Project project, UUID profileId) {
        return authProfileRepository.findByIdAndProject(profileId, project)
                .orElseThrow(() -> new InvalidRequestException("Không tìm thấy profile xác thực"));
    }

    /** "Nhà tuyển dụng (HR)" → "NHA_TUYEN_DUNG_HR": bỏ dấu, chữ hoa, ký tự lạ thành "_". */
    static String normalizeRoleKey(String raw) {
        String ascii = java.text.Normalizer.normalize(raw.trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('đ', 'd').replace('Đ', 'D');
        String key = ascii.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (key.length() > 40) {
            key = key.substring(0, 40);
        }
        if (RESERVED_ROLE_KEY.equals(key)) {
            throw new InvalidRequestException("Khoá vai trò 'DEFAULT' dành cho token mặc định của project");
        }
        if (!VALID_ROLE_KEY.matcher(key).matches()) {
            throw new InvalidRequestException("Khoá vai trò phải bắt đầu bằng chữ cái và chỉ gồm chữ, số, dấu gạch dưới");
        }
        return key;
    }
}
