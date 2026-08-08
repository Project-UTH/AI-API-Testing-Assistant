package com.aiapitesting.backend.exception;

public class AiGenerationFailedException extends RuntimeException {
    public AiGenerationFailedException(String message) {
        super(message);
    }
}
