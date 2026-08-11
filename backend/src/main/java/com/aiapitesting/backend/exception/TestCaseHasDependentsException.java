package com.aiapitesting.backend.exception;

public class TestCaseHasDependentsException extends RuntimeException {
    public TestCaseHasDependentsException(String message) {
        super(message);
    }
}
