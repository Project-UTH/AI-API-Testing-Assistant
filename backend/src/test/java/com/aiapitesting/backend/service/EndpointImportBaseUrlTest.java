package com.aiapitesting.backend.service;

import io.swagger.v3.oas.models.servers.Server;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** basePath/servers của spec là tiền tố của mọi path: nối vào URL người dùng chỉ nhập host, nhưng không đè lên URL đã có path. */
class EndpointImportBaseUrlTest {

    private static List<Server> servers(String url) {
        return List.of(new Server().url(url));
    }

    @Test
    void hostOnlyUrl_getsTheSpecBasePath_whetherServerIsAbsoluteOrRelative() {
        assertThat(EndpointImportService.resolveTargetBaseUrl("http://h:8080", servers("http://localhost:8091/api/v2"))).isEqualTo("http://h:8080/api/v2");
        assertThat(EndpointImportService.resolveTargetBaseUrl("http://h:8080/", servers("/api/v3/"))).isEqualTo("http://h:8080/api/v3");
    }

    @Test
    void urlWithItsOwnPath_orSpecWithoutBasePath_isKeptAsGiven() {
        assertThat(EndpointImportService.resolveTargetBaseUrl("http://h/custom", servers("http://x/api/v2"))).isEqualTo("http://h/custom");
        assertThat(EndpointImportService.resolveTargetBaseUrl("http://h:8080", servers("/"))).isEqualTo("http://h:8080");
        assertThat(EndpointImportService.resolveTargetBaseUrl("http://h:8080", servers("http://x"))).isEqualTo("http://h:8080");
        assertThat(EndpointImportService.resolveTargetBaseUrl("http://h:8080", null)).isEqualTo("http://h:8080");
    }

    @Test
    void blankUrl_usesAbsoluteServerOnly() {
        assertThat(EndpointImportService.resolveTargetBaseUrl("", servers("https://api.example.com/v1"))).isEqualTo("https://api.example.com/v1");
        assertThat(EndpointImportService.resolveTargetBaseUrl(null, servers("/api/v2"))).isNull();
        assertThat(EndpointImportService.resolveTargetBaseUrl(null, servers("/"))).isNull();
    }
}
