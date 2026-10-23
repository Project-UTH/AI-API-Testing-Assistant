package com.aiapitesting.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * 17q G3 - lỗi nghiệp vụ của luật nghiệp vụ, mang sẵn mã lỗi + HTTP status (1 lớp thay vì 6 lớp gần giống nhau). Mã lỗi:
 * BUSINESS_RULE_NOT_FOUND (404), BUSINESS_RULE_INVALID (400), BUSINESS_RULE_DUPLICATES_SPEC (409), BUSINESS_RULE_ALREADY_EXISTS (409),
 * BUSINESS_RULE_STATE_INVALID (409), BUSINESS_RULE_NOT_DELETABLE (409).
 */
public class BusinessRuleException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public BusinessRuleException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public static BusinessRuleException notFound() {
        return new BusinessRuleException("BUSINESS_RULE_NOT_FOUND", HttpStatus.NOT_FOUND, "Không tìm thấy luật nghiệp vụ với id đã cho");
    }

    public static BusinessRuleException invalid(String message) {
        return new BusinessRuleException("BUSINESS_RULE_INVALID", HttpStatus.BAD_REQUEST, message);
    }

    public static BusinessRuleException duplicatesSpec(String message) {
        return new BusinessRuleException("BUSINESS_RULE_DUPLICATES_SPEC", HttpStatus.CONFLICT, message);
    }

    public static BusinessRuleException alreadyExists() {
        return new BusinessRuleException("BUSINESS_RULE_ALREADY_EXISTS", HttpStatus.CONFLICT,
                "Endpoint này đã có luật giống hệt (cùng mẫu và tham số) - kể cả luật đã loại");
    }

    public static BusinessRuleException stateInvalid(String message) {
        return new BusinessRuleException("BUSINESS_RULE_STATE_INVALID", HttpStatus.CONFLICT, message);
    }

    public static BusinessRuleException notDeletable() {
        return new BusinessRuleException("BUSINESS_RULE_NOT_DELETABLE", HttpStatus.CONFLICT,
                "Chỉ xoá được luật bạn tự nhập và chưa từng duyệt - luật khác dùng \"Loại\" (giữ lại để đo độ chính xác)");
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
