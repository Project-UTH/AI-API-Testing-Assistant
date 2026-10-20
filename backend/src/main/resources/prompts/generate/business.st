- Chỉ sinh test case NGHIỆP VỤ cho endpoint này. KHÔNG sinh lại Positive/Negative/Boundary/Security cơ bản (thiếu field bắt buộc, sai kiểu, vượt độ dài,
  thiếu token...) - hệ thống đã tự tính các loại đó từ spec.
- Nhiệm vụ: từ TÊN field, mô tả endpoint và ngữ cảnh, SUY LUẬN các luật nghiệp vụ hợp lý mà spec KHÔNG khai báo, rồi viết test vi phạm đúng 1 luật.
  Ví dụ loại luật: ngày hạn/ngày kết thúc không được ở quá khứ; cặp giá trị min/max thì min không lớn hơn max; mã/id tham chiếu phải tồn tại
  trong hệ thống; giá trị tiền/số lượng phải dương; trạng thái chỉ chuyển theo thứ tự hợp lệ; trường thuộc nhóm loại trừ nhau; chuỗi chỉ gồm khoảng trắng
  không được coi là có giá trị; định dạng ngày/giờ/tiền tệ không hợp lệ dù đúng kiểu dữ liệu.
- MỖI test case chỉ vi phạm ĐÚNG 1 luật, mọi field khác giữ hợp lệ theo spec (đúng kiểu, đúng enum, đúng độ dài). Tối đa 12 test case, không trùng nhau,
  ưu tiên các luật có khả năng cao nhất.
- expectedStatus = mã lỗi hợp lý nhất của luật bị vi phạm (thường 400 hoặc 422; 404/409 khi luật là tham chiếu không tồn tại hoặc xung đột trạng thái).
  TUYỆT ĐỐI KHÔNG kỳ vọng 500. Có thể thêm tối đa 2 test case HỢP LỆ (kỳ vọng 2xx) nếu chúng kiểm chứng 1 luật nghiệp vụ ở biên (ví dụ ngày hạn đúng hôm nay).
- Ngày/giờ dùng placeholder ngày động, KHÔNG ghi cứng ngày: "{{today}}", "{{today+30d}}", "{{today-1d}}", "{{now+1h}}", "{{now-15m}}".
- Field "rationale" BẮT BUỘC bắt đầu bằng "GIẢ ĐỊNH:" rồi nêu rõ luật nghiệp vụ được giả định (đây chỉ là suy đoán từ tên field, chưa được xác nhận).
- Tên test case: "Nghiệp vụ - [luật bị vi phạm]". authOverride luôn "DEFAULT". Không tự bịa field ngoài định nghĩa OpenAPI. Không tự thêm header xác thực.
