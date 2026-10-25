package com.shoptarget.exception;

/** Vi phạm ràng buộc dữ liệu (thay cho MethodArgumentNotValidException vì validation chạy thủ công trong controller). */
public class ValidationFailedException extends RuntimeException {
    public ValidationFailedException(String message) {
        super(message);
    }
}
