package com.aiapitesting.backend.exception;

import com.aiapitesting.backend.dto.response.ApiErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponse> handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of("EMAIL_ALREADY_EXISTS", ex.getMessage()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiErrorResponse.of("INVALID_CREDENTIALS", ex.getMessage()));
    }

    @ExceptionHandler(ProjectNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleProjectNotFound(ProjectNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of("PROJECT_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiErrorResponse> handleForbidden(ForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiErrorResponse.of("FORBIDDEN", ex.getMessage()));
    }

    @ExceptionHandler(SwaggerParseException.class)
    public ResponseEntity<ApiErrorResponse> handleSwaggerParseFailed(SwaggerParseException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(ApiErrorResponse.of("SWAGGER_PARSE_FAILED", ex.getMessage()));
    }

    @ExceptionHandler(EndpointNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleEndpointNotFound(EndpointNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of("ENDPOINT_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(AiGenerationFailedException.class)
    public ResponseEntity<ApiErrorResponse> handleAiGenerationFailed(AiGenerationFailedException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiErrorResponse.of("AI_GENERATION_FAILED", ex.getMessage()));
    }

    @ExceptionHandler(TestCaseNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleTestCaseNotFound(TestCaseNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of("TEST_CASE_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(TestExecutionNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleTestExecutionNotFound(TestExecutionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of("TEST_EXECUTION_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(TestCaseHasDependentsException.class)
    public ResponseEntity<ApiErrorResponse> handleTestCaseHasDependents(TestCaseHasDependentsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of("TEST_CASE_HAS_DEPENDENTS", ex.getMessage()));
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(InvalidRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("VALIDATION_ERROR", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("Dữ liệu đầu vào không hợp lệ");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(Exception ex) {
        // Log lỗi không xác định trước để còn chẩn đoán - trước đây bị nuốt hoàn toàn, không có
        // dấu vết gì trong log khi 1 lỗi hệ thống thật xảy ra.
        log.error("Loi he thong khong xac dinh", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of("INTERNAL_ERROR", "Đã xảy ra lỗi hệ thống, vui lòng thử lại sau"));
    }
}
