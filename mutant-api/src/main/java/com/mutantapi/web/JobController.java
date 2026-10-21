package com.mutantapi.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.mutantapi.mutant.MutantFlag;
import com.mutantapi.mutant.Mutants;
import com.mutantapi.security.AuthFilter;
import com.mutantapi.security.AuthUser;
import com.mutantapi.store.Store;
import com.mutantapi.web.dto.Dtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/jobs")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Jobs")
public class JobController {

    private static final Set<String> JOB_TYPES = Set.of("FULL_TIME", "PART_TIME", "INTERN");
    private static final int NO_LIMIT = Integer.MAX_VALUE;

    private final Store store;
    private final Mutants m;

    public JobController(Store store, Mutants mutants) {
        this.store = store;
        this.m = mutants;
    }

    private record Parsed(String title, String description, BigDecimal salaryMin, BigDecimal salaryMax,
                          String currency, String jobType, LocalDate deadline) {
    }

    @PostMapping
    @Operation(summary = "Tao tin tuyen dung (chi HR)")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = Dtos.JobRequest.class)))
    @ApiResponses({
            @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = Dtos.JobResponse.class))),
            @ApiResponse(responseCode = "400", description = "Du lieu khong hop le"),
            @ApiResponse(responseCode = "401", description = "Thieu hoac sai token"),
            @ApiResponse(responseCode = "403", description = "Khong phai HR")})
    public ResponseEntity<Dtos.JobResponse> create(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthUser auth, @RequestBody JsonNode body) {
        if (!auth.isHr() && !m.on(MutantFlag.M14)) {
            throw ApiException.forbidden("Chi HR duoc tao tin tuyen dung");
        }
        Parsed p = parse(body);
        Store.Job job = new Store.Job();
        job.ownerId = auth.id();
        // M19: nhan ownerId tu body - HR (hoac ung vien khi M14) gan chu so huu tuy y.
        if (m.on(MutantFlag.M19) && body.has("ownerId") && body.get("ownerId").isIntegralNumber()) {
            job.ownerId = body.get("ownerId").longValue();
        }
        apply(job, p);
        store.saveJob(job);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(job));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Xem 1 tin tuyen dung theo id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Dtos.JobResponse.class))),
            @ApiResponse(responseCode = "401", description = "Thieu hoac sai token"),
            @ApiResponse(responseCode = "404", description = "Khong tim thay tin")})
    public Dtos.JobResponse get(@PathVariable long id) {
        return toResponse(find(id, true, m.on(MutantFlag.M33))); // M33: tin da xoa mem van xem duoc
    }

    @PutMapping("/{id}")
    @Operation(summary = "Sua tin tuyen dung (chi HR chu so huu)")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = Dtos.JobRequest.class)))
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Dtos.JobResponse.class))),
            @ApiResponse(responseCode = "400", description = "Du lieu khong hop le"),
            @ApiResponse(responseCode = "401", description = "Thieu hoac sai token"),
            @ApiResponse(responseCode = "403", description = "Khong phai HR chu so huu"),
            @ApiResponse(responseCode = "404", description = "Khong tim thay tin")})
    public Dtos.JobResponse update(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthUser auth, @PathVariable long id, @RequestBody JsonNode body) {
        Store.Job job = find(id, false, m.on(MutantFlag.M34)); // M34: tin da xoa mem van sua duoc
        if (!auth.isHr()) {
            throw ApiException.forbidden("Chi HR duoc sua tin tuyen dung");
        }
        if (job.ownerId != auth.id() && !m.on(MutantFlag.M15)) {
            throw ApiException.forbidden("Chi HR chu so huu duoc sua tin nay");
        }
        Parsed p = parse(body);
        apply(job, p);
        // M20: nhan status tu body (truong khong khai bao) - doi trang thai tin ngoai y muon.
        if (m.on(MutantFlag.M20) && body.has("status") && body.get("status").isTextual()) {
            job.status = body.get("status").asText();
        }
        store.saveJob(job);
        return toResponse(job);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Xoa mem tin tuyen dung (chi HR chu so huu)")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Da xoa"),
            @ApiResponse(responseCode = "401", description = "Thieu hoac sai token"),
            @ApiResponse(responseCode = "403", description = "Khong phai HR chu so huu"),
            @ApiResponse(responseCode = "404", description = "Khong tim thay tin")})
    public ResponseEntity<Void> delete(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthUser auth, @PathVariable long id) {
        Store.Job job = find(id, false, m.on(MutantFlag.M37)); // M37: xoa tin da xoa roi van 204
        // M35: HR khac xoa duoc tin cua HR khac.
        if (!auth.isHr() || (job.ownerId != auth.id() && !m.on(MutantFlag.M35))) {
            throw ApiException.forbidden("Chi HR chu so huu duoc xoa tin nay");
        }
        job.status = "DELETED";
        store.saveJob(job);
        // M36: xoa thanh cong tra 200 thay vi 204.
        return m.on(MutantFlag.M36) ? ResponseEntity.ok().build() : ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/rubric")
    @Operation(summary = "Dat bo tieu chi danh gia cho tin (chi HR chu so huu); tong weight phai bang 100")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = Dtos.RubricRequest.class)))
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Dtos.RubricResponse.class))),
            @ApiResponse(responseCode = "400", description = "Du lieu khong hop le hoac tong weight khac 100"),
            @ApiResponse(responseCode = "401", description = "Thieu hoac sai token"),
            @ApiResponse(responseCode = "403", description = "Khong phai HR chu so huu"),
            @ApiResponse(responseCode = "404", description = "Khong tim thay tin")})
    public Dtos.RubricResponse putRubric(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthUser auth, @PathVariable long id, @RequestBody JsonNode body) {
        Store.Job job = find(id, false, false);
        if (!auth.isHr() || job.ownerId != auth.id()) {
            throw ApiException.forbidden("Chi HR chu so huu duoc dat rubric");
        }
        Input in = Input.of(body);
        JsonNode criteria = in.raw("criteria");
        List<Store.Criterion> parsed = new ArrayList<>();
        if (criteria == null || !criteria.isArray() || criteria.isEmpty() || criteria.size() > 20) {
            in.addError("criteria phai la mang tu 1 den 20 tieu chi");
        } else {
            for (int i = 0; i < criteria.size(); i++) {
                Input c = in.child(criteria.get(i), "criteria[" + i + "]");
                if (c == null) {
                    continue;
                }
                String name = c.string("name", true, true, 1, 200);
                // M08: weight = 0 van duoc nhan (bien duoi 0.01 thanh 0).
                BigDecimal weight = c.decimal("weight", true, m.on(MutantFlag.M08) ? BigDecimal.ZERO : new BigDecimal("0.01"), new BigDecimal("100"));
                // M09: maxScore = 0 van duoc nhan (bien duoi 1 thanh 0).
                Integer maxScore = c.integer("maxScore", true, m.on(MutantFlag.M09) ? 0 : 1);
                if (name != null && weight != null && maxScore != null) {
                    parsed.add(new Store.Criterion(name, weight, maxScore));
                }
            }
        }
        // M13: du lieu sai tra 401 thay vi 400.
        in.check(m.on(MutantFlag.M13) ? 401 : 400);

        BigDecimal total = parsed.stream().map(Store.Criterion::weight).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(new BigDecimal("100")) != 0 && !m.on(MutantFlag.M25)) {
            throw new ApiException(400, "RUBRIC_WEIGHT_SUM_INVALID", "Tong weight phai bang 100, hien la " + total.toPlainString());
        }
        job.rubric = parsed;
        store.saveJob(job);
        List<Dtos.CriterionRequest> echoed = parsed.stream()
                .map(c -> new Dtos.CriterionRequest(c.name(), c.weight(), c.maxScore())).toList();
        return new Dtos.RubricResponse(job.id, total, echoed);
    }

    // ---- ho tro ----

    private Parsed parse(JsonNode body) {
        Input in = Input.of(body);
        // M01 bo bat buoc; M02 cho phep rong/khoang trang; M03 bo gioi han toi da; M07 gioi han thanh 199 (tu choi nham dung bien 200).
        String title = in.string("title", !m.on(MutantFlag.M01), !m.on(MutantFlag.M02), m.on(MutantFlag.M02) ? 0 : 1,
                m.on(MutantFlag.M03) ? NO_LIMIT : m.on(MutantFlag.M07) ? 199 : 200);
        String description = in.string("description", true, true, 1, NO_LIMIT);
        // M06: salaryMin = -1 van duoc nhan.
        BigDecimal salaryMin = in.decimal("salaryMin", false, m.on(MutantFlag.M06) ? BigDecimal.valueOf(-1) : BigDecimal.ZERO, null);
        BigDecimal salaryMax = in.decimal("salaryMax", false, BigDecimal.ZERO, null);
        // M10: do dai toi thieu 3 thanh 2; M04: bo gioi han toi da 3.
        String currency = in.string("salaryCurrency", false, false, m.on(MutantFlag.M10) ? 2 : 3, m.on(MutantFlag.M04) ? NO_LIMIT : 3);
        String jobType = in.string("jobType", true, true, 1, NO_LIMIT);
        if (jobType != null && !JOB_TYPES.contains(jobType)) {
            if (m.on(MutantFlag.M05)) {
                jobType = "FULL_TIME"; // M05: gia tri ngoai enum bi bo qua thay vi 400
            } else if (!in.off("jobType", "enum")) {
                in.addError("jobType phai thuoc FULL_TIME, PART_TIME, INTERN");
            }
        }
        String deadlineText = in.string("deadline", true, true, 1, NO_LIMIT);
        LocalDate deadline = null;
        if (deadlineText != null) {
            try {
                deadline = LocalDate.parse(deadlineText);
            } catch (DateTimeParseException e) {
                if (!in.off("deadline", "format")) {
                    in.addError("deadline phai dung dinh dang yyyy-MM-dd");
                }
            }
        }
        // M11: loi validate tra 200 kem than loi thay vi 400.
        in.check(m.on(MutantFlag.M11) ? 200 : 400);

        // Luat nghiep vu (chay sau validate dinh dang).
        if (deadline != null && deadline.isBefore(LocalDate.now()) && !m.on(MutantFlag.M24)) {
            throw new ApiException(400, "INVALID_DEADLINE", "deadline khong duoc o qua khu");
        }
        if (salaryMin != null && salaryMax != null && salaryMin.compareTo(salaryMax) > 0 && !m.on(MutantFlag.M23)) {
            throw new ApiException(400, "INVALID_SALARY_RANGE", "salaryMin khong duoc lon hon salaryMax");
        }
        return new Parsed(title, description, salaryMin, salaryMax, currency, jobType, deadline);
    }

    private void apply(Store.Job job, Parsed p) {
        job.title = p.title();
        job.description = p.description();
        job.salaryMin = p.salaryMin();
        job.salaryMax = p.salaryMax();
        job.salaryCurrency = p.currency();
        job.jobType = p.jobType();
        job.deadline = p.deadline();
    }

    private Store.Job find(long id, boolean forRead, boolean includeDeleted) {
        java.util.Optional<Store.Job> found = includeDeleted ? store.findJobAny(id) : store.findJob(id);
        return found.orElseThrow(() -> {
            // M12: job khong ton tai tra 500 thay vi 404 (chi o GET).
            if (forRead && m.on(MutantFlag.M12)) {
                return new ApiException(500, "INTERNAL_ERROR", "Loi he thong");
            }
            return ApiException.notFound("JOB_NOT_FOUND", "Khong tim thay tin tuyen dung " + id);
        });
    }

    private Dtos.JobResponse toResponse(Store.Job j) {
        return new Dtos.JobResponse(j.id, j.ownerId, j.title, j.description, j.salaryMin, j.salaryMax, j.salaryCurrency,
                j.jobType, j.deadline == null ? null : j.deadline.toString(), j.status);
    }
}
