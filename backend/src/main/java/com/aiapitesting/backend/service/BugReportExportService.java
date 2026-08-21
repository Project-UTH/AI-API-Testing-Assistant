package com.aiapitesting.backend.service;

import com.aiapitesting.backend.entity.BugFrequency;
import com.aiapitesting.backend.entity.BugPriority;
import com.aiapitesting.backend.entity.BugReport;
import com.aiapitesting.backend.entity.BugSeverity;
import com.aiapitesting.backend.entity.BugStatus;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Sinh file .xlsx dạng BẢNG 1 header + N hàng dữ liệu (giống mẫu file QA người dùng cung cấp -
 * BugID/Status/Component/Summary/Description/Severity/Frequency/Priority/Attachment theo cột, có
 * filter + freeze header), KHÔNG phải layout label/value dọc. Dùng chung cho cả xuất 1 bug (dialog
 * sửa bug, N=1) lẫn xuất toàn bộ bug của project (trang Bug Report, N=nhiều). Nhãn tiếng Việt phải
 * khớp BugStatusBadge.tsx ở frontend, đổi 1 bên phải đổi bên kia. Tách riêng khỏi BugReportService
 * vì đây là logic trình bày (Apache POI), không phải nghiệp vụ CRUD/đọc-tổng hợp.
 */
@Service
public class BugReportExportService {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(ZoneId.systemDefault());

    private static final String[] HEADERS = {
            "BugID", "Trạng thái", "Component", "Summary", "Description",
            "Severity", "Frequency", "Priority", "Attachment",
            "Build", "Người báo cáo", "Từ lần chạy lúc", "Ngày tạo", "Ghi chú"
    };

    private static final int[] COLUMN_WIDTH_CHARS = {
            12, 14, 20, 35, 70, 12, 14, 14, 20, 16, 24, 18, 18, 30
    };

    private static final Map<BugStatus, String> STATUS_LABEL = new EnumMap<>(BugStatus.class);
    private static final Map<BugSeverity, String> SEVERITY_LABEL = new EnumMap<>(BugSeverity.class);
    private static final Map<BugPriority, String> PRIORITY_LABEL = new EnumMap<>(BugPriority.class);
    private static final Map<BugFrequency, String> FREQUENCY_LABEL = new EnumMap<>(BugFrequency.class);

    static {
        STATUS_LABEL.put(BugStatus.NEW, "Mới");
        STATUS_LABEL.put(BugStatus.NEED_REVISE, "Cần bổ sung");
        STATUS_LABEL.put(BugStatus.READY_TO_SUBMIT, "Sẵn sàng báo cáo");
        STATUS_LABEL.put(BugStatus.POSTED, "Đã báo cáo");
        STATUS_LABEL.put(BugStatus.PENDING, "Đang chờ xử lý");
        STATUS_LABEL.put(BugStatus.CANNOT_REPRODUCE, "Không tái hiện được");
        STATUS_LABEL.put(BugStatus.REOPENED, "Mở lại");
        STATUS_LABEL.put(BugStatus.CLOSED, "Đã đóng");

        SEVERITY_LABEL.put(BugSeverity.MINOR, "Nhẹ");
        SEVERITY_LABEL.put(BugSeverity.SERIOUS, "Nghiêm trọng");
        SEVERITY_LABEL.put(BugSeverity.CRITICAL, "Chí mạng");

        PRIORITY_LABEL.put(BugPriority.TRIVIAL, "Không đáng kể");
        PRIORITY_LABEL.put(BugPriority.MINOR, "Thấp");
        PRIORITY_LABEL.put(BugPriority.MAJOR, "Cao");
        PRIORITY_LABEL.put(BugPriority.CRITICAL, "Nghiêm trọng");
        PRIORITY_LABEL.put(BugPriority.BLOCKER, "Chặn release");

        FREQUENCY_LABEL.put(BugFrequency.SELDOM, "Hiếm khi");
        FREQUENCY_LABEL.put(BugFrequency.OFTEN, "Thường xuyên");
        FREQUENCY_LABEL.put(BugFrequency.ALWAYS, "Luôn luôn");
    }

