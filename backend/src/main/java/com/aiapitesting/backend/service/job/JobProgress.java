package com.aiapitesting.backend.service.job;

import java.util.UUID;

/**
 * Cổng báo tiến độ mà các bước sinh test gọi để người dùng GIÁM SÁT được việc HỆ THỐNG và AI đang làm, và để HUỶ giữa chừng.
 * {@link #NOOP} dùng khi gọi trực tiếp không qua job (các test cũ và luồng đồng bộ cũ vẫn chạy y nguyên).
 *
 * <p>Huỷ là HỢP TÁC: chỉ kiểm tra ở ranh giới an toàn (giữa các bước/vòng), không bao giờ giữa lúc đang ghi test hoặc khoá test gốc.
 */
public interface JobProgress {

    /** Không làm gì: không ghi bước, không huỷ, không giới hạn token (trần mặc định do bên gọi tự áp dụng). */
    JobProgress NOOP = new JobProgress() {
        @Override
        public Step start(UUID endpointId, String endpointLabel, String phase, String actor, String title) {
            return Step.NOOP;
        }

        @Override
        public void checkCancelled() {
        }

        @Override
        public int addTokens(int tokens) {
            return tokens;
        }

        @Override
        public int tokensUsed() {
            return 0;
        }

        @Override
        public int tokenCap() {
            return 0;
        }

        @Override
        public boolean isDryRun() {
            return false;
        }
    };

    /** Bắt đầu 1 bước (hiện ngay ở giao diện với trạng thái RUNNING). */
    Step start(UUID endpointId, String endpointLabel, String phase, String actor, String title);

    /** Ném {@link JobCancelledException} nếu người dùng đã yêu cầu huỷ. */
    void checkCancelled();

    /** Cộng token AI vừa dùng vào job; trả về tổng. Không ném lỗi khi vượt trần - bên gọi tự kiểm bằng {@link #tokenCap()}. */
    int addTokens(int tokens);

    int tokensUsed();

    /** 0 = không đặt trần riêng cho job. */
    int tokenCap();

    /** Chạy thử: chỉ ghi các bước 'sẽ làm gì', KHÔNG gọi AI, KHÔNG gọi API đích, KHÔNG ghi test. */
    boolean isDryRun();

    default boolean overCap() {
        return tokenCap() > 0 && tokensUsed() >= tokenCap();
    }

    /** Điều khiển 1 bước đang chạy. */
    interface Step {
        Step NOOP = (status, detail, httpStatus, tokens) -> {
        };

        void finish(String status, String detail, Integer httpStatus, Integer tokens);

        default void ok(String detail) {
            finish("OK", detail, null, null);
        }

        default void fail(String detail) {
            finish("FAILED", detail, null, null);
        }

        default void skipped(String detail) {
            finish("SKIPPED", detail, null, null);
        }
    }
}
