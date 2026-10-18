package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.repository.TestExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;

/**
 * Lần chạy test thực thi trong bộ nhớ (@Async) nên server tắt/restart giữa chừng để lại bản ghi PENDING/RUNNING mãi mãi -
 * và từ khi import OpenAPI bị chặn khi project còn lần chạy chưa xong, bản ghi kẹt đó sẽ khoá import vĩnh viễn. Lúc khởi
 * động chưa có lần chạy nào đang thực sự chạy (ứng dụng chạy 1 node), nên mọi bản ghi chưa xong đều là bỏ dở -> FAILED.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExecutionStartupCleaner {

    private final TestExecutionRepository testExecutionRepository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void markAbandonedExecutionsAsFailed() {
        int count = testExecutionRepository.markUnfinishedAsFailed(
                EnumSet.of(ExecutionStatus.PENDING, ExecutionStatus.RUNNING), Instant.now());
        if (count > 0) {
            log.warn("Da danh dau {} lan chay bi bo do (server tat giua chung) la FAILED", count);
        }
    }
}
