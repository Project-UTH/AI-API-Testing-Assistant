package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.service.TestCaseListQuery;
import com.aiapitesting.backend.service.TestCaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Module 17 (G4) - cách chọn giữa danh sách cũ và danh sách theo trang, và tham số rỗng/lạ không được làm 500. */
@ExtendWith(MockitoExtension.class)
class ProjectTestCaseControllerTest {

    @Mock
    private TestCaseService testCaseService;

    private MockMvc mvc;
    private final UUID projectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProjectTestCaseController(testCaseService)).build();
    }

    @Test
    void withoutPage_usesLegacyFullList() throws Exception {
        when(testCaseService.listByProject(projectId)).thenReturn(List.of());

        mvc.perform(get("/api/v1/projects/{id}/test-cases", projectId)).andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());

        verify(testCaseService).listByProject(projectId);
        verify(testCaseService, never()).listByProjectPaged(any(), any());
    }

    @Test
    void withPage_usesPagedListAndPassesFilters() throws Exception {
        when(testCaseService.listByProjectPaged(eq(projectId), any())).thenReturn(PageResponse.of(List.of(), 2, 50, 0));

        mvc.perform(get("/api/v1/projects/{id}/test-cases", projectId)
                        .param("page", "2").param("size", "20").param("q", "login").param("method", "POST").param("locked", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2));

        ArgumentCaptor<TestCaseListQuery> captor = ArgumentCaptor.forClass(TestCaseListQuery.class);
        verify(testCaseService).listByProjectPaged(eq(projectId), captor.capture());
        assertThat(captor.getValue().page()).isEqualTo(2);
        assertThat(captor.getValue().size()).isEqualTo(20);
        assertThat(captor.getValue().q()).isEqualTo("login");
        assertThat(captor.getValue().method()).isEqualTo("POST");
        assertThat(captor.getValue().locked()).isTrue();
        verify(testCaseService, never()).listByProject(any());
    }

    /** "?page=" (rỗng) trước đây làm 500 vì tham số kiểu int không nhận null. */
    @Test
    void emptyPageValue_isTreatedAsFirstPage_notAServerError() throws Exception {
        when(testCaseService.listByProjectPaged(eq(projectId), any())).thenReturn(PageResponse.of(List.of(), 0, 50, 0));

        mvc.perform(get("/api/v1/projects/{id}/test-cases", projectId).param("page", "")).andExpect(status().isOk());

        ArgumentCaptor<TestCaseListQuery> captor = ArgumentCaptor.forClass(TestCaseListQuery.class);
        verify(testCaseService).listByProjectPaged(eq(projectId), captor.capture());
        assertThat(captor.getValue().page()).isNull(); // PagingSupport.resolve đổi null thành trang 0
    }

    @Test
    void nonNumericPage_isRejectedAsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/projects/{id}/test-cases", projectId).param("page", "abc")).andExpect(status().isBadRequest());
    }
}
