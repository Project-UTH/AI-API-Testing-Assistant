package com.aiapitesting.backend.service.job;

/** Người dùng đã huỷ job; ném ở ranh giới an toàn, được GenerationJobService bắt để chuyển job sang CANCELLED (không phải lỗi). */
public class JobCancelledException extends RuntimeException {
    public JobCancelledException() {
        super("Job đã bị huỷ");
    }
}
