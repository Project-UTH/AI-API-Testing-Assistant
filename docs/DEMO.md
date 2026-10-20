# Kịch bản demo (~9 phút, desktop >= 1024px)

Demo dùng dữ liệu đã chạy sẵn, **không gọi AI** (không tốn token, không phụ thuộc mạng). Dữ liệu tạo bằng chính API của
hệ thống, không chèn SQL.

## Chuẩn bị (làm trước giờ demo)

1. Chạy hệ thống: `docker compose up -d --build` (giao diện `http://localhost:3000`).
2. Chạy `shop-api-target` ở cổng 8081 (API đích dùng để demo).
3. Seed dữ liệu: `python scripts/seed-demo.py --recreate` (chỉ cần Python 3, không cài thêm gì).
   - Tạo tài khoản demo `demo@aiapi.local` / `demo12345` (riêng, không đụng tài khoản test khác).
   - Tạo project **"Demo - Shop API"**: import OpenAPI, 25 test case thủ công, chạy 7 lượt, sinh bug report.
4. Kiểm tra trước giờ demo: đăng nhập tài khoản demo, mở project, xem Kết quả thực thi mở được. Token của target hết hạn sau
   7 ngày - nếu quá hạn thì chạy lại bước 3.

## Kịch bản

| # | Màn hình | Làm gì | Nói gì |
|---|---|---|---|
| 1 | Project "Demo - Shop API" | Chỉ thanh tab và (nếu còn) banner "Bước tiếp theo" | "Hệ thống nhận OpenAPI, tạo test case (AI hoặc thủ công), chạy rồi **giải thích** vì sao fail. Hôm nay demo phần đã chạy sẵn, không gọi AI." |
| 2 | Tab Test case | Mở 1 test case; chỉ chuỗi dữ liệu `{{id}}` (POST → GET → PUT → PATCH → DELETE) | "Người dùng review/sửa trước khi chạy. Id lấy từ kết quả POST thay vì đoán." |
| 3 | Tab Lịch sử → lượt chạy gần nhất | Mở kết quả; bấm từng chip nguyên nhân | "Không phải đỏ hết: mỗi fail có **nguyên nhân kèm bằng chứng, tất định, 0 token**." Ví dụ: 404 do id dự phòng (test chưa chạm tới logic), 403 thay vì 401 (lệch cách trả mã), còn "trả 200 trong khi spec nói quantity phải > 0" mới là lỗi của API. |
| 4 | Lượt chạy số 4 (token sai) | Mở lượt đó | "Khi token hết hạn, hàng chục test đỏ không có nghĩa API hỏng. Hệ thống thấy N/M test cùng bị từ chối xác thực và **không tạo bug**." |
| 5 | Test `X1 Tìm sản phẩm FLAKYMARK` | Mở kết quả của test này | "Chip *Không ổn định*: cùng phiên bản test đổi Pass↔Fail 5 lần trong 6 lượt. Ngưỡng này chúng em tự đặt, chưa calibrate." |
| 6 | Tab Coverage | Chỉ "đã có test case" và "đã chạy có kết quả" | "Coverage đo checklist EP/BVA suy ra từ spec, không đo code. Mục chưa chạm tài nguyên được đánh dấu *chưa kiểm được*." |
| 7 | Tab Bug Report | Mở 1 bug | "Bug report do hệ thống tạo theo mẫu cố định, **không dùng AI** nên không bịa. Chỉ 6 bug cho 6 test lỗi thật - fail do môi trường/token/không ổn định/id dự phòng bị loại." |
| 8 | Bấm Import OpenAPI | **Chỉ mở hộp thoại, bấm Hủy** | "Import lại xoá toàn bộ dữ liệu cũ (chủ ý), nên hộp thoại báo số lượng sẽ mất và bắt xác nhận." |

## Rủi ro khi demo trực tiếp

