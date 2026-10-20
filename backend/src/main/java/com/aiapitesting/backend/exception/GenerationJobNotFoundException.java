package com.aiapitesting.backend.exception;

/** Không có job sinh test với id đó trong project của người gọi (cũng dùng khi job thuộc project khác, để không lộ sự tồn tại). */
public class GenerationJobNotFoundException extends RuntimeException {
    public GenerationJobNotFoundException(String message) {
        super(message);
    }
}
