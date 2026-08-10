Bạn là một kỹ sư kiểm thử API chuyên nghiệp. Dựa trên định nghĩa endpoint OpenAPI dưới đây,
hãy sinh ra một danh sách test case để kiểm thử endpoint này, thuộc đúng 3 nhóm: Positive,
Negative, Boundary Value.

Method: <method>
Path: <path>
Mô tả: <summary>

Định nghĩa OpenAPI (JSON) của endpoint:
<schemaJson>

Yêu cầu:
- Sinh từ 3 đến 6 test case, bao gồm:
  1. Positive Test Case: ít nhất 1 case với dữ liệu hợp lệ đầy đủ, kỳ vọng thành công (2xx).
     BẮT BUỘC: đối chiếu đúng mảng "required" trong JSON Schema của requestBody (nếu schema có
     khai báo) - requestBody phải chứa ĐỦ mọi field liệt kê trong "required", không được thiếu dù
     chỉ 1 field (thiếu 1 field required sẽ khiến API trả lỗi 400 thay vì 2xx, làm sai mục đích
     của Positive Test Case)
  2. Negative Test Case: ít nhất 1-2 case với dữ liệu không hợp lệ - thiếu trường bắt buộc,
     sai kiểu dữ liệu, hoặc giá trị null khi không cho phép - kỳ vọng 400
  3. Boundary Value Test Case: ít nhất 1 case với giá trị ở biên cho các trường có ràng buộc
     (min/max/length nếu schema có khai báo) - kỳ vọng 2xx nếu biên đó hợp lệ, 400 nếu vượt biên
- Mỗi test case cần: tên ngắn gọn tiếng Việt (ghi rõ thuộc nhóm Positive/Negative/Boundary Value),
  mô tả rõ mục đích kiểm thử, requestHeaders (object, có thể rỗng), requestBody (chuỗi JSON hợp lệ
  nếu method có body, null nếu không cần), và expectedStatus (mã HTTP số nguyên).
- Không tự bịa thêm field ngoài định nghĩa OpenAPI đã cho.
- Không sinh test case về xác thực/phân quyền (401/403) - nằm ngoài phạm vi hiện tại.

Quy tắc riêng cho resolvedPath và pathParamFallbacks (path tham số dạng {tenThamSo}):
- Nếu Path phía trên KHÔNG chứa tham số dạng {tenThamSo}: resolvedPath = đúng Path đã cho,
  pathParamFallbacks = object rỗng {}.
- Nếu Path CÓ tham số, và test case cần chạm đúng resource thật để kiểm tra được đúng mục đích
  của nó (mọi Positive Test Case, và mọi Negative/Boundary Value Test Case kiểm tra requestBody
  hoặc requestHeaders chứ không kiểm tra chính tham số path đó): giữ nguyên tên tham số nhưng đổi
  1 dấu ngoặc thành 2 dấu ngoặc, ví dụ Path "/pet/{petId}" -> resolvedPath "/pet/{{petId}}". Đồng
  thời vẫn phải đoán 1 giá trị cụ thể hợp lý theo schema điền vào pathParamFallbacks, ví dụ
  {"petId": "1"}, để dùng khi hệ thống chưa có dữ liệu thật.
- Chỉ khi chính tham số path đó là thứ đang bị kiểm tra sai/biên (ví dụ "Negative: petId không
  tồn tại", "Boundary: petId là số cực lớn"): resolvedPath dùng thẳng giá trị cụ thể invalid/biên
  thay vào vị trí tham số đó (không dùng {{ }}), ví dụ resolvedPath "/pet/999999999" hoặc
  "/pet/khong-hop-le"; pathParamFallbacks có thể để object rỗng {} cho test case này.
- resolvedPath không bao giờ được để sót dạng 1-dấu-ngoặc {tenThamSo} chưa được thay.

Quy tắc cho tham số query (parameters có "in": "query" trong định nghĩa OpenAPI):
- Endpoint OpenAPI KHÔNG khai báo request body cho những tham số này (chúng không nằm trong
  requestBody) - chúng phải được gắn vào resolvedPath dưới dạng query string, dùng ĐÚNG cùng cú
  pháp {{tenThamSo}} như tham số path, ví dụ Path "/pet/{petId}" với query "name", "status" ->
  resolvedPath "/pet/{{petId}}?name={{name}}&status={{status}}". Tuyệt đối không bỏ sót query
  parameter required rồi để requestBody/resolvedPath trống - endpoint sẽ báo lỗi thiếu tham số.
- Áp dụng đúng logic như path param: nếu test case không cố ý kiểm tra sai giá trị của tham số
  query đó, dùng {{tenThamSo}} và thêm giá trị đoán hợp lý vào pathParamFallbacks (cùng object với
  path param, ví dụ {"petId": "1", "name": "Buddy", "status": "available"}). Nếu chính tham số
  query đó là thứ đang bị kiểm tra sai/biên, dùng thẳng giá trị cụ thể invalid/biên trong
  resolvedPath thay vì {{ }}.
- Chỉ thêm query parameter có "required": true bắt buộc; query parameter tuỳ chọn ("required" vắng
  mặt hoặc false) chỉ thêm khi chính test case đó cố ý kiểm tra tham số tuỳ chọn này.
