package com.aiapitesting.backend.exception;

public class TestCaseNotFoundException extends RuntimeException {
    public TestCaseNotFoundException(String message) {
        super(message);
    }
}
