package com.aiapitesting.backend.exception;

public class TestExecutionNotFoundException extends RuntimeException {
    public TestExecutionNotFoundException(String message) {
        super(message);
    }
}
