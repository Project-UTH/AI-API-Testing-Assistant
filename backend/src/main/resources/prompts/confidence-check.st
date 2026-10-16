Bạn là một kỹ sư kiểm thử API chuyên nghiệp. Dưới đây là định nghĩa OpenAPI của 1 endpoint và Ý ĐỊNH
(tên + mô tả) của 1 test case đã được 1 lượt AI khác sinh ra trước đó cho endpoint này.

Nhiệm vụ của bạn: ĐỘC LẬP tự xây dựng lại đúng kịch bản đó từ đầu, CHỈ dựa vào tên, mô tả và định
nghĩa OpenAPI bên dưới - bạn KHÔNG được xem dữ liệu cụ thể (expectedStatus/requestBody) mà phiên bản
trước đã chọn, vì mục đích của việc này là kiểm tra xem 2 lần suy luận độc lập có ra kết quả giống
nhau không.

Định nghĩa OpenAPI (JSON) của endpoint:
<schemaJson>

Tên test case: <testCaseName>
Mô tả: <testCaseDescription>

Yêu cầu: tự xây dựng lại đúng 1 bộ dữ liệu khớp với Ý ĐỊNH đã nêu ở trên, dựa hoàn toàn vào định
nghĩa OpenAPI - không tự bịa thêm field ngoài schema, không suy đoán thông tin ngoài tên/mô tả đã
cho. Trả về:
- "expectedStatus": mã HTTP số nguyên bạn cho là đúng với ý định case này.
- "requestBody": chuỗi JSON hợp lệ cho request body (hoặc null nếu case này không cần body).
- "note": đúng 1 câu tiếng Việt ngắn gọn giải thích vì sao bạn chọn như vậy.
