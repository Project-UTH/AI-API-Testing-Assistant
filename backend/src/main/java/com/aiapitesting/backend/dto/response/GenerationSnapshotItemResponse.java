package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.dto.ai.GeneratedTestCase;

/** 1 test case trong snapshot của 1 sự kiện "Sinh test case" (Module 8) - chỉ field cần hiển thị. */
public record GenerationSnapshotItemResponse(String name, String description, Integer expectedStatus) {
    public static GenerationSnapshotItemResponse from(GeneratedTestCase snapshot) {
        return new GenerationSnapshotItemResponse(snapshot.name(), snapshot.description(), snapshot.expectedStatus());
    }
}
