package com.aiapitesting.backend.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 17q G2 - so khớp tên field trong lý do API từ chối (oracle mạnh cho Pass của test âm). */
class EndpointCoverageFieldMatchTest {

    @Test
    void mentionsField_matchesCaseAccentAndNamingStyles() {
        assertThat(EndpointCoverageService.mentionsField("salaryMin must be <= salaryMax", "salaryMin")).isTrue();
        assertThat(EndpointCoverageService.mentionsField("Salary min khong hop le", "salaryMin")).isTrue();
        assertThat(EndpointCoverageService.mentionsField("salary_min invalid", "salaryMin")).isTrue();
        assertThat(EndpointCoverageService.mentionsField("criteria[0].weight invalid", "criteria[0].weight")).isTrue();
        assertThat(EndpointCoverageService.mentionsField("emailAddress is required", "email")).isFalse();
        assertThat(EndpointCoverageService.mentionsField(null, "email")).isFalse();
        assertThat(EndpointCoverageService.mentionsField("uuid is invalid", "id")).isFalse();
        assertThat(EndpointCoverageService.mentionsField("username is required", "name")).isFalse();
    }
}
