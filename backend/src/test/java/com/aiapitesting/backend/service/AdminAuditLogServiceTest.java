package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.response.AdminAuditEventResponse;
import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.entity.AdminAuditAction;
import com.aiapitesting.backend.entity.AdminAuditEvent;
import com.aiapitesting.backend.repository.AdminAuditEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAuditLogServiceTest {

    @Mock
    private AdminAuditEventRepository adminAuditEventRepository;

    @InjectMocks
    private AdminAuditLogService adminAuditLogService;

    @Test
    void list_mapsEntitiesToResponse_newestFirstOrderComesFromRepository() {
        Pageable pageable = PageRequest.of(0, 20);
        AdminAuditEvent event = AdminAuditEvent.builder()
                .id(UUID.randomUUID())
                .adminEmail("admin@test.com")
                .targetEmail("user1@test.com")
                .action(AdminAuditAction.USER_LOCKED)
                .createdAt(Instant.now())
                .build();
        when(adminAuditEventRepository.findAllByOrderByCreatedAtDesc(pageable))
                .thenReturn(new PageImpl<>(List.of(event), pageable, 1));

        PageResponse<AdminAuditEventResponse> result = adminAuditLogService.list(pageable);

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).adminEmail()).isEqualTo("admin@test.com");
        assertThat(result.data().get(0).targetEmail()).isEqualTo("user1@test.com");
        assertThat(result.data().get(0).action()).isEqualTo(AdminAuditAction.USER_LOCKED);
    }
}
