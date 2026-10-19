package com.aiapitesting.backend.dto.request;

/** Phạm vi đo coverage của 1 endpoint - phải chọn ít nhất 1 loại. */
public record UpdateCoverageScopeRequest(boolean positive, boolean negative, boolean boundary, boolean security) {
}
