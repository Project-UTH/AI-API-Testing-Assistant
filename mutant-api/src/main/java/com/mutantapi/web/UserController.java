package com.mutantapi.web;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Users")
public class UserController {

    private final Store store;
    private final Mutants mutants;

    public UserController(Store store, Mutants mutants) {
        this.store = store;
        this.mutants = mutants;
    }

    @GetMapping("/me")
    @Operation(summary = "Thong tin tai khoan dang dang nhap (khong bao gom mat khau)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Dtos.UserResponse.class))),
            @ApiResponse(responseCode = "401", description = "Thieu hoac sai token")})
    public Map<String, Object> me(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthUser auth) {
        if (auth.id() == 0) {
            return new LinkedHashMap<>(); // M31: khach (khong token) van duoc tra 200 voi du lieu rong
        }
        Store.User user = store.findUser(auth.id())
                .orElseThrow(() -> new ApiException(401, "UNAUTHORIZED", "Tai khoan khong con ton tai"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", user.id());
        body.put("email", user.email());
        body.put("fullName", user.fullName());
        body.put("role", user.role());
        // H14 (held-out): tra thang entity User (co passwordHash) thay vi UserResponse - cung hanh vi voi M21.
        if (mutants.on(MutantFlag.M21) || mutants.on(MutantFlag.H14)) {
            body.put("passwordHash", user.passwordHash());
        }
        // M32: lo email cua cac tai khoan khac.
        if (mutants.on(MutantFlag.M32)) {
            body.put("allUsers", store.allEmails());
        }
        return body;
    }
}
