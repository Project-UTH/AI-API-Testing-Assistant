package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.response.AuthSuggestionResponse;
import com.aiapitesting.backend.entity.Endpoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Module 17 (S4) - gợi ý xác thực khi spec không khai báo: chỉ gợi ý, không đụng endpoint đã khai tay, im lặng khi spec đã khai. */
class AuthSuggestionServiceTest {

    private static Endpoint ep(String method, String path) {
        return Endpoint.builder().id(UUID.randomUUID()).method(method).path(path).requiresAuth(false).build();
    }

    private static List<Endpoint> recruitmentLikeSpec() {
        List<Endpoint> list = new ArrayList<>();
        list.add(ep("GET", "/api/hr/jobs"));
        list.add(ep("POST", "/api/hr/jobs"));
        list.add(ep("GET", "/api/hr/jobs/{jobId}"));
        list.add(ep("GET", "/api/candidates/applications"));
        list.add(ep("GET", "/api/candidates/profile/me"));
        list.add(ep("GET", "/api/public/catalogs"));
        list.add(ep("POST", "/api/auth/login"));
        list.add(ep("POST", "/api/auth/register/hr"));
        list.add(ep("GET", "/api/auth/me")); // dưới /auth nhưng 'me' là dữ liệu của người đăng nhập -> cần token
        return list;
    }

    private static AuthSuggestionResponse.Group group(AuthSuggestionResponse r, String prefix, boolean protectedGroup) {
        return r.groups().stream().filter(g -> g.pathPrefix().equals(prefix) && g.suggestedRequiresAuth() == protectedGroup).findFirst().orElseThrow();
    }

    @Test
    void specWithoutSecurity_suggestsRoleGroupsAsProtected_andPublicPathsAsPublic() {
        AuthSuggestionResponse r = AuthSuggestionService.suggest(recruitmentLikeSpec());

        assertThat(r.hint()).isTrue();
        assertThat(group(r, "/api/hr", true).count()).isEqualTo(3);
        assertThat(group(r, "/api/candidates", true).count()).isEqualTo(2);
        assertThat(group(r, "/api/public", false).count()).isEqualTo(1);
        assertThat(group(r, "/api/auth", false).count()).isEqualTo(2);
        assertThat(group(r, "/api/auth", true).count()).isEqualTo(1); // /api/auth/me
        assertThat(r.suggestedProtected()).isEqualTo(6);
        assertThat(group(r, "/api/hr", true).samplePaths()).hasSize(3).first().asString().contains("/api/hr/jobs");
        assertThat(group(r, "/api/hr", true).endpointIds()).hasSize(3);
    }

    @Test
    void specThatDeclaresSecurityAnywhere_hasNoHint() {
        List<Endpoint> list = recruitmentLikeSpec();
        list.get(0).setRequiresAuth(true); // global security hoặc security ở 1 endpoint
        assertThat(AuthSuggestionService.suggest(list).hint()).isFalse();
    }

    @Test
    void manualValuesAreExcludedAndCounted_neverSuggestedAgain() {
        List<Endpoint> list = recruitmentLikeSpec();
        list.get(0).setRequiresAuthManual(true);
        list.get(3).setRequiresAuthManual(false);

        AuthSuggestionResponse r = AuthSuggestionService.suggest(list);

        assertThat(r.alreadyManual()).isEqualTo(2);
        assertThat(group(r, "/api/hr", true).count()).isEqualTo(2);
        assertThat(r.groups().stream().flatMap(g -> g.endpointIds().stream())).doesNotContain(list.get(0).getId(), list.get(3).getId());
    }

    @Test
    void tinySpecOrSingleUndifferentiatedGroup_hasNoHint() {
        assertThat(AuthSuggestionService.suggest(List.of(ep("GET", "/a"), ep("GET", "/b"))).hint()).isFalse();
        List<Endpoint> oneGroup = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            oneGroup.add(ep("GET", "/api/products/" + i));
        }
        assertThat(AuthSuggestionService.suggest(oneGroup).hint()).isFalse(); // không phân biệt được công khai/cần xác thực
        assertThat(AuthSuggestionService.suggest(List.of()).hint()).isFalse();
    }

    @Test
    void endpointsRemovedFromSpecAreIgnored() {
        List<Endpoint> list = recruitmentLikeSpec();
        list.forEach(e -> e.setMissingFromSpec(true));
        assertThat(AuthSuggestionService.suggest(list).total()).isZero();
        assertThat(AuthSuggestionService.suggest(list).hint()).isFalse();
    }

    @Test
    void prefixAndPublicDetection_areCaseInsensitiveAndStopAtPathParams() {
        assertThat(AuthSuggestionService.prefixOf("/api/hr/jobs/{jobId}/x")).isEqualTo("/api/hr");
        assertThat(AuthSuggestionService.prefixOf("/{id}/x")).isEqualTo("/");
        assertThat(AuthSuggestionService.prefixOf("/ping")).isEqualTo("/ping");
        assertThat(AuthSuggestionService.isPublicPath("/API/Auth/LOGIN")).isTrue();
        assertThat(AuthSuggestionService.isPublicPath("/api/hr/jobs")).isFalse();
    }
}
