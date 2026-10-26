package com.aiapitesting.backend.dto.response;

import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.entity.VerdictTrust;

import java.util.List;
import java.util.UUID;

/**
 * 1 dòng trong Coverage Matrix (Module 15c) - tính tất định từ {@code Endpoint.schema} đối chiếu
 * với {@code TestCase} hiện có, KHÔNG dùng AI/rationale để suy ra trạng thái.
 *
 * Module 15j - các dòng POSITIVE/NEGATIVE/BOUNDARY/SECURITY chính là từng mục của checklist EP/BVA
 * (TestCaseChecklistPlanner, cùng bộ tiêu chí lúc sinh test case), nên mẫu số "X/Y mục" có nghĩa rõ ràng.
 *
 * @param category          nhóm: POSITIVE/NEGATIVE/BOUNDARY/SECURITY (mục checklist), STATUS_CODE, RELATION
 *                          (chuỗi dữ liệu giữa 2 endpoint), AUTH (403 - luôn NOT_APPLICABLE)
 * @param label             nhãn hiển thị tiếng Việt, đủ cụ thể để người dùng biết cần thử gì (kèm kỳ vọng)
 * @param status            COVERED/MISSING/NOT_APPLICABLE
 * @param detail            MISSING: tóm tắt vì sao chưa đạt; NOT_APPLICABLE: lý do không áp dụng (bắt buộc có)
 * @param coveringTestCases Module 15f - (các) test case CHÍNH XÁC khiến dòng này COVERED (provenance), rỗng khi
 *                          MISSING/NOT_APPLICABLE; 15j kèm kết quả lần chạy mới nhất của từng case
 * @param checklistType     15j - tên ChecklistItemType khi dòng là 1 mục checklist, null với STATUS_CODE/RELATION/AUTH
 * @param itemKey           15j - khoá ổn định của mục checklist, gửi lại cho API bù khoảng trống; null khi
 *                          hệ thống không tự bù được loại dòng này (STATUS_CODE/RELATION/AUTH)
 * @param whyNeeded         15j - vì sao cần kiểm mục này, câu mẫu tất định sinh từ spec (không phải AI tự khai)
 * @param runStatus         15j - chỉ có khi COVERED: các case phủ dòng này đã chạy ra kết quả chưa
 * @param nearMisses        15j - chỉ có khi MISSING: case "gần đạt" nhưng không được tính, kèm lý do (bằng chứng)
 * @param fillable          15j - chỉ true khi dòng MISSING này hệ thống bù được NGAY BÂY GIỜ (có case gốc hợp lệ để
 *                          mượn, hoặc dựng thẳng được từ đặc tả) - frontend dùng để làm mờ nút Bổ sung từ trước
 *                          thay vì để người dùng bấm rồi mới biết thất bại
 * @param fillBlockedReason 15j - khi dòng MISSING có {@code itemKey} nhưng chưa bù được: vì sao + cần làm gì
 * @param verdictTrust      17q G2 - chỉ có khi dòng đã chạy ra kết quả (runStatus PASSED/FAILED): kết luận của dòng CHẮC hay NGHI
 * @param specUnderspecified 17q G2 - mục "biên ngầm" (spec không khai ràng buộc, kỳ vọng 2xx chỉ là quy ước): Fail ở đây không quy
 *                          là lỗi API (tối đa NGHI)
 */
public record CoverageDimensionResponse(
        String category, String label, CoverageDimensionStatus status, String detail,
        List<TestCaseRef> coveringTestCases,
        String checklistType, String itemKey, String whyNeeded, CoverageRunStatus runStatus,
        List<NearMiss> nearMisses,
        boolean fillable, String fillBlockedReason,
        VerdictTrust verdictTrust, boolean specUnderspecified
) {
    public CoverageDimensionResponse(
            String category, String label, CoverageDimensionStatus status, String detail,
            List<TestCaseRef> coveringTestCases,
            String checklistType, String itemKey, String whyNeeded, CoverageRunStatus runStatus,
            List<NearMiss> nearMisses, boolean fillable, String fillBlockedReason
    ) {
        this(category, label, status, detail, coveringTestCases, checklistType, itemKey, whyNeeded, runStatus,
                nearMisses, fillable, fillBlockedReason, null, false);
    }

    /** Dòng không bù được/không cần bù (COVERED, NOT_APPLICABLE, STATUS_CODE, RELATION): fillable=false, không có lý do. */
    public CoverageDimensionResponse(
            String category, String label, CoverageDimensionStatus status, String detail,
            List<TestCaseRef> coveringTestCases,
            String checklistType, String itemKey, String whyNeeded, CoverageRunStatus runStatus,
            List<NearMiss> nearMisses
    ) {
        this(category, label, status, detail, coveringTestCases, checklistType, itemKey, whyNeeded, runStatus,
                nearMisses, false, null);
    }

    /** 17q G2 - bản sao kèm độ tin kết luận + cờ "spec thiếu ràng buộc". */
    public CoverageDimensionResponse withTrust(VerdictTrust trust, boolean underspecified) {
        return new CoverageDimensionResponse(category, label, status, detail, coveringTestCases, checklistType, itemKey, whyNeeded,
                runStatus, nearMisses, fillable, fillBlockedReason, trust, underspecified);
    }

    /** @param latestResult 15j - trạng thái lần chạy mới nhất của case, null khi chưa chạy lần nào */
    public record TestCaseRef(UUID id, String name, TestResultStatus latestResult) {
    }

    public record NearMiss(UUID testCaseId, String testCaseName, String reason) {
    }
}
