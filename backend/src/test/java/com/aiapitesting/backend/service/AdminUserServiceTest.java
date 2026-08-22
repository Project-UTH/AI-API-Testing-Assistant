package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.response.AdminUserResponse;
import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.entity.User;
import com.aiapitesting.backend.entity.UserRole;
import com.aiapitesting.backend.exception.InvalidRequestException;
import com.aiapitesting.backend.exception.UserNotFoundException;
import com.aiapitesting.backend.repository.BugReportRepository;
import com.aiapitesting.backend.repository.BugReportRepository.OwnerBugReportCount;
import com.aiapitesting.backend.repository.ProjectRepository;
import com.aiapitesting.backend.repository.ProjectRepository.OwnerCount;
import com.aiapitesting.backend.repository.TestCaseRepository;
import com.aiapitesting.backend.repository.TestCaseRepository.OwnerTestCaseCount;
import com.aiapitesting.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
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
    void setEnabled_lockingAnotherUser_succeeds() {
        when(userRepository.findById(user1.getId())).thenReturn(Optional.of(user1));
        when(currentUserService.getCurrentUser()).thenReturn(admin);

        AdminUserResponse response = adminUserService.setEnabled(user1.getId(), false);

        assertThat(response.enabled()).isFalse();
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
