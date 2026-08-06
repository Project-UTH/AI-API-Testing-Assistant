package com.aiapitesting.backend.exception;

public class EndpointNotFoundException extends RuntimeException {
    public EndpointNotFoundException(String message) {
        super(message);
    }
}
