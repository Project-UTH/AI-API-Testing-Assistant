# Danh mục mẫu luật nghiệp vụ (17q G0.2)

*Đọc cùng: roadmap 17q (G3.1).*

*Danh mục này được chốt và commit TRƯỚC khi viết bộ lỗi held-out (G0.3) và tài liệu nghiệp vụ dùng để đo (G0.4). Mục đích: bộ mẫu luật mà hệ thống hỗ trợ lấy từ tài liệu tham khảo, không phải từ các lỗi cài sẵn trong `mutant-api`. Nhờ vậy, khi đo trên bộ held-out, hệ thống không bị "may đo" theo đề.*

## Nguồn

1. **[MLP19]** A. Martin-Lopez, S. Segura, A. Ruiz-Cortés. *A Catalogue of Inter-Parameter Dependencies in RESTful Web APIs.* ICSOC 2019.
   - Khảo sát 40 API thật (2.557 operation).
   - Phân loại phụ thuộc giữa các tham số thành 7 loại: Requires, Or, OnlyOne, AllOrNone, ZeroOrOne, Arithmetic/Relational, Complex.
2. **[ISTQB]** ISTQB Foundation Level Syllabus v4.0 (2023):
   - 4.2.3 Decision table testing;
   - 4.2.4 State transition testing.
3. **[OWASP23]** OWASP API Security Top 10 (2023). API1 (BOLA) và API6 (truy cập luồng nghiệp vụ nhạy cảm không giới hạn) được dùng làm căn cứ cho mẫu "trùng lặp" và "quyền sở hữu".
4. **Ràng buộc toàn vẹn dữ liệu** (cơ sở dữ liệu quan hệ): khoá duy nhất (unique) và tham chiếu tồn tại (referential integrity).

## Danh mục

Mỗi mẫu có 3 phần:
- **Tham số**: những gì luật cần biết.
- **Test sinh ra**: hệ thống tạo test từ test gốc hợp lệ bằng cách sửa đúng các field của luật.
- **Kỳ vọng**: kỳ vọng mặc định là "bị từ chối" (không 2xx, không 5xx). Mã lỗi cụ thể chỉ lấy khi có nguồn (xem roadmap 17q G3.1).

| # | Mẫu | Nguồn | Tham số | Test sinh ra (vi phạm đúng 1 luật) | Đợt G3 |
|---|---|---|---|---|---|
| T1 | `COMPARE_FIELDS`: so sánh 2 field (`a ≤ b`, `a < b`, `a = b`...) | MLP19 Arithmetic/Relational | field a, field b, phép so sánh | 1 test vi phạm (a > b) + 1 test ở biên hợp lệ (a = b nếu là `≤`) | Đợt đầu |
| T2 | `REQUIRES`: có field a thì phải có field b | MLP19 Requires | a, b | có a, bỏ b | Đợt đầu |
| T3 | `ALL_OR_NONE`: nhóm field phải có đủ hoặc không có field nào | MLP19 AllOrNone | danh sách field | có một phần nhóm | Đợt đầu |
| T4 | `ONLY_ONE` / `ZERO_OR_ONE`: đúng 1 / tối đa 1 field trong nhóm | MLP19 OnlyOne, ZeroOrOne | danh sách field | gửi 2 field cùng lúc; với ONLY_ONE thêm test không gửi field nào | Đợt đầu |
| T5 | `OR`: ít nhất 1 field trong nhóm | MLP19 Or | danh sách field | không gửi field nào trong nhóm | Đợt đầu |
| T6 | `DATE_RELATIVE`: ngày/giờ so với thời điểm hiện tại (không ở quá khứ / không ở tương lai / trong khoảng N ngày) | Ràng buộc thời gian, dạng Relational của MLP19 với hằng "bây giờ" | field, hướng, khoảng | giá trị ở phía bị cấm (vd `{{today-1d}}`) + 1 test biên hợp lệ | Đợt đầu |
| T7 | `VALUE_RANGE`: giá trị trong khoảng mà spec KHÔNG khai (luật nghiệp vụ, vd số năm kinh nghiệm 0-50) | Relational với hằng số; BVA áp lên luật nghiệp vụ | field, min?, max? | ngay ngoài biên ở từng phía đã khai | Đợt đầu |
| T8 | `ARRAY_AGGREGATE`: hàm tổng hợp trên mảng (tổng/số phần tử/không trùng) so với hằng | MLP19 Complex (dạng tổng hợp) | đường dẫn mảng, field, hàm, phép so sánh, hằng | sửa phần tử để tổng hợp vi phạm (vd tổng ≠ 100) | Đợt đầu |
| T9 | `UNIQUE`: tạo trùng theo khoá duy nhất bị từ chối | Toàn vẹn dữ liệu (khoá duy nhất); OWASP23 API6 | tập field tạo thành khoá | gửi lại test gốc lần 2 cùng khoá | Đợt đầu |
| T10 | `STATE_TRANSITION`: chỉ được chuyển trạng thái theo các cạnh cho phép | ISTQB 4.2.4 | field trạng thái, danh sách cạnh hợp lệ, endpoint đổi trạng thái | chuyển theo cạnh KHÔNG có trong danh sách (cần dựng dữ liệu ở trạng thái nguồn) | **Hoãn** (cần chuỗi dữ liệu nhiều bước) |
| T11 | `REFERENCE_EXISTS`: mã/id tham chiếu phải tồn tại | Toàn vẹn dữ liệu (tham chiếu) | field, endpoint/nguồn danh mục | giá trị chắc chắn không tồn tại | **Hoãn** (oracle chỉ ở mức NGHI: 400/404/422 đều hợp lý) |
| T12 | `OWNERSHIP`: chỉ chủ sở hữu được đọc/sửa/xoá | OWASP23 API1 (BOLA) | endpoint, vai | dùng profile thứ 2 cùng vai truy cập dữ liệu của profile 1 | **Hướng phát triển** (BOLA, đã chốt) |

## Luật không khớp mẫu nào

- Hiện ở mục "chưa tự động hoá". Người dùng tự viết test.
- Đợt này KHÔNG cho AI viết test cho các luật này, vì đây là nguồn chính của Fail giả 23% ở lần đo 17o.

## Quy tắc chống overfitting

- Không thêm mẫu mới vào danh mục sau khi đã xem kết quả đo trên bộ held-out.
- Nếu cần thêm, ghi rõ ngày và lý do vào phần lịch sử bên dưới. Số đo sau thay đổi đó coi là in-sample.
- Danh mục được thiết kế theo các loại phụ thuộc phổ biến trong MLP19. Mức độ phủ loại lỗi của bộ M01-M42 là hệ quả, không phải mục tiêu.

## Lịch sử

- 2026-10-23: phiên bản đầu (T1-T12), chốt trước G0.3/G0.4.
