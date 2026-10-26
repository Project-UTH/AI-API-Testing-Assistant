package com.aiapitesting.backend.service;

import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.repository.EndpointRepository;
import com.aiapitesting.backend.service.execution.RestAssuredTestRunner;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tự tìm id thật từ endpoint danh sách: trích id từ nhiều dạng phản hồi, chỉ GET, chặn id có ký tự nguy hiểm. */
class PathParamDiscoveryServiceTest {

    private final EndpointRepository endpoints = mock(EndpointRepository.class);
    private final RestAssuredTestRunner runner = mock(RestAssuredTestRunner.class);
    private final PathParamDiscoveryService service = new PathParamDiscoveryService(endpoints, runner);
    private final Project project = Project.builder().id(UUID.randomUUID()).build();

    @Test
    void firstId_readsRootArray_dataWrappers_numbersAndUuids() {
        assertThat(service.firstId("[{\"id\":\"a1\"},{\"id\":\"b2\"}]", "id")).isEqualTo("a1");
        assertThat(service.firstId("{\"data\":[{\"id\":42}]}", "id")).isEqualTo("42");
        assertThat(service.firstId("{\"content\":[{\"id\":\"3fa85f64-5717-4562-b3fc-2c963f66afa6\"}],\"total\":1}", "id")).isEqualTo("3fa85f64-5717-4562-b3fc-2c963f66afa6");
        assertThat(service.firstId("{\"anything\":[{\"resumeId\":\"r9\"}]}", "resumeId")).isEqualTo("r9"); // khoá khác "id": dùng tên tham số
    }

    @Test
    void firstId_returnsNull_forEmptyListBrokenJsonOrUnsafeIds() {
        assertThat(service.firstId("[]", "id")).isNull();
        assertThat(service.firstId("{\"data\":[]}", "id")).isNull();
        assertThat(service.firstId("không phải json", "id")).isNull();
        assertThat(service.firstId("", "id")).isNull();
        assertThat(service.firstId("[{\"id\":\"../admin?x=1\"}]", "id")).isNull(); // ký tự điều khiển đường dẫn
        assertThat(service.firstId("[{\"id\":{\"nested\":1}}]", "id")).isNull();
    }

    @Test
    void discover_usesOnlyAGetOnTheParentCollection_andExplainsWhyItFailed() {
        Endpoint resource = Endpoint.builder().id(UUID.randomUUID()).method("POST").path("/resumes/{id}/reparse").build();
        when(endpoints.findByProjectAndPathAndMethod(project, "/resumes", "GET")).thenReturn(Optional.empty());
        var none = service.discover(project, resource, "id");
        assertThat(none.value()).isNull();
        assertThat(none.note()).contains("GET /resumes");
        verify(runner, never()).run(any(), any(), any());

        Endpoint list = Endpoint.builder().id(UUID.randomUUID()).method("GET").path("/resumes").build();
        when(endpoints.findByProjectAndPathAndMethod(project, "/resumes", "GET")).thenReturn(Optional.of(list));
        when(runner.run(any(), any(), any())).thenReturn(new RestAssuredTestRunner.RunResult(200, "[{\"id\":\"cv-1\"}]"));
        assertThat(service.discover(project, resource, "id").value()).isEqualTo("cv-1");

        when(runner.run(any(), any(), any())).thenReturn(new RestAssuredTestRunner.RunResult(401, "{}"));
        assertThat(service.discover(project, resource, "id").note()).contains("401");

        when(runner.run(any(), any(), any())).thenReturn(new RestAssuredTestRunner.RunResult(200, "[]"));
        assertThat(service.discover(project, resource, "id").note()).contains("chưa có dữ liệu");

        // đường dẫn cha còn tham số khác ({x}) thì không dùng (chưa giải được)
        Endpoint nested = Endpoint.builder().id(UUID.randomUUID()).method("PUT").path("/a/{x}/b/{y}").build();
        assertThat(service.discover(project, nested, "y").value()).isNull();
    }
}
