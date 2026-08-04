package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.response.EndpointResponse;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TargetAuthType;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.exception.SwaggerParseException;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.security.AesEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EndpointImportServiceTest {

    private static final String SAMPLE_OPENAPI = """
            {
              "openapi": "3.0.0",
              "info": { "title": "Sample", "version": "1.0" },
              "paths": {
                "/pets": {
                  "get": {
                    "summary": "List pets",
                    "responses": { "200": { "description": "ok" } }
                  }
                }
              }
            }
            """;

    @Mock
    private ProjectService projectService;

    @Mock
    private EndpointRepository endpointRepository;

    @Mock
    private SafeUrlFetcher safeUrlFetcher;

    @Mock
    private AesEncryptionService aesEncryptionService;

    @InjectMocks
    private EndpointImportService endpointImportService;

    private Project project;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        project = Project.builder().id(projectId).build();
    }

    @Test
    void importFromFile_parsesValidSpecAndReplacesExistingEndpoints() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        MockMultipartFile file = new MockMultipartFile(
                "file", "spec.json", "application/json", SAMPLE_OPENAPI.getBytes(StandardCharsets.UTF_8));

        List<EndpointResponse> result = endpointImportService.importFromFile(projectId, file, null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).path()).isEqualTo("/pets");
        assertThat(result.get(0).method()).isEqualTo("GET");
        assertThat(result.get(0).summary()).isEqualTo("List pets");

        verify(endpointRepository).deleteAllByProject(project);

        ArgumentCaptor<List<Endpoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(endpointRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
    }

    @Test
    void importFromUrl_fetchesContentThroughSafeFetcherThenParses() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(safeUrlFetcher.fetch("https://example.com/openapi.json")).thenReturn(SAMPLE_OPENAPI);
        when(endpointRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<EndpointResponse> result = endpointImportService.importFromUrl(
                projectId, "https://example.com/openapi.json", null, null);

        assertThat(result).hasSize(1);
        verify(safeUrlFetcher).fetch("https://example.com/openapi.json");
    }

    @Test
    void importFromUrl_ssrfRejectionPropagatesFromSafeFetcher() {
        when(safeUrlFetcher.fetch("http://169.254.169.254/latest/meta-data"))
                .thenThrow(new SwaggerParseException("URL trỏ tới địa chỉ nội bộ không được phép"));

        assertThatThrownBy(() -> endpointImportService.importFromUrl(
                projectId, "http://169.254.169.254/latest/meta-data", null, null))
                .isInstanceOf(SwaggerParseException.class);

        verifyNoInteractions(endpointRepository);
    }

    @Test
    void importFromFile_invalidJsonThrowsSwaggerParseException() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);

        MockMultipartFile file = new MockMultipartFile(
                "file", "spec.json", "application/json", "not a valid openapi document".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> endpointImportService.importFromFile(projectId, file, null, null))
                .isInstanceOf(SwaggerParseException.class);

        verify(endpointRepository, never()).saveAll(anyList());
    }

    @Test
    void importFromFile_authTypeWithoutValueThrowsInvalidRequest() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);

        MockMultipartFile file = new MockMultipartFile(
                "file", "spec.json", "application/json", SAMPLE_OPENAPI.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> endpointImportService.importFromFile(projectId, file, TargetAuthType.API_KEY, " "))
                .isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(aesEncryptionService);
    }

    @Test
    void importFromFile_encryptsAuthValueWhenProvided() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(aesEncryptionService.encrypt("secret-token")).thenReturn("encrypted-value");
        when(endpointRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        MockMultipartFile file = new MockMultipartFile(
                "file", "spec.json", "application/json", SAMPLE_OPENAPI.getBytes(StandardCharsets.UTF_8));

        endpointImportService.importFromFile(projectId, file, TargetAuthType.BEARER_TOKEN, "secret-token");

        assertThat(project.getTargetAuthType()).isEqualTo(TargetAuthType.BEARER_TOKEN);
        assertThat(project.getTargetAuthValueEncrypted()).isEqualTo("encrypted-value");
    }
}
