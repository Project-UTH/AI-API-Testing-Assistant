# Chạy thật trên API tuyển dụng thật (ai-recruitment-agent) với SRS thật, 2026-10-26

API của nhóm khác (cổng 8082, 110 endpoint, KHÔNG có lỗi cài sẵn, không có đáp án chuẩn). SRS thật: `ai-recruitment-agent/docs/SRS.md` (30 KB, mô tả sản phẩm cấp cao, không phải bản liệt kê luật chi tiết như SRS của mutant-api). Script: `scripts/eval-real-recruitment.py`; dữ liệu thô: `real-recruitment-2026-10-26.json`. **Một lượt, 22 endpoint nghiệp vụ, người gán nhãn là người phát triển, không có đáp án: nhãn "đúng/sai" dưới đây là phán đoán của người đo đối chiếu SRS, không phải số đo chính xác.**

**Token:** 46.453 (ước tính 47.123, sai -1,4%; sổ CSDL +46.453 khớp; trần 80.000). Làn tài liệu 2 lô: 18.983 + 19.131; làn gợi ý 2 lô: 3.980 + 4.359. Làn tài liệu đắt vì mỗi lô gửi cả SRS (~12k token).

## Kết quả
- **5 mục có trích dẫn (làn 1), 9 GIẢ ĐỊNH (làn 2), 4 mục bị bỏ vì trùng ràng buộc spec, 2 đề xuất bị loại** (1 sai endpoint, 1 sai tham số). **8 câu hỏi** về chỗ SRS thiếu (3 câu trùng ý nhau: hạn nộp, tổng trọng số, số khung giờ).
- Mục có trích dẫn: 3 đúng nội dung (số khung giờ phỏng vấn 1-5; mỗi khung có thời điểm và địa điểm; trích dẫn nêu đúng "FR-H12: 1–5 khung giờ"). **2 mục (salaryMin ≤ salaryMax cho POST và PUT /api/hr/jobs) có trích dẫn KHÔNG nêu luật đó** ("HR tạo mới bài đăng tuyển dụng (tiêu đề, mô tả công việc, mức lương, ...)"): đoạn trích có thật, qua kiểm chứng, nhưng không chứng minh luật. Đây là điểm mù của kiểm chứng nguyên văn + hằng số: không bắt được trích dẫn đúng chữ nhưng sai nghĩa khi luật không có hằng số. Người duyệt phải đọc đoạn trích, không được tin nhãn "có trích dẫn".
- Mục GIẢ ĐỊNH (mã gợi ý đều 400): hạn nộp tin ≥ hiện tại (POST, PUT), ngày phỏng vấn ≥ hiện tại, ngày sinh ≤ hiện tại, câu hỏi chọn lựa cần options (POST, PUT), câu trả lời cần ít nhất một dạng nội dung, thang điểm không trùng mức (POST, PUT rubric). Hợp lý về nghiệp vụ; chưa kiểm hết (chỉ POST /api/hr/jobs có test gốc).

## Chạy test từ checklist trên API thật (chỉ POST /api/hr/jobs có test gốc: 4 test)
Lần đầu token của project hết hạn: cả 4 test bị 401, hệ thống gán **AUTH_CONFIG "Nghi token/xác thực", không phải lỗi API** (đúng, không tạo bug giả). Sau khi lấy token HR mới và tạo công ty cho tài khoản thử (API yêu cầu HR có công ty mới đăng tin được; lần đầu trả 404 COMPANY_NOT_FOUND):

| Test | Mong | Nhận | Kết quả |
|---|---|---|---|
| tạo hợp lệ, salaryMin = salaryMax | 201 | 201 | Pass |
| **vi phạm: salaryMin > salaryMax** | 400 | **401** | **Fail, API_WRONG_STATUS, độ tin CHẮC** |
| vi phạm: deadline ở quá khứ | 400 | 400 | Pass |
| tạo hợp lệ, deadline = hôm nay | 201 | 201 | Pass |

**Phát hiện thật:** `POST /api/hr/jobs` với `salaryMin > salaryMax` trả **401 UNAUTHENTICATED ("Cần đăng nhập...") ngay cả khi có token hợp lệ**, trong khi cùng token tạo được tin hợp lệ (201). Gọi trực tiếp bằng script ngoài hệ thống, lặp 2 lần: cùng kết quả; xen kẽ với dữ liệu hợp lệ thì 201. Nhiều khả năng là lỗi ánh xạ ngoại lệ sang mã lỗi ở API đích (nên là 400). Theo nguyên tắc đồ án: giữ Fail, không sửa kỳ vọng. Nên báo cho nhóm phát triển API đích.

## Giới hạn
Một lượt; không đáp án nên không tính precision/recall; 22/110 endpoint; chỉ 4 test chạy được vì thiếu test gốc cho phần lớn endpoint; mục có trích dẫn sai nghĩa cho thấy cần duyệt tay thật (E2 chưa đo). Tác động lên dữ liệu: đã tải SRS vào project EXT, đăng ký tài khoản thử `hr.eval@example.com` cùng 1 công ty và vài tin thử trên bản API chạy cục bộ, đặt lại token của project EXT.
