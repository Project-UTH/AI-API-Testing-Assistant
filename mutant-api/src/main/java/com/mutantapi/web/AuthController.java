package com.mutantapi.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.mutantapi.mutant.MutantFlag;
import com.mutantapi.mutant.Mutants;
import com.mutantapi.security.TokenService;
import com.mutantapi.store.Store;
import com.mutantapi.web.dto.Dtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/auth")
@Tag(name = "Auth")
public class AuthController {

    private final Store store;
    private final TokenService tokens;
    private final Mutants mutants;

    public AuthController(Store store, TokenService tokens, Mutants mutants) {
        this.store = store;
        this.tokens = tokens;
        this.mutants = mutants;
    }

    @PostMapping("/login")
    @Operation(summary = "Dang nhap, nhan JWT (tai khoan demo: hr1@demo.local, hr2@demo.local, cand1@demo.local, cand2@demo.local)")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = Dtos.LoginRequest.class)))
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Dtos.LoginResponse.class))),
            @ApiResponse(responseCode = "400", description = "Thieu email/mat khau"),
            @ApiResponse(responseCode = "401", description = "Sai thong tin dang nhap")})
    public Map<String, Object> login(@RequestBody JsonNode body) {
        Input in = Input.of(body);
        String email = in.string("email", true, true, 1, Integer.MAX_VALUE);
        String password = in.string("password", true, true, 1, Integer.MAX_VALUE);
        in.check(400);
        if (email == null || password == null) { // chi toi duoc khi cong tac quy tac tat kiem tra dau vao
            throw new ApiException(401, "INVALID_CREDENTIALS", "Sai email hoac mat khau");
        }

        // M29: email khong ton tai tra 500 thay vi 401.
        if (mutants.on(MutantFlag.M29) && store.findUserByEmail(email).isEmpty()) {
            throw new ApiException(500, "INTERNAL_ERROR", "Loi he thong");
        }
        // M28: chi kiem tra email, bo qua mat khau.
        Store.User user = store.findUserByEmail(email)
                .filter(u -> mutants.on(MutantFlag.M28) || u.passwordHash().equals(Store.hash(password)))
                .orElseThrow(() -> new ApiException(401, "INVALID_CREDENTIALS", "Sai email hoac mat khau"));
        String token = tokens.issue(user.id(), user.role());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("token", token);
        response.put("role", user.role());
        response.put("expiresAt", String.valueOf(tokens.expiryOf(token).toInstant()));
        // M30: tra them passwordHash trong response dang nhap.
        if (mutants.on(MutantFlag.M30)) {
            response.put("passwordHash", user.passwordHash());
        }
        return response;
    }
}
