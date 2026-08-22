package com.aiapitesting.backend.exception;

public class AiQuotaExceededException extends RuntimeException {
    public AiQuotaExceededException(String message) {
        super(message);
    }
}
