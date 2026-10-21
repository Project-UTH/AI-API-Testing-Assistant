# E2 — Đo thời gian duyệt test AI nghiệp vụ (cần người thật, CHƯA ĐO)

Mục đích: roadmap 17o/E2 yêu cầu có số đo cho câu "người dùng duyệt test do AI nghiệp vụ đề xuất mất bao lâu và chấp nhận bao nhiêu". Không đo thì chỉ là
khẳng định. **Mọi ô bên dưới đang để trống, không điền số ước đoán.**

## Cách đo (mỗi người duyệt làm 1 lần, ~10 phút)

1. Chạy hệ thống và `mutant-api` (xem `mutant-api/README.md`), tạo project mới, import OpenAPI của `mutant-api`.
2. Ở trang Endpoint chọn **1 endpoint** (đề xuất `POST /api/jobs`), bấm **Sinh test case**, bật "AI bổ sung nghiệp vụ còn thiếu (GIẢ ĐỊNH)".
3. Khi job xong, mở trang Test case, lọc test có nhãn **AI đề xuất thêm / nghiệp vụ**. Bấm đồng hồ.
4. Với từng test, đọc tên + dữ liệu + kỳ vọng + lý do rồi chọn **một** hành động: *Chấp nhận nguyên văn* / *Sửa rồi giữ* / *Bỏ*.
5. Dừng đồng hồ khi duyệt xong toàn bộ danh sách. Ghi vào bảng.

## Bảng kết quả

| Người duyệt | Vai trò (SV/GV/QA) | Endpoint | Số test AI đề xuất | Thời gian duyệt (phút) | Chấp nhận nguyên văn | Sửa rồi giữ | Bỏ | Ghi chú |
|---|---|---|---|---|---|---|---|---|
| | | | | | | | | |
| | | | | | | | | |
| | | | | | | | | |

## Chỉ số tính từ bảng (sau khi có ≥ 3 dòng)

- Thời gian duyệt trung bình / test = tổng phút ÷ tổng số test.
- Tỉ lệ chấp nhận nguyên văn = chấp nhận ÷ tổng; tỉ lệ có giá trị sau chỉnh = (chấp nhận + sửa) ÷ tổng.
- Nếu tỉ lệ bỏ cao, ghi 2–3 ví dụ test bị bỏ và lý do (thường là luật nghiệp vụ AI đoán sai vì spec không nói).

## Giới hạn phải ghi khi báo cáo

Cỡ mẫu nhỏ (người duyệt biết dự án nên có thiên lệch), chưa có nhóm đối chứng (tự viết test tay cùng endpoint), chỉ đo 1 endpoint. Không suy rộng.
