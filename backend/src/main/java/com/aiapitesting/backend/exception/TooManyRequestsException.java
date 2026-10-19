package com.aiapitesting.backend.exception;

/** Vượt giới hạn tần suất (HTTP 429) - dùng cho endpoint CI (Module 17 - 17d). */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}
