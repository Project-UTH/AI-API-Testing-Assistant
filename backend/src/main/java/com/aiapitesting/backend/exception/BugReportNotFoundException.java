package com.aiapitesting.backend.exception;

public class BugReportNotFoundException extends RuntimeException {
    public BugReportNotFoundException(String message) {
        super(message);
    }
}