    public byte[] exportSingleBugToExcel(BugReport bug) {
        return exportBugsToExcel(List.of(bug));
    }

    public byte[] exportBugsToExcel(List<BugReport> bugs) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Bug List");
            for (int i = 0; i < COLUMN_WIDTH_CHARS.length; i++) {
                sheet.setColumnWidth(i, COLUMN_WIDTH_CHARS[i] * 256);
            }

            CellStyle headerStyle = headerStyle(workbook);
            CellStyle cellStyle = cellStyle(workbook, false);
            CellStyle wrapCellStyle = cellStyle(workbook, true);

            writeHeaderRow(sheet, headerStyle);
            int rowIndex = 1;
            for (BugReport bug : bugs) {
                writeDataRow(sheet, rowIndex++, bug, cellStyle, wrapCellStyle);
            }

            sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, rowIndex - 1), 0, HEADERS.length - 1));
            sheet.createFreezePane(0, 1);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Không tạo được file Excel cho bug report", e);
        }
    }

    private void writeHeaderRow(Sheet sheet, CellStyle headerStyle) {
        Row row = sheet.createRow(0);
        row.setHeightInPoints(20);
        for (int i = 0; i < HEADERS.length; i++) {
            XSSFCell cell = (XSSFCell) row.createCell(i);
            cell.setCellValue(HEADERS[i]);
            cell.setCellStyle(headerStyle);
        }
    }

    private void writeDataRow(Sheet sheet, int rowIndex, BugReport bug, CellStyle cellStyle, CellStyle wrapCellStyle) {
        Row row = sheet.createRow(rowIndex);
        String description = blankToPlaceholder(bug.getStepsToReproduce());
        int lineCount = Math.max((int) description.lines().count(), 1);
        row.setHeightInPoints(Math.max(15f, lineCount * 15f));

        String[] values = {
                bug.getBugId(),
                STATUS_LABEL.get(bug.getStatus()),
                bug.getEndpoint().getMethod() + " " + bug.getEndpoint().getPath(),
                bug.getSummary(),
                description,
                SEVERITY_LABEL.get(bug.getSeverity()),
                FREQUENCY_LABEL.get(bug.getFrequency()),
                PRIORITY_LABEL.get(bug.getPriority()),
                blankToPlaceholder(bug.getAttachmentUrl()),
                blankToPlaceholder(bug.getBuild()),
                bug.getReporter().getEmail(),
                TIMESTAMP_FORMAT.format(bug.getSourceTestResult().getExecution().getStartedAt()),
                TIMESTAMP_FORMAT.format(bug.getCreatedAt()),
                blankToPlaceholder(bug.getNote()),
        };

        // Summary/Description/Ghi chú (cột 3, 4, 13) là văn bản dài nhiều dòng - cần wrap, còn lại
        // giữ 1 dòng cho gọn giống mẫu file QA người dùng cung cấp.
        for (int i = 0; i < values.length; i++) {
            XSSFCell cell = (XSSFCell) row.createCell(i);
            cell.setCellValue(values[i]);
            cell.setCellStyle(i == 3 || i == 4 || i == 13 ? wrapCellStyle : cellStyle);
        }
    }

    private String blankToPlaceholder(String value) {
        return (value == null || value.isBlank()) ? "(không có)" : value;
    }

    private CellStyle headerStyle(XSSFWorkbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
        applyThinBorder(style);
        return style;
    }

    private CellStyle cellStyle(XSSFWorkbook workbook, boolean wrap) {
        CellStyle style = workbook.createCellStyle();
        style.setVerticalAlignment(VerticalAlignment.TOP);
        style.setWrapText(wrap);
        applyThinBorder(style);
        return style;
    }

    private void applyThinBorder(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }
}
