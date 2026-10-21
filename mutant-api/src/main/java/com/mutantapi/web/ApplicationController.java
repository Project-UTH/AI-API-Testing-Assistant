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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Applications")
public class ApplicationController {

    private static final Set<String> TARGET_STATUSES = Set.of("INTERVIEW_INVITED", "HIRED", "REJECTED");
    /** Vong doi hop le: PENDING -> INTERVIEW_INVITED | REJECTED; INTERVIEW_INVITED -> HIRED | REJECTED. */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "PENDING", Set.of("INTERVIEW_INVITED", "REJECTED"),
            "INTERVIEW_INVITED", Set.of("HIRED", "REJECTED"));

    private final Store store;
    private final Mutants m;

    public ApplicationController(Store store, Mutants mutants) {
        this.store = store;
        this.m = mutants;
    }

    @PostMapping("/jobs/{jobId}/applications")
    @Operation(summary = "Ung vien nop don vao 1 tin tuyen dung (chi CANDIDATE, moi ung vien nop 1 don cho moi tin)")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = Dtos.ApplicationRequest.class)))
    @ApiResponses({
            @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = Dtos.ApplicationResponse.class))),
            @ApiResponse(responseCode = "400", description = "Du lieu khong hop le hoac chua dong y consent"),
            @ApiResponse(responseCode = "401", description = "Thieu hoac sai token"),
            @ApiResponse(responseCode = "403", description = "Khong phai CANDIDATE"),
            @ApiResponse(responseCode = "404", description = "Khong tim thay tin"),
            @ApiResponse(responseCode = "409", description = "Da nop don cho tin nay")})
    public ResponseEntity<Dtos.ApplicationResponse> apply(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthUser auth,
                                                          @PathVariable long jobId, @RequestBody JsonNode body) {
        if (!auth.isCandidate() && !m.on(MutantFlag.M38)) { // M38: HR cung nop don duoc
            throw ApiException.forbidden("Chi ung vien duoc nop don");
        }
        store.findJob(jobId).orElseThrow(() -> ApiException.notFound("JOB_NOT_FOUND", "Khong tim thay tin tuyen dung " + jobId));
        Input in = Input.of(body);
        String coverLetter = in.string("coverLetter", false, false, 0, 2000);
        Boolean consent = in.bool("consent", true);
        in.check(400);
        if (!Boolean.TRUE.equals(consent) && !m.on(MutantFlag.M39)) { // M39: consent=false van nhan don
            throw new ApiException(400, "CONSENT_REQUIRED", "Ung vien phai dong y (consent = true) truoc khi nop don");
        }
        // M27: nop don trung van duoc nhan.
        if (store.hasApplication(jobId, auth.id()) && !m.on(MutantFlag.M27)) {
            throw ApiException.conflict("ALREADY_APPLIED", "Ban da nop don cho tin nay");
        }
        Store.Application app = new Store.Application();
        app.jobId = jobId;
        app.candidateId = auth.id();
        app.coverLetter = coverLetter;
        store.saveApplication(app);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(app));
    }

    @GetMapping("/applications/{id}")
    @Operation(summary = "Xem 1 don ung tuyen (ung vien chu don hoac HR so huu tin)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Dtos.ApplicationResponse.class))),
            @ApiResponse(responseCode = "401", description = "Thieu hoac sai token"),
            @ApiResponse(responseCode = "403", description = "Khong co quyen xem don nay"),
            @ApiResponse(responseCode = "404", description = "Khong tim thay don")})
    public Dtos.ApplicationResponse get(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthUser auth, @PathVariable long id) {
        Store.Application app = findApplication(id, true);
        boolean allowed;
        if (auth.isHr()) {
            // M40: HR khac xem duoc don cua tin khong thuoc minh.
            allowed = ownerOf(app) == auth.id() || m.on(MutantFlag.M40);
        } else {
            // M16: ung vien xem duoc don cua nguoi khac (thieu kiem tra chu don).
            allowed = auth.isCandidate() && (app.candidateId == auth.id() || m.on(MutantFlag.M16));
        }
        if (!allowed) {
            throw ApiException.forbidden("Ban khong co quyen xem don nay");
        }
        return toResponse(app);
    }

    @PatchMapping("/applications/{id}/status")
    @Operation(summary = "HR doi trang thai don: PENDING->INTERVIEW_INVITED|REJECTED, INTERVIEW_INVITED->HIRED|REJECTED")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = Dtos.StatusRequest.class)))
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Dtos.ApplicationResponse.class))),
            @ApiResponse(responseCode = "400", description = "Trang thai khong hop le"),
            @ApiResponse(responseCode = "401", description = "Thieu hoac sai token"),
            @ApiResponse(responseCode = "403", description = "Khong phai HR so huu tin"),
            @ApiResponse(responseCode = "404", description = "Khong tim thay don"),
            @ApiResponse(responseCode = "409", description = "Chuyen trang thai khong hop le")})
    public Dtos.ApplicationResponse changeStatus(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthUser auth,
                                                 @PathVariable long id, @RequestBody JsonNode body) {
        Store.Application app = findApplication(id, false);
        // M17: HR khac doi duoc trang thai don cua tin khong thuoc minh.
        if (!auth.isHr() || (ownerOf(app) != auth.id() && !m.on(MutantFlag.M17))) {
            throw ApiException.forbidden("Chi HR so huu tin duoc doi trang thai don");
        }
        Input in = Input.of(body);
        String status = in.string("status", true, true, 1, Integer.MAX_VALUE);
        boolean invalidStatus = status != null && !TARGET_STATUSES.contains(status);
        if (invalidStatus && m.on(MutantFlag.M42)) { // M42: trang thai ngoai enum bi bo qua, van tra 200
            return toResponse(app);
        }
        if (invalidStatus && !in.off("status", "enum")) {
            in.addError("status phai thuoc INTERVIEW_INVITED, HIRED, REJECTED");
        }
        in.check(400);
        if (status == null) { // chi toi duoc khi cong tac quy tac tat kiem tra "bat buoc"/kieu
            throw ApiException.conflict("INVALID_STATE_TRANSITION", "Khong co trang thai de chuyen");
        }

        boolean allowed = TRANSITIONS.getOrDefault(app.status, Set.of()).contains(status)
                // M26: nhay thang PENDING -> HIRED.
                || (m.on(MutantFlag.M26) && "PENDING".equals(app.status) && "HIRED".equals(status));
        if (!allowed) {
            throw ApiException.conflict("INVALID_STATE_TRANSITION", "Khong the chuyen " + app.status + " -> " + status);
        }
        app.status = status;
        store.saveApplication(app);
        return toResponse(app);
    }

    private Store.Application findApplication(long id, boolean forRead) {
        return store.findApplication(id).orElseThrow(() -> {
            // M41: don khong ton tai tra 500 thay vi 404 (chi o GET).
            if (forRead && m.on(MutantFlag.M41)) {
                return new ApiException(500, "INTERNAL_ERROR", "Loi he thong");
            }
            return ApiException.notFound("APPLICATION_NOT_FOUND", "Khong tim thay don ung tuyen " + id);
        });
    }

    private long ownerOf(Store.Application app) {
        return store.findJobAny(app.jobId).map(j -> j.ownerId).orElse(-1L);
    }

    private Dtos.ApplicationResponse toResponse(Store.Application a) {
        return new Dtos.ApplicationResponse(a.id, a.jobId, a.candidateId, a.coverLetter, a.status);
    }
}
