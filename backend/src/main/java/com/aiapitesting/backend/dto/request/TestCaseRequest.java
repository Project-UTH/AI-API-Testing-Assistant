package com.aiapitesting.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record TestCaseRequest(
        @NotBlank(message = "Tên test case không được để trống")
        @Size(max = 255, message = "Tên test case tối đa 255 ký tự")
        String name,

        @Size(max = 2000, message = "Mô tả tối đa 2000 ký tự")
        String description,

        String requestHeaders,

        String requestBody,

        @NotNull(message = "Mã trạng thái kỳ vọng không được để trống")
        @Min(value = 100, message = "Mã trạng thái phải từ 100 đến 599")
        @Max(value = 599, message = "Mã trạng thái phải từ 100 đến 599")
        Integer expectedStatus,

        String resolvedPath,

        String pathParamFallbacks,

        @Valid
        List<TestCaseDependencyInput> dependencies
) {
}
