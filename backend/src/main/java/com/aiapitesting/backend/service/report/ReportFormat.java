package com.aiapitesting.backend.service.report;

import com.aiapitesting.backend.exception.InvalidRequestException;

import java.util.Locale;

/** Định dạng báo cáo lượt chạy (Module 17 - 17d). PDF cố ý không có: người dùng in HTML từ trình duyệt. */
public enum ReportFormat {
    JUNIT("xml", "application/xml"),
    HTML("html", "text/html");

    private final String extension;
    private final String contentType;

    ReportFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    /** "junit"/"xml" → JUNIT, "html" → HTML (không phân biệt hoa thường); giá trị lạ → 400 tiếng Việt. */
    public static ReportFormat parse(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "junit", "xml" -> JUNIT;
            case "html" -> HTML;
            default -> throw new InvalidRequestException("Định dạng báo cáo không hợp lệ (chọn junit hoặc html)");
        };
    }
}
