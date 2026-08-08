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
  1. Positive Test Case: ít nhất 1 case với dữ liệu hợp lệ đầy đủ, kỳ vọng thành công (2xx)
  2. Negative Test Case: ít nhất 1-2 case với dữ liệu không hợp lệ - thiếu trường bắt buộc,
     sai kiểu dữ liệu, hoặc giá trị null khi không cho phép - kỳ vọng 400
  3. Boundary Value Test Case: ít nhất 1 case với giá trị ở biên cho các trường có ràng buộc
     (min/max/length nếu schema có khai báo) - kỳ vọng 2xx nếu biên đó hợp lệ, 400 nếu vượt biên
- Mỗi test case cần: tên ngắn gọn tiếng Việt (ghi rõ thuộc nhóm Positive/Negative/Boundary Value),
  mô tả rõ mục đích kiểm thử, requestHeaders (object, có thể rỗng), requestBody (chuỗi JSON hợp lệ
  nếu method có body, null nếu không cần), và expectedStatus (mã HTTP số nguyên).
- Không tự bịa thêm field ngoài định nghĩa OpenAPI đã cho.
- Không sinh test case về xác thực/phân quyền (401/403) - nằm ngoài phạm vi hiện tại.
