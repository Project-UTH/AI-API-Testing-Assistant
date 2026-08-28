package com.aiapitesting.backend.exception;

public class GoogleAuthFailedException extends RuntimeException {
    public GoogleAuthFailedException(String message) {
        super(message);
    }
}
