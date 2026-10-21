- Chỉ sinh ĐÚNG 1 test case Positive HỢP LỆ làm test GỐC cho endpoint này (mảng JSON chỉ có 1 phần tử). Hệ thống sẽ CHẠY THỬ test này trên API thật
  để xác nhận trả 2xx, rồi dùng nó làm nền để tự sinh các test còn lại - nên giá trị phải thật sự hợp lệ.
- Field kiểu object lồng nhau: điền đủ cả object con (kể cả field bắt buộc bên trong); field kiểu mảng object: đưa ít nhất 1 phần tử đầy đủ field bắt buộc.
- Điền ĐỦ mọi field bắt buộc; field tuỳ chọn chỉ điền khi chắc hợp lệ. Giá trị thực tế, hợp lý với tên field (email đúng dạng, số trong khoảng cho phép,
  chuỗi không rỗng, đúng enum/pattern/độ dài trong định nghĩa). Mọi ràng buộc trong OpenAPI phải được tôn trọng.
- Ngày/giờ dùng placeholder ngày động, KHÔNG ghi cứng ngày: "{{today+30d}}", "{{now+1h}}".
- Giá trị phải DUY NHẤT mỗi lần chạy (email, mã, tên, slug): dùng placeholder "{{uuid}}" hoặc "{{rand}}" (hệ thống thay lúc chạy), ví dụ "user-{{rand}}@example.com". CHỈ dùng các placeholder này, KHÔNG dùng cú pháp của công cụ khác như {{$randomUUID}}.
- Tham số đường dẫn viết dạng {{tenThamSo}} trong resolvedPath và đoán 1 giá trị tạm trong pathParamFallbacks (hệ thống sẽ thay bằng id thật). TUYỆT ĐỐI KHÔNG
  dùng id/UUID mẫu trong tài liệu làm giá trị chắc chắn tồn tại.
- Field là mã danh mục/mã tham chiếu mà spec KHÔNG liệt kê giá trị hợp lệ: chọn giá trị phổ biến nhất có thể hợp lệ (dạng viết hoa có gạch dưới như IT_SOFTWARE
  nếu tên gợi ý như vậy); nếu sai hệ thống sẽ báo lại thông báo lỗi của API để bạn sửa.
- expectedStatus = mã thành công nhỏ nhất (2xx) khai trong phần responses của định nghĩa. authOverride = "DEFAULT". Tên test: "Positive - Test gốc hợp lệ".
- Field "rationale": 1 câu giải thích các giá trị đã chọn. Không tự bịa field ngoài định nghĩa. Không tự thêm header xác thực.
