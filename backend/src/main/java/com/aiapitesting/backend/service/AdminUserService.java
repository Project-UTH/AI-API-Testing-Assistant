package com.aiapitesting.backend.service;

import com.aiapitesting.backend.dto.response.AdminUserResponse;
import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.entity.AdminAuditAction;
import com.aiapitesting.backend.entity.AdminAuditEvent;
import com.aiapitesting.backend.entity.User;
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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Quản lý user cho trang Admin (Module 11) - đọc/khoá-mở tài khoản. KHÔNG có method đổi role:
 * quyền ADMIN chỉ cấp được bằng SQL trực tiếp trên DB (xem UserRole), cố ý không có API nào ở đây
 * hay ở bất kỳ đâu trong hệ thống cho phép leo quyền qua HTTP.
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final TestCaseRepository testCaseRepository;
    private final BugReportRepository bugReportRepository;
    private final TestGenerationEventRepository testGenerationEventRepository;
    private final AdminAuditEventRepository adminAuditEventRepository;
    private final CurrentUserService currentUserService;

    public PageResponse<AdminUserResponse> listUsers(Pageable pageable) {
        Page<User> page = userRepository.findAll(pageable);
        List<UUID> userIds = page.getContent().stream().map(User::getId).toList();

        Map<UUID, Long> projectCounts = toMap(projectRepository.countGroupedByOwnerIds(userIds), OwnerCount::getOwnerId, OwnerCount::getCount);
        Map<UUID, Long> testCaseCounts = toMap(testCaseRepository.countGroupedByOwnerIds(userIds), OwnerTestCaseCount::getOwnerId, OwnerTestCaseCount::getCount);
        Map<UUID, Long> bugReportCounts = toMap(bugReportRepository.countGroupedByOwnerIds(userIds), OwnerBugReportCount::getOwnerId, OwnerBugReportCount::getCount);
        Instant startOfToday = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant startOfTomorrow = startOfToday.plus(1, ChronoUnit.DAYS);
        List<OwnerAiUsage> aiUsageToday =
                testGenerationEventRepository.sumUsageGroupedByOwnerIds(userIds, startOfToday, startOfTomorrow);
        Map<UUID, Long> aiTokensToday = toMap(aiUsageToday, OwnerAiUsage::getOwnerId, OwnerAiUsage::getTotalTokens);
        Map<UUID, Long> aiCallsToday = toMap(aiUsageToday, OwnerAiUsage::getOwnerId, OwnerAiUsage::getCallCount);

        return PageResponse.from(page, user -> AdminUserResponse.from(
                user,
                projectCounts.getOrDefault(user.getId(), 0L),
                testCaseCounts.getOrDefault(user.getId(), 0L),
                bugReportCounts.getOrDefault(user.getId(), 0L),
                aiTokensToday.getOrDefault(user.getId(), 0L),
                aiCallsToday.getOrDefault(user.getId(), 0L)));
    }

    public AdminUserResponse getUserDetail(UUID id) {
        User user = getUser(id);
        return buildResponse(user);
    }

    public AdminUserResponse setEnabled(UUID id, boolean enabled) {
        User user = getUser(id);
        User currentAdmin = currentUserService.getCurrentUser();
        if (!enabled && user.getId().equals(currentAdmin.getId())) {
            throw new InvalidRequestException("Không thể tự khoá tài khoản của chính mình");
        }

        user.setEnabled(enabled);
        userRepository.save(user);

        adminAuditEventRepository.save(AdminAuditEvent.builder()
                .adminEmail(currentAdmin.getEmail())
                .targetEmail(user.getEmail())
                .action(enabled ? AdminAuditAction.USER_UNLOCKED : AdminAuditAction.USER_LOCKED)
                .build());

        return buildResponse(user);
    }

    private AdminUserResponse buildResponse(User user) {
        Instant startOfToday = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant startOfTomorrow = startOfToday.plus(1, ChronoUnit.DAYS);
        List<OwnerAiUsage> aiUsageToday = testGenerationEventRepository.sumUsageGroupedByOwnerIds(
                List.of(user.getId()), startOfToday, startOfTomorrow);
        long aiTokensToday = aiUsageToday.isEmpty() ? 0L : aiUsageToday.get(0).getTotalTokens();
        long aiCallsToday = aiUsageToday.isEmpty() ? 0L : aiUsageToday.get(0).getCallCount();

        return AdminUserResponse.from(
                user,
                projectRepository.countByOwner(user),
                testCaseRepository.countByEndpointProjectOwner(user),
                bugReportRepository.countByProjectOwner(user),
                aiTokensToday,
                aiCallsToday);
    }

    private User getUser(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("Không tìm thấy người dùng với id đã cho"));
    }

    private <T> Map<UUID, Long> toMap(List<T> rows, Function<T, UUID> keyFn, Function<T, Long> valueFn) {
        return rows.stream().collect(Collectors.toMap(keyFn, valueFn));
    }
}
