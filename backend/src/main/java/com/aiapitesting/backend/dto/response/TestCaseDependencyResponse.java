package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.TestCaseDependency;

import java.util.UUID;

public record TestCaseDependencyResponse(
        UUID dependsOnTestCaseId,
        String dependsOnTestCaseName,
        String jsonPath,
        String placeholderName
) {
    public static TestCaseDependencyResponse from(TestCaseDependency dependency) {
        return new TestCaseDependencyResponse(
                dependency.getDependsOnTestCase().getId(),
                dependency.getDependsOnTestCase().getName(),
                dependency.getJsonPath(),
                dependency.getPlaceholderName()
        );
    }
}