- Đừng bấm "Import" ở bước 8 (xoá hết dữ liệu demo; seed lại mất vài phút).
- Chỉ chạy lại 1-2 test để chứng minh chạy thật (25 test mất vài giây); đừng chạy cả project nhiều lần (làm đổi lịch sử/ổn định).
- Backend khởi động lại giữa chừng không làm kẹt lượt chạy (tự dọn khi khởi động), nhưng nên kiểm tra trước giờ demo.
- "Sinh test case" ở cách "AI sinh test gốc" gọi AI thật (~6–27 nghìn token/endpoint, thấy số token ngay trong khung giám sát); muốn demo không tốn token thì chọn cách "Chỉ bổ sung từ test gốc đã Pass (0 token)" hoặc bật "Chạy thử" (dry-run). Khung giám sát chạy nền: demo được thu nhỏ/mở lại/huỷ.
- Bật "Dọn dữ liệu đã tạo" khi chạy test trên API demo để không để lại bản ghi thừa.

## Hạn chế đã biết (nói thẳng khi bị hỏi)

- **Khác Postman/Schemathesis?** Postman cần người viết test; Schemathesis sinh fuzz nhưng không giải thích. Ở đây: AI + checklist
  EP/BVA từ spec, review trước khi chạy, phân loại nguyên nhân tất định, quy trình bug report. **Chưa có:** fuzz sâu, chạy trong
  CI/dòng lệnh. Đối chiếu response với schema của spec chỉ ở mức cảnh báo (kiểu, trường bắt buộc), không đổi Pass/Fail.
- **Làm sao biết là bug thật?** Với black-box không biết chắc. Hệ thống chỉ "nghi kèm bằng chứng"; fail nghi do test/môi trường/token/
  không ổn định bị chặn khỏi bug tự động. Các luật (id dự phòng → 404, 401↔403, 400↔422, 200/201/204) là heuristic chưa calibrate
  bằng tập dữ liệu lớn và có thể che một lệch nhẹ so với spec (nhãn "nghi kỳ vọng lệch mã" luôn kèm bằng chứng và vẫn là Fail).
- **Ngưỡng FLAKY** (>= 5 lần chạy, đổi Pass↔Fail >= 2 lần) do nhóm tự đặt.
- **AI sinh test có đáng tin?** Vì vậy AI chỉ sinh 1 test gốc rồi hệ thống CHẠY THẬT để xác nhận (tối đa vài vòng AI sửa theo lỗi thật); phần còn lại do checklist tất định bù. AI nghiệp vụ là giả định cần duyệt. Đã đo trên API tuyển dụng: 36 test sinh ra → 24 Pass / 12 Fail, trong đó 8 Fail là lỗi thật của API (trả 401 thay vì 400). **Độ chính xác (bắt được bao nhiêu lỗi cài sẵn) CHƯA đo** - xem roadmap 17o.
- **Xác thực:** token tĩnh (Bearer, Basic, X-API-Key/header tuỳ ý, query string) + nhiều profile vai trò; chưa có đăng nhập động/refresh token/OAuth2/nhiều môi trường. Body gửi được JSON, form-urlencoded, multipart (chỉ field văn bản), XML/text nguyên văn.
- **Import lại OpenAPI** mặc định theo DIFF (giữ test case/lịch sử của endpoint không đổi, đánh dấu endpoint đổi/gỡ; có chế độ thay thế toàn bộ kèm cảnh báo). Hạn chế còn lại: khoảng hở mili-giây giữa lúc kiểm tra và lúc tạo lần chạy mới; import giữa lúc đang sinh test.
- **Chi phí AI:** có quota token theo người dùng/ngày (mặc định 2.000.000) và trần token riêng cho mỗi lần sinh (mặc định 120.000). Hạn chế đã biết: import lại và xoá project xoá cả lịch sử sinh test mà quota
  được tính từ đó, nên có thể lách hạn mức (đề xuất sửa: bảng ghi usage riêng).
- **Giao diện** tối ưu cho màn hình >= 1024px; chưa hỗ trợ điện thoại.
- **Dữ liệu demo không bịa:** tạo qua chính API của hệ thống và chạy thật trên shop-api-target; 25 test case là test thủ công.
