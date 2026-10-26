package com.mutantapi.web;

import com.mutantapi.mutant.MutantFlag;
import com.mutantapi.mutant.Mutants;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private final Mutants mutants;

    public GlobalExceptionHandler(Mutants mutants) {
        this.mutants = mutants;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> handleApi(ApiException e, HttpServletRequest request) {
        Map<String, Object> body = body(e.code(), e.getMessage());
        if (!e.details().isEmpty()) {
            body.put("details", e.details());
        }
        // M22: loi 400 cua POST /api/jobs ro ri thong tin noi bo (ten class, cau SQL).
        if (e.status() == 400 && mutants.on(MutantFlag.M22) && "POST".equals(request.getMethod())
                && "/api/jobs".equals(request.getRequestURI())) {
            body.put("debug", "com.mutantapi.web.JobController.create(JobController.java:57) | SELECT * FROM jobs WHERE owner_id = ?");
        }
        return ResponseEntity.status(e.status()).body(body);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestValueException.class})
    ResponseEntity<Map<String, Object>> handleBadBody(Exception e) {
        return ResponseEntity.badRequest().body(body("INVALID_BODY", "Body khong phai JSON hop le"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        // H04 (held-out): o nhanh GET /api/jobs/{id}, loi sai kieu tham so duong dan khong duoc xu ly rieng -> roi vao 500.
        if (mutants.on(MutantFlag.H04) && "GET".equals(request.getMethod())
                && request.getRequestURI().matches("^/api/jobs/[^/]+$")) {
            return ResponseEntity.status(500).body(body("INTERNAL_ERROR", "Loi he thong"));
        }
        return ResponseEntity.badRequest().body(body("TYPE_MISMATCH", "Tham so '" + e.getName() + "' sai kieu du lieu"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        // Loi chuan cua Spring (404 khong co route, 405, 415...) giu dung ma HTTP cua no - neu bien tat ca thanh 500
        // thi se nham thanh mutant "404 thanh 500" (M12) va lam nhieu phep do.
        if (e instanceof org.springframework.web.ErrorResponse er) {
            int status = er.getStatusCode().value();
            return ResponseEntity.status(status).body(body("HTTP_" + status, "Yeu cau khong hop le"));
        }
        return ResponseEntity.status(500).body(body("INTERNAL_ERROR", "Loi he thong"));
    }

    private Map<String, Object> body(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return body;
    }
}
