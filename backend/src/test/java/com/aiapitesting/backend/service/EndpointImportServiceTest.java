package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.response.EndpointResponse;
import com.aiapitesting.backend.dto.response.ImportImpactResponse;
import com.aiapitesting.backend.entity.BugStatus;
import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.entity.TestCaseSource;
import com.aiapitesting.backend.exception.ExecutionInProgressException;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TargetAuthType;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.exception.SwaggerParseException;
import com.aiapitesting.backend.repository.BugReportEventRepository;
import com.aiapitesting.backend.repository.BugReportRepository;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.repository.TestCaseAssertionRepository;
import com.aiapitesting.backend.repository.TestCaseDependencyRepository;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestExecutionEndpointRepository;
import com.aiapitesting.backend.repository.TestExecutionRepository;
import com.aiapitesting.backend.repository.TestGenerationEventRepository;
import com.aiapitesting.backend.repository.TestResultRepository;
import com.aiapitesting.backend.security.AesEncryptionService;
import com.aiapitesting.backend.security.TargetAuthHeaderResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
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

    private static final String OPENAPI_WITH_RESPONSE_SCHEMA = """
            {
              "openapi": "3.0.0",
              "info": { "title": "Sample", "version": "1.0" },
              "paths": {
                "/pets/{id}": {
                  "get": {
                    "summary": "Get pet",
                    "parameters": [ { "name": "id", "in": "path", "required": true, "schema": { "type": "integer" } } ],
                    "responses": {
                      "200": { "description": "ok", "content": { "application/json": { "schema": { "$ref": "#/components/schemas/Pet" } } } },
                      "404": { "description": "missing", "content": { "application/json": { "schema": { "type": "object", "properties": { "code": { "type": "string" } } } } } }
                    }
                  }
                }
              },
              "components": { "schemas": { "Pet": { "type": "object", "required": ["id", "name"],
                "properties": { "id": { "type": "integer" }, "name": { "type": "string" } } } } }
            }
            """;

    private static final String SAMPLE_OPENAPI_WITH_SERVERS = """
            {
              "openapi": "3.0.0",
              "info": { "title": "Sample", "version": "1.0" },
              "servers": [ { "url": "https://petstore.example.com/v2" } ],
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
    private com.aiapitesting.backend.repository.EndpointRoleRepository endpointRoleRepository;

    @Mock
    private ProjectService projectService;

    @Mock
    private EndpointRepository endpointRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private TestResultRepository testResultRepository;

    @Mock
    private TestExecutionRepository testExecutionRepository;

    @Mock
    private TestExecutionEndpointRepository testExecutionEndpointRepository;

    @Mock
    private TestCaseDependencyRepository testCaseDependencyRepository;

    @Mock
    private TestCaseAssertionRepository testCaseAssertionRepository;

    @Mock
    private TestGenerationEventRepository testGenerationEventRepository;

    @Mock
    private BugReportRepository bugReportRepository;

    @Mock
    private BugReportEventRepository bugReportEventRepository;

    @Mock
    private SafeUrlFetcher safeUrlFetcher;

    @Mock
    private AesEncryptionService aesEncryptionService;

    @Spy
    private TargetAuthHeaderResolver targetAuthHeaderResolver = new TargetAuthHeaderResolver();

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
    void importFromFile_projectHasRunningExecution_isRejectedAndDeletesNothing() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(testExecutionRepository.existsByProjectAndStatusIn(eq(project), any())).thenReturn(true);
        MockMultipartFile file = new MockMultipartFile(
                "file", "spec.json", "application/json", SAMPLE_OPENAPI.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> endpointImportService.importFromFile(projectId, file, null, null, null))
                .isInstanceOf(ExecutionInProgressException.class);

        verify(endpointRepository, never()).deleteAllByProject(any());
        verify(testCaseRepository, never()).deleteAllByEndpointProject(any());
    }

    @Test
    void getImportImpact_mapsRepositoryCountsOfTheProject() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.countByProject(project)).thenReturn(7L);
        when(testCaseRepository.countByEndpointProject(project)).thenReturn(74L);
        when(testCaseRepository.countByEndpointProjectAndSource(project, TestCaseSource.MANUAL)).thenReturn(3L);
        when(testCaseRepository.countByEndpointProjectAndLockedTrue(project)).thenReturn(2L);
        when(testExecutionRepository.countByProject(project)).thenReturn(23L);
        when(testResultRepository.countByTestCaseEndpointProject(project)).thenReturn(90L);
        when(bugReportRepository.countByProject(project)).thenReturn(5L);
        when(bugReportRepository.countByProjectAndStatusNot(project, BugStatus.CLOSED)).thenReturn(4L);
        when(testGenerationEventRepository.countByEndpointProject(project)).thenReturn(9L);
        when(testResultRepository.countByTestCaseEndpointProjectAndStatus(project, com.aiapitesting.backend.entity.TestResultStatus.FAILED)).thenReturn(30L);

        ImportImpactResponse impact = endpointImportService.getImportImpact(projectId);

        assertThat(impact).isEqualTo(new ImportImpactResponse(7, 74, 3, 2, 23, 90, 5, 4, 9, 30));
    }

    @Test
    void importFromFile_storesOnly2xxJsonResponseSchemasInSeparateColumnWithRefsInlined() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        MockMultipartFile file = new MockMultipartFile(
                "file", "spec.json", "application/json", OPENAPI_WITH_RESPONSE_SCHEMA.getBytes(StandardCharsets.UTF_8));

        endpointImportService.importFromFile(projectId, file, null, null, null);

        ArgumentCaptor<List<Endpoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(endpointRepository).saveAll(captor.capture());
        String responseSchemas = captor.getValue().get(0).getResponseSchemas();
        assertThat(responseSchemas).contains("\"200\"").contains("\"required\"").contains("\"name\"").doesNotContain("$ref");
        assertThat(responseSchemas).as("chỉ lấy 2xx, không lấy 404").doesNotContain("\"404\"");
        // Schema AI vẫn không phình ra: cột `schema` giữ nguyên thiết kế (chỉ mã trạng thái, không có body response).
        assertThat(captor.getValue().get(0).getSchema()).doesNotContain("\"required\":[\"id\"");
    }

    @Test
    void importFromFile_wildcardMediaTypeIsTreatedAsJson_becauseSpringdocDefaultsToIt() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        String spec = OPENAPI_WITH_RESPONSE_SCHEMA.replace("application/json", "*/*");
        MockMultipartFile file = new MockMultipartFile("file", "spec.json", "application/json", spec.getBytes(StandardCharsets.UTF_8));

        endpointImportService.importFromFile(projectId, file, null, null, null);

        ArgumentCaptor<List<Endpoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(endpointRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getResponseSchemas()).contains("\"200\"");
    }

    @Test
    void importFromFile_specWithoutResponseSchema_leavesResponseSchemasNull() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        MockMultipartFile file = new MockMultipartFile(
                "file", "spec.json", "application/json", SAMPLE_OPENAPI.getBytes(StandardCharsets.UTF_8));

        endpointImportService.importFromFile(projectId, file, null, null, null);

        ArgumentCaptor<List<Endpoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(endpointRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getResponseSchemas()).isNull();
    }

    @Test
    void importFromFile_parsesValidSpecAndReplacesExistingEndpoints() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        MockMultipartFile file = new MockMultipartFile(
                "file", "spec.json", "application/json", SAMPLE_OPENAPI.getBytes(StandardCharsets.UTF_8));

        List<EndpointResponse> result = endpointImportService.importFromFile(projectId, file, null, null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).path()).isEqualTo("/pets");
        assertThat(result.get(0).method()).isEqualTo("GET");
        assertThat(result.get(0).summary()).isEqualTo("List pets");

        // Dọn TestExecutionEndpoint/TestResult/TestExecution/test case/TestGenerationEvent của các
        // endpoint cũ trước khi xoá endpoint - tránh vi phạm khoá ngoại (lỗi MySQL 1451) nếu các
        // endpoint đó đã có dữ liệu liên quan
        verify(testExecutionEndpointRepository).deleteAllByExecutionProject(project);
        verify(testResultRepository).deleteAllByTestCaseEndpointProject(project);
        verify(testExecutionRepository).deleteAllByProject(project);
        verify(testCaseRepository).deleteAllByEndpointProject(project);
        verify(testGenerationEventRepository).detachFromEndpointsOfProject(project);
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
                projectId, "https://example.com/openapi.json", null, null, null);

        assertThat(result).hasSize(1);
        verify(safeUrlFetcher).fetch("https://example.com/openapi.json");
    }

    @Test
    void importFromUrl_withBearerAuth_passesAuthorizationHeaderToFetcher() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(safeUrlFetcher.fetch("https://private.example.com/openapi.json", "Authorization", "Bearer secret-token"))
                .thenReturn(SAMPLE_OPENAPI);
        when(endpointRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<EndpointResponse> result = endpointImportService.importFromUrl(
                projectId, "https://private.example.com/openapi.json", TargetAuthType.BEARER_TOKEN, "secret-token", null);

        assertThat(result).hasSize(1);
        verify(safeUrlFetcher).fetch("https://private.example.com/openapi.json", "Authorization", "Bearer secret-token");
        verify(safeUrlFetcher, never()).fetch(anyString());
    }

    @Test
    void importFromUrl_withApiKeyAuth_passesApiKeyHeaderToFetcher() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(safeUrlFetcher.fetch("https://private.example.com/openapi.json", "X-API-Key", "my-key"))
                .thenReturn(SAMPLE_OPENAPI);
        when(endpointRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        endpointImportService.importFromUrl(
                projectId, "https://private.example.com/openapi.json", TargetAuthType.API_KEY, "my-key", null);

        verify(safeUrlFetcher).fetch("https://private.example.com/openapi.json", "X-API-Key", "my-key");
    }

    @Test
    void importFromUrl_authTypeWithoutValueFailsBeforeFetching() {
        doThrow(new InvalidRequestException("Thiếu giá trị xác thực cho loại xác thực đã chọn"))
                .when(projectService).validateTargetAuthValue(TargetAuthType.BEARER_TOKEN, " ");

        assertThatThrownBy(() -> endpointImportService.importFromUrl(
                projectId, "https://private.example.com/openapi.json", TargetAuthType.BEARER_TOKEN, " ", null))
                .isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(safeUrlFetcher);
        verifyNoInteractions(endpointRepository);
    }

    @Test
    void importFromFile_invalidJsonThrowsSwaggerParseException() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);

        MockMultipartFile file = new MockMultipartFile(
                "file", "spec.json", "application/json", "not a valid openapi document".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> endpointImportService.importFromFile(projectId, file, null, null, null))
                .isInstanceOf(SwaggerParseException.class);

        verify(endpointRepository, never()).saveAll(anyList());
    }

    @Test
    void importFromFile_authTypeWithoutValueThrowsInvalidRequest() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        doThrow(new InvalidRequestException("Thiếu giá trị xác thực cho loại xác thực đã chọn"))
                .when(projectService).validateTargetAuthValue(TargetAuthType.API_KEY, " ");

        MockMultipartFile file = new MockMultipartFile(
                "file", "spec.json", "application/json", SAMPLE_OPENAPI.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> endpointImportService.importFromFile(projectId, file, TargetAuthType.API_KEY, " ", null))
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

        endpointImportService.importFromFile(projectId, file, TargetAuthType.BEARER_TOKEN, "secret-token", null);

        assertThat(project.getTargetAuthType()).isEqualTo(TargetAuthType.BEARER_TOKEN);
        assertThat(project.getTargetAuthValueEncrypted()).isEqualTo("encrypted-value");
    }

    @Test
    void importFromFile_targetBaseUrlProvidedByUser_takesPriorityOverSpecServers() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        MockMultipartFile file = new MockMultipartFile(
                "file", "spec.json", "application/json", SAMPLE_OPENAPI_WITH_SERVERS.getBytes(StandardCharsets.UTF_8));

        endpointImportService.importFromFile(projectId, file, null, null, "https://user-chosen.example.com");

        // Host do người dùng chọn thắng host của spec, nhưng tiền tố /v2 của servers[] vẫn là tiền tố của mọi path nên được nối thêm
        assertThat(project.getTargetBaseUrl()).isEqualTo("https://user-chosen.example.com/v2");
    }

    @Test
    void importFromFile_targetBaseUrlWithItsOwnPath_isKeptExactlyAsGiven() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        MockMultipartFile file = new MockMultipartFile(
                "file", "spec.json", "application/json", SAMPLE_OPENAPI_WITH_SERVERS.getBytes(StandardCharsets.UTF_8));

        endpointImportService.importFromFile(projectId, file, null, null, "https://user-chosen.example.com/custom");

        assertThat(project.getTargetBaseUrl()).isEqualTo("https://user-chosen.example.com/custom");
    }

    @Test
    void importFromFile_targetBaseUrlBlank_fallsBackToSpecServers() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        MockMultipartFile file = new MockMultipartFile(
                "file", "spec.json", "application/json", SAMPLE_OPENAPI_WITH_SERVERS.getBytes(StandardCharsets.UTF_8));

        endpointImportService.importFromFile(projectId, file, null, null, "  ");

        assertThat(project.getTargetBaseUrl()).isEqualTo("https://petstore.example.com/v2");
    }

    @Test
    void importFromFile_noTargetBaseUrlAndSpecHasNoServers_leavesNull() {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        MockMultipartFile file = new MockMultipartFile(
                "file", "spec.json", "application/json", SAMPLE_OPENAPI.getBytes(StandardCharsets.UTF_8));

        endpointImportService.importFromFile(projectId, file, null, null, null);

        assertThat(project.getTargetBaseUrl()).isNull();
    }

    // ---- Module 17 (17c) - import theo diff ----

    private static final String SPEC_PETS_WITH_QUERY = """
            {
              "openapi": "3.0.0",
              "info": { "title": "Sample", "version": "1.0" },
              "paths": {
                "/pets": {
                  "get": {
                    "summary": "List pets",
                    "parameters": [ { "name": "limit", "in": "query", "required": true, "schema": { "type": "integer" } } ],
                    "responses": { "200": { "description": "ok" } }
                  }
                }
              }
            }
            """;

    private MockMultipartFile spec(String content) {
        return new MockMultipartFile("file", "spec.json", "application/json", content.getBytes(StandardCharsets.UTF_8));
    }

    /** Import lần đầu (project chưa có endpoint) rồi trả các endpoint đã lưu - dùng làm "endpoint hiện có" cho lần import kế. */
    private List<Endpoint> firstImport(String content) {
        when(projectService.getOwnedProject(projectId)).thenReturn(project);
        when(endpointRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        endpointImportService.importFromFile(projectId, spec(content), null, null, null, com.aiapitesting.backend.entity.ImportMode.DIFF);
        ArgumentCaptor<List<Endpoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(endpointRepository).saveAll(captor.capture());
        List<Endpoint> saved = new java.util.ArrayList<>(captor.getValue());
        saved.forEach(e -> e.setId(UUID.randomUUID()));
        clearInvocations(endpointRepository, testCaseRepository, bugReportRepository, testResultRepository, testExecutionRepository,
                endpointRoleRepository);
        return saved;
    }

    private void projectAlreadyHas(List<Endpoint> existing) {
        lenient().when(endpointRepository.countByProject(project)).thenReturn((long) existing.size());
        lenient().when(endpointRepository.findAllByProjectOrderByCreatedAtAsc(project)).thenReturn(existing);
    }

    private void verifyNothingDeleted() {
        verify(endpointRepository, never()).deleteAllByProject(any());
        verify(testCaseRepository, never()).deleteAllByEndpointProject(any());
        verify(bugReportRepository, never()).deleteAllByProject(any());
        verify(testResultRepository, never()).deleteAllByTestCaseEndpointProject(any());
        verify(testExecutionRepository, never()).deleteAllByProject(any());
        verify(endpointRoleRepository, never()).deleteAllByEndpointProject(any());
    }

    @Test
    void diffImport_sameSpecAgain_keepsEverythingAndDeletesNothing() {
        List<Endpoint> existing = firstImport(SAMPLE_OPENAPI);
        projectAlreadyHas(existing);

        List<EndpointResponse> result = endpointImportService.importFromFile(
                projectId, spec(SAMPLE_OPENAPI), null, null, null, com.aiapitesting.backend.entity.ImportMode.DIFF);

        verifyNothingDeleted();
        assertThat(result).hasSize(1);
        assertThat(existing.get(0).getSchemaChangedAt()).isNull();
        assertThat(Boolean.TRUE.equals(existing.get(0).getMissingFromSpec())).isFalse();
    }

    @Test
    void diffImport_changedEndpoint_isUpdatedAndFlaggedButItsTestCasesStay() {
        List<Endpoint> existing = firstImport(SAMPLE_OPENAPI);
        projectAlreadyHas(existing);

        endpointImportService.importFromFile(projectId, spec(SPEC_PETS_WITH_QUERY), null, null, null,
                com.aiapitesting.backend.entity.ImportMode.DIFF);

        verifyNothingDeleted();
        Endpoint pets = existing.get(0);
        assertThat(pets.getSchema()).contains("limit");
        assertThat(pets.getSchemaChangedAt()).isNotNull();
    }

    @Test
    void diffImport_newEndpointIsAdded_missingOneIsFlaggedNotDeleted() {
        List<Endpoint> existing = firstImport(SAMPLE_OPENAPI); // chỉ có GET /pets
        projectAlreadyHas(existing);

        // Spec mới bỏ GET /pets, chỉ còn GET /pets/{id}
        List<EndpointResponse> result = endpointImportService.importFromFile(projectId, spec(OPENAPI_WITH_RESPONSE_SCHEMA), null, null, null,
                com.aiapitesting.backend.entity.ImportMode.DIFF);

        verifyNothingDeleted();
        assertThat(existing.get(0).getMissingFromSpec()).isTrue();
        ArgumentCaptor<List<Endpoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(endpointRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(Endpoint::getPath).contains("/pets", "/pets/{id}");
    }

    @Test
    void diffImport_legacyEndpointWithoutHash_comparedByItsStoredData_notTreatedAsChanged() {
        List<Endpoint> existing = firstImport(SAMPLE_OPENAPI);
        existing.get(0).setSpecHash(null); // endpoint tạo trước Module 17
        projectAlreadyHas(existing);

        endpointImportService.importFromFile(projectId, spec(SAMPLE_OPENAPI), null, null, null, com.aiapitesting.backend.entity.ImportMode.DIFF);

        assertThat(existing.get(0).getSchemaChangedAt()).isNull();
        assertThat(existing.get(0).getSpecHash()).isNotNull(); // được ghi lại
    }

    @Test
    void replaceMode_stillDeletesEverythingEvenWhenEndpointsExist() {
        List<Endpoint> existing = firstImport(SAMPLE_OPENAPI);
        projectAlreadyHas(existing);

        endpointImportService.importFromFile(projectId, spec(SAMPLE_OPENAPI), null, null, null, com.aiapitesting.backend.entity.ImportMode.REPLACE);

        verify(endpointRepository).deleteAllByProject(project);
        verify(testCaseRepository).deleteAllByEndpointProject(project);
    }

    @Test
    void preview_reportsCountsWithoutWritingAnything() {
        List<Endpoint> existing = firstImport(SAMPLE_OPENAPI);
        projectAlreadyHas(existing);
        when(testCaseRepository.countByEndpointIds(anyList())).thenReturn(List.of());

        var diff = endpointImportService.previewFromFile(projectId, spec(SPEC_PETS_WITH_QUERY));

        assertThat(diff.changedCount()).isEqualTo(1);
        assertThat(diff.addedCount()).isZero();
        assertThat(diff.removedCount()).isZero();
        assertThat(diff.changedEndpoints()).containsExactly("GET /pets");
        verify(endpointRepository, never()).saveAll(anyList());
        verify(endpointRepository, never()).save(any());
        assertThat(existing.get(0).getSchemaChangedAt()).isNull();
    }

    private static final String SAMPLE_OPENAPI_NEW_SUMMARY = SAMPLE_OPENAPI.replace("List pets", "Danh sach thu cung");

    @Test
    void diffImport_onlyTheSummaryChanged_isNotTreatedAsSpecChange() {
        List<Endpoint> existing = firstImport(SAMPLE_OPENAPI);
        projectAlreadyHas(existing);

        endpointImportService.importFromFile(projectId, spec(SAMPLE_OPENAPI_NEW_SUMMARY), null, null, null,
                com.aiapitesting.backend.entity.ImportMode.DIFF);

        // Cột schema có ghi kèm summary: đổi 1 câu mô tả không được làm test case bị coi là "có thể lỗi thời".
        assertThat(existing.get(0).getSchemaChangedAt()).isNull();
        assertThat(existing.get(0).getSummary()).isEqualTo("Danh sach thu cung");
    }

    @Test
    void diffImport_sameSpecParsedTwice_givesTheSameHash() {
        List<Endpoint> first = firstImport(OPENAPI_WITH_RESPONSE_SCHEMA);
        when(endpointRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        endpointImportService.importFromFile(projectId, spec(OPENAPI_WITH_RESPONSE_SCHEMA), null, null, null,
                com.aiapitesting.backend.entity.ImportMode.REPLACE);
        ArgumentCaptor<List<Endpoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(endpointRepository).saveAll(captor.capture());

        assertThat(captor.getValue().get(0).getSpecHash()).isNotNull().isEqualTo(first.get(0).getSpecHash());
    }

    @Test
    void diffImport_legacyEndpointWithoutHashAndResponseSchemas_isUnchangedAndGetsBackfilledNotFlagged() {
        List<Endpoint> existing = firstImport(OPENAPI_WITH_RESPONSE_SCHEMA);
        Endpoint legacy = existing.get(0);
        legacy.setSpecHash(null);
        legacy.setResponseSchemas(null); // import trước khi có đối chiếu schema response
        projectAlreadyHas(existing);

        endpointImportService.importFromFile(projectId, spec(OPENAPI_WITH_RESPONSE_SCHEMA), null, null, null,
                com.aiapitesting.backend.entity.ImportMode.DIFF);

        assertThat(legacy.getSchemaChangedAt()).isNull();
        assertThat(legacy.getSpecHash()).isNotNull();
        assertThat(legacy.getResponseSchemas()).isNotNull();
    }

    @Test
    void diffImport_userLeftBaseUrlBlank_keepsTheConfiguredBaseUrl() {
        List<Endpoint> existing = firstImport(SAMPLE_OPENAPI_WITH_SERVERS);
        project.setTargetBaseUrl("http://my-configured-target:9000");
        projectAlreadyHas(existing);

        endpointImportService.importFromFile(projectId, spec(SAMPLE_OPENAPI_WITH_SERVERS), null, null, null,
                com.aiapitesting.backend.entity.ImportMode.DIFF);

        assertThat(project.getTargetBaseUrl()).isEqualTo("http://my-configured-target:9000");
    }

    @Test
    void diffImport_userGaveBaseUrl_isApplied() {
        List<Endpoint> existing = firstImport(SAMPLE_OPENAPI);
        project.setTargetBaseUrl("http://old:1");
        projectAlreadyHas(existing);

        endpointImportService.importFromFile(projectId, spec(SAMPLE_OPENAPI), null, null, "http://new:2",
                com.aiapitesting.backend.entity.ImportMode.DIFF);

        assertThat(project.getTargetBaseUrl()).isEqualTo("http://new:2");
    }

    @Test
    void diffImport_unchangedEndpoints_areNotRewritten() {
        List<Endpoint> existing = firstImport(SAMPLE_OPENAPI);
        existing.get(0).setMissingFromSpec(false);
        projectAlreadyHas(existing);

        endpointImportService.importFromFile(projectId, spec(SAMPLE_OPENAPI), null, null, null, com.aiapitesting.backend.entity.ImportMode.DIFF);

        ArgumentCaptor<List<Endpoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(endpointRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).isEmpty();
    }
}
