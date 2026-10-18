package com.aiapitesting.backend.exception;

import com.aiapitesting.backend.dto.response.ApiErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unknownRoute_is404NotFoundNotInternalError() {
        ResponseEntity<ApiErrorResponse> response = handler.handleNoResource(
                new NoResourceFoundException(HttpMethod.GET, "/api/v1/khong-ton-tai", "khong-ton-tai"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void malformedPathVariable_is400ValidationErrorNamingTheParameter() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "not-a-uuid", java.util.UUID.class, "bugReportId", null, new IllegalArgumentException("bad"));

        ResponseEntity<ApiErrorResponse> response = handler.handleTypeMismatch(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().message()).contains("bugReportId");
    }

    @Test
    void missingRequestParameter_is400ValidationError() {
        ResponseEntity<ApiErrorResponse> response = handler.handleMissingParameter(
                new MissingServletRequestParameterException("url", "String"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void wrongHttpMethod_is405() {
        ResponseEntity<ApiErrorResponse> response = handler.handleMethodNotAllowed(
                new HttpRequestMethodNotSupportedException("PATCH"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody().code()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void wrongContentType_is415() {
        ResponseEntity<ApiErrorResponse> response = handler.handleUnsupportedMediaType(
                new HttpMediaTypeNotSupportedException("text/plain"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody().code()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void trulyUnexpectedException_stillInternalError() {
        ResponseEntity<ApiErrorResponse> response = handler.handleGeneric(new IllegalStateException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
    }
}
