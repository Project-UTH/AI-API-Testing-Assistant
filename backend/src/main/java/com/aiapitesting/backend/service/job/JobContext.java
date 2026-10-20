package com.aiapitesting.backend.service.job;

import java.util.UUID;

/** {@link JobProgress} gắn với 1 job thật: ghi bước qua {@link JobStepRecorder}, huỷ bằng cờ trong DB, cộng token vào job. */
public class JobContext implements JobProgress {

    private final UUID jobId;
    private final JobStepRecorder recorder;
    private final boolean dryRun;
    private final int tokenCap;
    private volatile int tokensUsed;

    public JobContext(UUID jobId, JobStepRecorder recorder, boolean dryRun, int tokenCap, int tokensUsedSoFar) {
        this.jobId = jobId;
        this.recorder = recorder;
        this.dryRun = dryRun;
        this.tokenCap = tokenCap;
        this.tokensUsed = tokensUsedSoFar;
    }

    @Override
    public Step start(UUID endpointId, String endpointLabel, String phase, String actor, String title) {
        UUID stepId = recorder.startStep(jobId, endpointId, endpointLabel, phase, actor, title);
        return (status, detail, httpStatus, tokens) -> recorder.finishStep(stepId, status, detail, httpStatus, tokens);
    }

    @Override
    public void checkCancelled() {
        if (recorder.isCancelRequested(jobId)) {
            throw new JobCancelledException();
        }
    }

    @Override
    public int addTokens(int tokens) {
        tokensUsed = recorder.addTokens(jobId, tokens);
        return tokensUsed;
    }

    @Override
    public int tokensUsed() {
        return tokensUsed;
    }

    @Override
    public int tokenCap() {
        return tokenCap;
    }

    @Override
    public boolean isDryRun() {
        return dryRun;
    }
}
