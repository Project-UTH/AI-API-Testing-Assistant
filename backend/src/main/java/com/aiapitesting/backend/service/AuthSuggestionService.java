package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.response.AuthSuggestionResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.repository.EndpointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Module 17 (S4) - nhiều API (springdoc mặc định...) không khai {@code security} trong spec dù thực tế chặn 401, nên mọi endpoint bị coi
 * là "không cần xác thực" và các mục Security bị "không áp dụng". Dịch vụ này đoán TẤT ĐỊNH theo tiền tố đường dẫn (2 đoạn đầu) nhóm nào
 * nhiều khả năng cần xác thực để GỢI Ý người dùng; không bao giờ tự áp dụng và không đụng endpoint đã khai tay.
 */
@Service
@RequiredArgsConstructor
public class AuthSuggestionService {

    static final int MIN_ENDPOINTS = 5;
    static final int SAMPLE_PATHS = 3;
    /** Đoạn đường dẫn (chữ thường) thường là công khai: đăng nhập/đăng ký/danh mục/tài liệu/health. */
    static final Set<String> PUBLIC_SEGMENTS = Set.of(
            "public", "auth", "login", "logout", "register", "signup", "signin", "health", "healthz", "catalog", "catalogs",
            "swagger", "swagger-ui", "docs", "api-docs", "actuator", "oauth", "token", "refresh", "forgot-password", "reset-password",
            "anonymous", "guest", "openapi", "version", "dang-nhap", "dang-ky", "dangnhap", "dangky");
    /** Đoạn gốc chung (không phải nhóm tài nguyên): bỏ qua khi lấy tiền tố nhóm, vd /api/v2/users -> /api/v2/users. */
    private static final java.util.regex.Pattern ROOT_SEGMENT = java.util.regex.Pattern.compile("(?i)api|rest|v\\d+(\\.\\d+)?");

    private final ProjectService projectService;
    private final EndpointRepository endpointRepository;

    @Transactional(readOnly = true)
    public AuthSuggestionResponse suggest(UUID projectId) {
        Project project = projectService.getOwnedProject(projectId);
        return suggest(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project));
    }

    /** Thuần tuý (không DB) để dễ kiểm thử. */
    static AuthSuggestionResponse suggest(List<Endpoint> all) {
        List<Endpoint> inSpec = all.stream().filter(e -> !Boolean.TRUE.equals(e.getMissingFromSpec())).toList();
        int alreadyManual = (int) inSpec.stream().filter(e -> e.getRequiresAuthManual() != null).count();
        // Spec đã khai xác thực (toàn cục hoặc từng endpoint) -> không gợi ý. Khai tay không tính là "spec khai".
        boolean specDeclaresSecurity = inSpec.stream().anyMatch(Endpoint::isRequiresAuth);
        if (specDeclaresSecurity || inSpec.size() < MIN_ENDPOINTS) {
            return AuthSuggestionResponse.none(inSpec.size(), alreadyManual);
        }

        Map<String, List<Endpoint>> byPrefix = new LinkedHashMap<>();
        for (Endpoint e : inSpec) {
            if (e.getRequiresAuthManual() == null && e.getPath() != null) {
                byPrefix.computeIfAbsent(prefixOf(e.getPath()), k -> new ArrayList<>()).add(e);
            }
        }
        List<AuthSuggestionResponse.Group> groups = new ArrayList<>();
        int protectedCount = 0, publicCount = 0;
        for (Map.Entry<String, List<Endpoint>> entry : byPrefix.entrySet()) {
            // Mỗi nhóm tách thành phần công khai/cần xác thực theo TỪNG endpoint (vd /api/auth/login công khai nhưng /api/auth/me thì không).
            List<Endpoint> prot = new ArrayList<>(), pub = new ArrayList<>();
            for (Endpoint e : entry.getValue()) {
                (isPublicPath(e.getPath()) ? pub : prot).add(e);
            }
            if (!prot.isEmpty()) {
                groups.add(group(entry.getKey(), true, prot));
                protectedCount += prot.size();
            }
            if (!pub.isEmpty()) {
                groups.add(group(entry.getKey(), false, pub));
                publicCount += pub.size();
            }
        }
        boolean hint = protectedCount > 0 && (publicCount > 0 || groups.stream().filter(g -> g.suggestedRequiresAuth()).count() > 1);
        return hint ? new AuthSuggestionResponse(true, inSpec.size(), alreadyManual, protectedCount, publicCount, List.copyOf(groups))
                : AuthSuggestionResponse.none(inSpec.size(), alreadyManual);
    }

    private static AuthSuggestionResponse.Group group(String prefix, boolean requiresAuth, List<Endpoint> endpoints) {
        List<String> samples = endpoints.stream().limit(SAMPLE_PATHS).map(e -> e.getMethod() + " " + e.getPath()).toList();
        return new AuthSuggestionResponse.Group(prefix, requiresAuth, endpoints.size(), samples, endpoints.stream().map(Endpoint::getId).toList());
    }

    /** Bỏ các đoạn gốc chung (api, v1, rest) rồi lấy 1 đoạn nhóm đầu tiên không phải tham số: "/api/v1/users/{id}" -> "/api/v1/users". */
    static String prefixOf(String path) {
        StringBuilder sb = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (segment.startsWith("{")) {
                break;
            }
            sb.append('/').append(segment);
            if (!ROOT_SEGMENT.matcher(segment).matches()) {
                break;
            }
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    static boolean isPublicPath(String path) {
        String[] segments = path.toLowerCase(Locale.ROOT).split("/");
        if (segments.length > 0 && "me".equals(segments[segments.length - 1])) {
            return false; // /auth/me, /profile/me: dữ liệu của người đang đăng nhập nên cần token
        }
        for (String segment : segments) {
            if (PUBLIC_SEGMENTS.contains(segment)) {
                return true;
            }
        }
        return false;
    }
}
