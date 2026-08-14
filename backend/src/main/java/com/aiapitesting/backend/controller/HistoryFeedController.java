package com.aiapitesting.backend.controller;

import com.aiapitesting.backend.dto.request.HistoryStatusFilter;
import com.aiapitesting.backend.dto.response.HistoryFeedItemResponse;
import com.aiapitesting.backend.dto.response.PageResponse;
import com.aiapitesting.backend.service.HistoryFeedService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/** Lịch sử tổng gộp mọi project của user hiện tại (Module 8) - khác /projects/{id}/history (1 project). */
@RestController
@RequestMapping("/api/v1/history")
@RequiredArgsConstructor
public class HistoryFeedController {

    private final HistoryFeedService historyFeedService;

    @GetMapping
    public PageResponse<HistoryFeedItemResponse> list(
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID endpointId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "ALL") HistoryStatusFilter status,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        Instant fromInstant = from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        // Cận trên loại trừ (< to+1 ngày) để "to" là ngày cuối cùng được TÍNH TRỌN VẸN, không bị cắt
        // theo giờ UTC 00:00 của chính ngày đó.
        Instant toInstant = to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return historyFeedService.getFeed(projectId, endpointId, fromInstant, toInstant, status, pageable);
    }
}
