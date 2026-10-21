package com.mutantapi.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO CHI de khai bao OpenAPI (rang buoc + mo ta). Controller doc JSON bang tay (xem Input) de mutant tat duoc
 * tung buoc kiem tra; hanh vi chuan phai khop khai bao o day - co test doi chieu (SpecMatchesBehaviourTest).
 */
public final class Dtos {

    private Dtos() {
    }

    public record LoginRequest(
            @NotBlank @Email @Schema(minLength = 1) String email,
            @NotBlank @Schema(minLength = 1) String password) {
    }

    public record LoginResponse(String token, String role, String expiresAt) {
    }

    public record UserResponse(Long id, String email, String fullName, String role) {
    }

    public record JobRequest(
            @NotBlank @Size(min = 1, max = 200) @Schema(description = "Tieu de tin tuyen dung") String title,
            @NotBlank @Schema(minLength = 1, description = "Mo ta cong viec") String description,
            @DecimalMin("0") @Schema(description = "Luong toi thieu, phai <= salaryMax neu co ca hai") BigDecimal salaryMin,
            @DecimalMin("0") @Schema(description = "Luong toi da") BigDecimal salaryMax,
            @Size(min = 3, max = 3) @Schema(description = "Ma tien te 3 ky tu, vd VND") String salaryCurrency,
            @NotNull @Schema(allowableValues = {"FULL_TIME", "PART_TIME", "INTERN"}) String jobType,
            @NotNull @Schema(type = "string", format = "date", description = "Han nop ho so, khong duoc o qua khu") String deadline) {
    }

    public record JobResponse(
            Long id, Long ownerId, String title, String description, BigDecimal salaryMin, BigDecimal salaryMax,
            String salaryCurrency, String jobType, String deadline, String status) {
    }

    public record CriterionRequest(
            @NotBlank @Size(min = 1, max = 200) String name,
            @NotNull @DecimalMin("0.01") @DecimalMax("100") @Schema(description = "Trong so (%) cua tieu chi") BigDecimal weight,
            @NotNull @Min(1) @Schema(description = "Diem toi da cua tieu chi") Integer maxScore) {
    }

    public record RubricRequest(
            @NotEmpty @Size(max = 20) @Schema(description = "Danh sach tieu chi, tong weight phai bang 100") List<CriterionRequest> criteria) {
    }

    public record RubricResponse(Long jobId, BigDecimal totalWeight, List<CriterionRequest> criteria) {
    }

    public record ApplicationRequest(
            @Size(max = 2000) String coverLetter,
            @NotNull @Schema(description = "Ung vien dong y cho phep phan tich ho so, phai la true") Boolean consent) {
    }

    public record ApplicationResponse(Long id, Long jobId, Long candidateId, String coverLetter, String status) {
    }

    public record StatusRequest(
            @NotNull @Schema(allowableValues = {"INTERVIEW_INVITED", "HIRED", "REJECTED"},
                    description = "PENDING->INTERVIEW_INVITED|REJECTED; INTERVIEW_INVITED->HIRED|REJECTED") String status) {
    }

    public record ErrorResponse(String code, String message, List<String> details) {
    }
}
