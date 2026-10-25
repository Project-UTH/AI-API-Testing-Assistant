package com.shoptarget.exception;

import com.shoptarget.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ProductNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of("PRODUCT_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse.of("INVALID_CREDENTIALS", e.getMessage()));
    }

    @ExceptionHandler(com.shoptarget.exception.ValidationFailedException.class)
    public ResponseEntity<ErrorResponse> handleManualValidation(ValidationFailedException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of("VALIDATION_ERROR", e.getMessage()));
    }

    /** Body không phải JSON hợp lệ: bản chuẩn trả 400; mutant S15 trả 500. */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(org.springframework.http.converter.HttpMessageNotReadableException e) {
        HttpStatus status = com.shoptarget.mutant.ShopMutants.on("S15") ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(ErrorResponse.of("MALFORMED_JSON", "Body khong phai JSON hop le"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .orElse("Du lieu khong hop le");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of("VALIDATION_ERROR", message));
    }

    // Loi convert query/path param (vd @RequestParam BigDecimal minPrice nhan "abc") - khong bat
    // handler nay thi request bi dispatch sang /error, dung ngoai vong permitAll cua SecurityConfig
    // nen tra ve 403 rong thay vi 400 dung chuan.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String requiredType = e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "kieu du lieu hop le";
        String message = e.getName() + ": gia tri '" + e.getValue() + "' khong the chuyen doi thanh " + requiredType;
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of("TYPE_MISMATCH", message));
    }
}
