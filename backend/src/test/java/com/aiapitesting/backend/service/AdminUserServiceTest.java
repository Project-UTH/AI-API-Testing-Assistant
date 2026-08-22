package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.response.AdminUserResponse;
import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.entity.AdminAuditAction;
import com.aiapitesting.backend.entity.AdminAuditEvent;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.entity.UserRole;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.exception.UserNotFoundException;
import com.aiapitesting.backend.repository.AdminAuditEventRepository;
import com.aiapitesting.backend.repository.BugReportRepository;
import com.aiapitesting.backend.repository.BugReportRepository.OwnerBugReportCount;
import com.aiapitesting.backend.repository.ProjectRepository;
import com.aiapitesting.backend.repository.ProjectRepository.OwnerCount;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestCaseRepository.OwnerTestCaseCount;
import com.aiapitesting.backend.repository.TestGenerationEventRepository;
import com.aiapitesting.backend.repository.TestGenerationEventRepository.OwnerAiUsage;
import com.aiapitesting.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private BugReportRepository bugReportRepository;

    @Mock
    private TestGenerationEventRepository testGenerationEventRepository;

    @Mock
    private AdminAuditEventRepository adminAuditEventRepository;

    @Mock
    private CurrentUserService currentUserService;

    @InjectMocks
    private AdminUserService adminUserService;

    private User user1;
    private User user2;
    private User admin;

    @BeforeEach
    void setUp() {
        user1 = User.builder().id(UUID.randomUUID()).email("user1@test.com")
                .role(UserRole.USER).enabled(true).createdAt(Instant.now()).build();
        user2 = User.builder().id(UUID.randomUUID()).email("user2@test.com")
                .role(UserRole.USER).enabled(true).createdAt(Instant.now()).build();
        admin = User.builder().id(UUID.randomUUID()).email("admin@test.com")
                .role(UserRole.ADMIN).enabled(true).createdAt(Instant.now()).build();
    }

    @Test
    void listUsers_mapsCountsFromGroupedQueries_userWithNoDataDefaultsToZero() {
        Pageable pageable = PageRequest.of(0, 20);
        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(user1, user2), pageable, 2));

        OwnerCount projectCount = mock(OwnerCount.class);
        when(projectCount.getOwnerId()).thenReturn(user1.getId());
        when(projectCount.getCount()).thenReturn(3L);
        when(projectRepository.countGroupedByOwnerIds(anyList())).thenReturn(List.of(projectCount));

        OwnerTestCaseCount testCaseCount = mock(OwnerTestCaseCount.class);
        when(testCaseCount.getOwnerId()).thenReturn(user1.getId());
        when(testCaseCount.getCount()).thenReturn(10L);
        when(testCaseRepository.countGroupedByOwnerIds(anyList())).thenReturn(List.of(testCaseCount));

        OwnerBugReportCount bugCount = mock(OwnerBugReportCount.class);
        when(bugCount.getOwnerId()).thenReturn(user1.getId());
        when(bugCount.getCount()).thenReturn(2L);
        when(bugReportRepository.countGroupedByOwnerIds(anyList())).thenReturn(List.of(bugCount));

        PageResponse<AdminUserResponse> result = adminUserService.listUsers(pageable);

        assertThat(result.data()).hasSize(2);
        AdminUserResponse response1 = result.data().stream()
                .filter(r -> r.id().equals(user1.getId())).findFirst().orElseThrow();
        assertThat(response1.totalProjects()).isEqualTo(3);
        assertThat(response1.totalTestCases()).isEqualTo(10);
        assertThat(response1.totalBugReports()).isEqualTo(2);

        // user2 không xuất hiện trong kết quả GROUP BY (chưa có project/test case/bug nào) -
        // phải mặc định về 0, không NPE khi map ngược từ Map.getOrDefault.
        AdminUserResponse response2 = result.data().stream()
                .filter(r -> r.id().equals(user2.getId())).findFirst().orElseThrow();
        assertThat(response2.totalProjects()).isZero();
        assertThat(response2.totalTestCases()).isZero();
        assertThat(response2.totalBugReports()).isZero();
    }

    @Test
    void listUsers_mapsAiTokenUsageFromGroupedQuery_userWithNoUsageDefaultsToZero() {
        Pageable pageable = PageRequest.of(0, 20);
        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(user1, user2), pageable, 2));

        OwnerAiUsage usage = mock(OwnerAiUsage.class);
        when(usage.getOwnerId()).thenReturn(user1.getId());
        when(usage.getTotalTokens()).thenReturn(1500L);
        when(usage.getCallCount()).thenReturn(3L);
        when(testGenerationEventRepository.sumUsageGroupedByOwnerIds(anyList(), any(), any()))
                .thenReturn(List.of(usage));

        PageResponse<AdminUserResponse> result = adminUserService.listUsers(pageable);

        AdminUserResponse response1 = result.data().stream()
                .filter(r -> r.id().equals(user1.getId())).findFirst().orElseThrow();
        assertThat(response1.aiTokensToday()).isEqualTo(1500);
        assertThat(response1.aiCallsToday()).isEqualTo(3);

        AdminUserResponse response2 = result.data().stream()
                .filter(r -> r.id().equals(user2.getId())).findFirst().orElseThrow();
        assertThat(response2.aiTokensToday()).isZero();
        assertThat(response2.aiCallsToday()).isZero();
    }

    @Test
    void setEnabled_lockingAnotherUser_succeeds_andWritesAuditEvent() {
        when(userRepository.findById(user1.getId())).thenReturn(Optional.of(user1));
        when(currentUserService.getCurrentUser()).thenReturn(admin);

        AdminUserResponse response = adminUserService.setEnabled(user1.getId(), false);

        assertThat(response.enabled()).isFalse();

        ArgumentCaptor<AdminAuditEvent> auditCaptor = ArgumentCaptor.forClass(AdminAuditEvent.class);
        verify(adminAuditEventRepository).save(auditCaptor.capture());
        AdminAuditEvent audit = auditCaptor.getValue();
        assertThat(audit.getAdminEmail()).isEqualTo(admin.getEmail());
        assertThat(audit.getTargetEmail()).isEqualTo(user1.getEmail());
        assertThat(audit.getAction()).isEqualTo(AdminAuditAction.USER_LOCKED);
    }

    @Test
    void setEnabled_unlockingUser_writesUnlockedAuditEvent() {
        user1.setEnabled(false);
        when(userRepository.findById(user1.getId())).thenReturn(Optional.of(user1));
        when(currentUserService.getCurrentUser()).thenReturn(admin);

        adminUserService.setEnabled(user1.getId(), true);

        ArgumentCaptor<AdminAuditEvent> auditCaptor = ArgumentCaptor.forClass(AdminAuditEvent.class);
        verify(adminAuditEventRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getAction()).isEqualTo(AdminAuditAction.USER_UNLOCKED);
    }

    @Test
    void setEnabled_lockingSelf_throwsInvalidRequest() {
        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(currentUserService.getCurrentUser()).thenReturn(admin);

        assertThatThrownBy(() -> adminUserService.setEnabled(admin.getId(), false))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void getUserDetail_notFound_throwsUserNotFound() {
        UUID missingId = UUID.randomUUID();
        when(userRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminUserService.getUserDetail(missingId))
                .isInstanceOf(UserNotFoundException.class);
    }
}
