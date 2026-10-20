package com.aiapitesting.backend.service.job;

import com.aiapitesting.backend.entity.GenerationJob;
import com.aiapitesting.backend.entity.GenerationJobStep;
import com.aiapitesting.backend.repository.GenerationJobRepository;
import com.aiapitesting.backend.repository.GenerationJobStepRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Ghi tiến độ job bằng transaction RIÊNG (REQUIRES_NEW) và commit NGAY, để request polling của giao diện thấy được từng bước vừa xong dù luồng
 * job còn đang chạy. Là bean riêng (không gọi nội bộ) để proxy @Transactional có hiệu lực.
 */
@Service
@RequiredArgsConstructor
public class JobStepRecorder {

    private static final int DETAIL_LIMIT = 4000;

    private final GenerationJobRepository jobs;
    private final GenerationJobStepRepository steps;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID startStep(UUID jobId, UUID endpointId, String endpointLabel, String phase, String actor, String title) {
        GenerationJobStep step = GenerationJobStep.builder()
                .jobId(jobId)
                .seq(steps.maxSeq(jobId) + 1)
                .endpointId(endpointId)
                .endpointLabel(truncate(endpointLabel, 200))
                .phase(truncate(phase, 20))
                .actor(truncate(actor, 10))
                .status(GenerationJobStep.RUNNING)
                .title(truncate(title, 200))
                .startedAt(Instant.now())
                .build();
        return steps.save(step).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishStep(UUID stepId, String status, String detail, Integer httpStatus, Integer tokens) {
        steps.findById(stepId).ifPresent(step -> {
            step.setStatus(status);
            step.setDetail(truncate(detail, DETAIL_LIMIT));
            step.setHttpStatus(httpStatus);
            step.setTokens(tokens);
            step.setFinishedAt(Instant.now());
            steps.save(step);
        });
    }

    /** Job kết thúc mà còn bước RUNNING (huỷ/lỗi giữa chừng) thì đóng lại, để giao diện không quay mãi. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int closeRunningSteps(UUID jobId, String status, String detail) {
        int n = 0;
        for (GenerationJobStep step : steps.findForPolling(jobId, Integer.MAX_VALUE)) {
            if (GenerationJobStep.RUNNING.equals(step.getStatus())) {
                step.setStatus(status);
                step.setDetail(truncate(step.getDetail() == null || step.getDetail().isBlank() ? detail : step.getDetail(), DETAIL_LIMIT));
                step.setFinishedAt(Instant.now());
                steps.save(step);
                n++;
            }
        }
        return n;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean isCancelRequested(UUID jobId) {
        return jobs.findById(jobId).map(GenerationJob::isCancelRequested).orElse(false);
    }

    /** Cộng token vào job và trả về tổng mới. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int addTokens(UUID jobId, int tokens) {
        if (tokens > 0) {
            jobs.addTokens(jobId, tokens);
        }
        return jobs.findById(jobId).map(GenerationJob::getTokensUsed).orElse(tokens);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }
}
