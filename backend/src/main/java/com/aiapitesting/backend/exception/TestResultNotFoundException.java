package com.aiapitesting.backend.exception;

public class TestResultNotFoundException extends RuntimeException {
    public TestResultNotFoundException(String message) {
        super(message);
    }
}
