package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.request.AuthProfileRequest;
import com.aiapitesting.backend.dto.response.ApiResponse;
import com.aiapitesting.backend.dto.response.AuthProfileResponse;
import com.aiapitesting.backend.service.AuthProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Module 17 (3.1) - profile xác thực (vai trò) của target API. Response không bao giờ chứa token. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/auth-profiles")
@RequiredArgsConstructor
public class AuthProfileController {

    private final AuthProfileService authProfileService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<AuthProfileResponse>>> list(@PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.of(authProfileService.list(projectId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AuthProfileResponse>> create(
            @PathVariable UUID projectId, @Valid @RequestBody AuthProfileRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(authProfileService.create(projectId, request)));
    }

    @PutMapping("/{profileId}")
    public ResponseEntity<ApiResponse<AuthProfileResponse>> update(
            @PathVariable UUID projectId, @PathVariable UUID profileId, @Valid @RequestBody AuthProfileRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.of(authProfileService.update(projectId, profileId, request)));
    }

    @DeleteMapping("/{profileId}")
    public ResponseEntity<Void> delete(@PathVariable UUID projectId, @PathVariable UUID profileId) {
        authProfileService.delete(projectId, profileId);
        return ResponseEntity.noContent().build();
    }
}
