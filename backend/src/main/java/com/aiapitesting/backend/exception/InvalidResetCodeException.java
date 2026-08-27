package com.aiapitesting.backend.exception;

public class InvalidResetCodeException extends RuntimeException {
    public InvalidResetCodeException(String message) {
        super(message);
    }
}
