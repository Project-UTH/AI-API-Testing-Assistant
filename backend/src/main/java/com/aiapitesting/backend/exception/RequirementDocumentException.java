package com.aiapitesting.backend.exception;

import org.springframework.http.HttpStatus;

/** 17q G3.3 - lỗi tải/đọc tài liệu nghiệp vụ, mang sẵn mã lỗi + HTTP status (DOCUMENT_TOO_LARGE 413, DOCUMENT_TYPE_INVALID/ENCODING_INVALID 400, ...). */
public class RequirementDocumentException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public RequirementDocumentException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
