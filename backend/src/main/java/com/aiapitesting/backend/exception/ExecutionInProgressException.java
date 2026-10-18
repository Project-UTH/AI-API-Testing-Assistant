package com.aiapitesting.backend.exception;

/** Project đang có lần chạy test chưa xong - không được import lại OpenAPI (sẽ xoá dữ liệu giữa lúc đang chạy). */
public class ExecutionInProgressException extends RuntimeException {
    public ExecutionInProgressException(String message) {
        super(message);
    }
}
