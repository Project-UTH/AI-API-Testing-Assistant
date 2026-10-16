- Chỉ sinh test case loại Security cho endpoint này, tập trung vào xác thực/phân quyền và injection
  nhẹ, KHÔNG sinh lại case Positive/Negative/Boundary Value (đã có ở lần sinh Cơ bản riêng).
- THỨ TỰ BẮT BUỘC: làm ĐỦ từng mục trong CHECKLIST Security bên dưới trước, mỗi mục đúng 1 test case,
  rồi mới tới phần "Đề xuất thêm".
- Checklist Security (bắt buộc) - hệ thống tính từ spec. Hệ thống tự đọc authOverride + expectedStatus
  của từng case để chấm (không dựa vào tên):
<if(hasChecklist)>
<checklistText>
<else>
  (Endpoint không yêu cầu xác thực, hoặc mọi mục đã có test case sẵn - KHÔNG sinh case thiếu/sai token.)
<endif>
  Case thiếu token đặt authOverride = "NONE"; case token sai/hết hạn đặt authOverride = "INVALID"; cả
  2 kỳ vọng expectedStatus = 401, requestBody/tham số giữ hợp lệ để lỗi chỉ đến từ xác thực.
- Đề xuất thêm cho loại Security (sau khi xong checklist, không bắt buộc, tối đa 2 case):
  Injection nhẹ - chèn chuỗi nghi injection (ví dụ chuỗi SQL injection dạng "' OR '1'='1", hoặc chuỗi
  script XSS đơn giản chèn thẻ script cùng lệnh alert) vào 1 field kiểu string trong requestBody - đặt
  authOverride = "DEFAULT" (dùng auth thật bình thường), kỳ vọng expectedStatus = 400 (API phải từ chối
  do validation dữ liệu đầu vào). TUYỆT ĐỐI KHÔNG kỳ vọng 500 - mục tiêu là xác nhận API validate input
  đúng cách (400), không phải xác nhận server bị lỗi.
- Tên test case: case theo checklist ghi "Security - [nội dung]"; case đề xuất thêm ghi "Đề xuất thêm -
  Injection - [nội dung]". Mỗi case vẫn cần đủ: mô tả rõ mục đích kiểm thử, requestHeaders/requestBody
  như bình thường, expectedStatus, và field authOverride (bắt buộc phải có, đúng 1 trong 3 giá trị
  "DEFAULT"/"NONE"/"INVALID" như hướng dẫn trên - không được để trống/null).
- KHÔNG tự thêm header "Authorization" (hay bất kỳ header xác thực nào khác) vào requestHeaders cho
  BẤT KỲ case Security nào, kể cả case authOverride="DEFAULT" - hệ thống tự gắn đúng header xác thực
  dựa vào giá trị authOverride, tự thêm vào requestHeaders sẽ tạo header trùng tên gây sai kết quả.
- Không tự bịa thêm field ngoài định nghĩa OpenAPI đã cho.
