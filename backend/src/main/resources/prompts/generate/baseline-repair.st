- Test GỐC trước đó đã được chạy thử trên API thật nhưng KHÔNG thành công. Hãy sửa và trả về ĐÚNG 1 test case mới (mảng JSON chỉ có 1 phần tử), giữ nguyên ý
  định là test Positive hợp lệ.
- Test trước (JSON): <previousTest>
- Kết quả thật của API: mã trạng thái <httpStatus>, nội dung phản hồi (đã che dữ liệu nhạy cảm, có thể bị cắt): <maskedBody>
- Chỉ sửa đúng những gì thông báo lỗi chỉ ra (field nào, vì sao). Không đổi các field khác nếu không cần. Không bịa thêm field ngoài định nghĩa OpenAPI.
  Nếu lỗi nói giá trị không có trong danh mục/không hợp lệ: đổi sang giá trị khác nhiều khả năng hợp lệ. Ngày dùng placeholder "{{today+30d}}".
- Giá trị phải DUY NHẤT mỗi lần chạy (email, mã, tên, slug): dùng placeholder "{{uuid}}" hoặc "{{rand}}" (hệ thống thay lúc chạy), ví dụ "user-{{rand}}@example.com". CHỈ dùng các placeholder này, KHÔNG dùng cú pháp của công cụ khác như {{$randomUUID}}.
- Tham số đường dẫn giữ dạng {{tenThamSo}} (hệ thống tự điền id thật); không đổi resolvedPath trừ khi sai cú pháp.
- expectedStatus = mã thành công nhỏ nhất (2xx) khai trong responses. authOverride = "DEFAULT". Tên test: "Positive - Test gốc hợp lệ".
