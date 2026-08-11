package com.aiapitesting.backend.dto.response;

import java.util.UUID;

public record DependencySuggestionResponse(
        String paramName,
        UUID sourceTestCaseId,
        String sourceLabel,
        String suggestedJsonPath
) {
}
