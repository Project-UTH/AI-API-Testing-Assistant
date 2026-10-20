package com.aiapitesting.backend.service.execution;

import com.aiapitesting.backend.entity.ExecutionStatus;
import com.aiapitesting.backend.repository.TestExecutionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ExecutionStartupCleanerTest {

    @Mock
    private TestExecutionRepository testExecutionRepository;

    @InjectMocks
    private ExecutionStartupCleaner cleaner;

    @Test
    void markAbandonedExecutionsAsFailed_onlyTouchesPendingAndRunning() {
        cleaner.markAbandonedExecutionsAsFailed();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<ExecutionStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(testExecutionRepository).markUnfinishedAsFailed(statuses.capture(), any(Instant.class));
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(ExecutionStatus.PENDING, ExecutionStatus.RUNNING);
    }
}
