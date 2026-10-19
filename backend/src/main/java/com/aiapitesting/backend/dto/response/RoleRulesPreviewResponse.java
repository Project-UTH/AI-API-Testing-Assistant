package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.AuthzExpectation;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

/**
 * Module 17 (S5) - xem trước kết quả áp quy tắc ma trận phân quyền (KHÔNG ghi gì): mỗi quy tắc khớp bao nhiêu endpoint, mẫu đường dẫn,
 * có ghi đè quy tắc trước/khai báo đã có không, và tổng số ô sẽ tạo/đổi/xoá nếu lưu.
 */
public record RoleRulesPreviewResponse(int cellsToCreate, int cellsToChange, int cellsToDelete, List<RuleReport> rules) {

    /** Số ô thật sự bị ghi nếu lưu (khớp với {@code BulkUpdateResponse.updated} của lần lưu). */
    @JsonProperty("totalCells")
    public int totalCells() {
        return cellsToCreate + cellsToChange + cellsToDelete;
    }

    /**
     * @param ruleIndex          thứ tự quy tắc trong yêu cầu, bắt đầu từ 1
     * @param samplePaths        tối đa 5 mẫu dạng "METHOD /path"
     * @param overridesEarlier   số ô bị quy tắc NÀY ghi đè giá trị khác do quy tắc TRƯỚC trong cùng yêu cầu đặt
     * @param overridesExisting  số ô đã có khai báo (đã lưu) với giá trị khác sẽ bị đổi
     */
    public record RuleReport(
            int ruleIndex, UUID profileId, String profileName, AuthzExpectation expectation, int matchedEndpoints,
            List<String> samplePaths, int overridesEarlier, int overridesExisting
    ) {
        @JsonProperty("matchedNothing")
        public boolean matchedNothing() {
            return matchedEndpoints == 0;
        }
    }
}
