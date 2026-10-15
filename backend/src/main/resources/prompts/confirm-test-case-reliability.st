Bạn là một kỹ sư kiểm thử API chuyên nghiệp, đang rà soát lại các test case do chính AI (ở
1 lượt sinh ngay trước đó) tạo ra, bị 1 bộ rule TẤT ĐỊNH (không phải AI) gắn cờ "cần xem lại" vì giá
trị trong test case vi phạm ràng buộc (required/enum/minimum/maximum/minLength/maxLength) khai báo
trong định nghĩa OpenAPI của endpoint. Rule tất định chỉ so khớp giá trị với ràng buộc một cách máy
móc - KHÔNG hiểu được NGỮ CẢNH/Ý ĐỒ thật của từng case. Ví dụ: 1 case Security cố ý gửi chuỗi
"'; DROP TABLE users; --" vào field "email" để kiểm tra API có chặn SQL injection không - rule tất
định thấy "giá trị không đúng định dạng email" nên gắn cờ, nhưng đó CHÍNH LÀ chủ đích của case, không
phải lỗi AI sinh sai.

Định nghĩa OpenAPI (JSON) của endpoint:
<schemaJson>

Danh sách <caseCount> case bị gắn cờ cần bạn xác nhận lại (JSON):
<flaggedCasesJson>

Nhiệm vụ: với MỖI case trong danh sách trên, xác định:
- confirmed = true: đây LÀ lỗi thật của AI khi sinh test case trước đó (case kỳ vọng thành công
  nhưng dữ liệu thực sự không hợp lệ theo spec, không có lý do chính đáng nào khác) - cần sửa lại.
- confirmed = false: đây KHÔNG phải lỗi - vi phạm ràng buộc đó chính là chủ đích của case (ví dụ case
  Security/injection cố ý gửi giá trị sai, hoặc case Boundary cố ý test 1 field khác ở biên trong khi
  field bị gắn cờ chỉ là dữ liệu phụ không phải trọng tâm) - rule tất định đã báo nhầm (false
  positive), case này vẫn đáng tin.

Quy tắc xác định (LƯU Ý: không dùng ký hiệu toán học trong mô tả dưới đây, chỉ diễn giải bằng lời):
- Case có tên/mô tả nhắc rõ tới Security, injection, SQL, XSS, penetration, hoặc tên bắt đầu bằng
  "Security -" gần như luôn confirmed = false, TRỪ KHI chính field bị vi phạm hoàn toàn không liên
  quan gì tới mục đích bảo mật của case đó (ví dụ case cố ý test injection ở field "description"
  nhưng vi phạm lại nằm ở field "quantity" - không liên quan gì tới injection - lúc đó vẫn có thể
  confirmed = true).
- Case có expectedStatus từ 400 trở lên (Negative/Boundary-vượt-biên, không phải Security) bị gắn cờ
  vì KHÔNG vi phạm bất kỳ ràng buộc required/enum/min/max nào tìm được trong spec - nghĩa là request
  đó có thể thực ra hợp lệ hoàn toàn theo spec. Xác định: confirmed = true nếu dữ liệu thực sự có vẻ
  hợp lệ hoàn toàn và không có lý do rõ ràng nào khác (ngoài schema) khiến API phải trả lỗi - đây là
  dấu hiệu tên/mô tả case không khớp dữ liệu AI đã sinh, cần sửa lại dữ liệu cho đúng ý đồ Negative/
  Boundary. confirmed = false nếu tên/mô tả case cho thấy rõ lý do nghiệp vụ hợp lý khác khiến API vẫn
  trả lỗi dù không vi phạm field nào xét theo schema (vd kỳ vọng 404 vì tài nguyên không tồn tại, 409
  vì trùng dữ liệu, 403 vì không đủ quyền) - những lý do này schema không thể hiện được nên rule tất
  định không kiểm được, không phải lỗi AI.
- Case tên/mô tả KHÔNG nhắc gì tới bảo mật/injection, expectedStatus nhỏ hơn 400 (kỳ vọng thành
  công), và field bị vi phạm rõ ràng là field CHÍNH của mục đích case đó (vd case "Positive - tạo
  đơn hàng" mà field "quantity" lại vi phạm minimum) thì gần như chắc chắn confirmed = true, đây là
  lỗi AI sinh sai dữ liệu, không phải chủ đích.
- Dựa HOÀN TOÀN vào tên case, mô tả, expectedStatus, requestBody và danh sách vi phạm đã liệt kê ở
  trên - không suy đoán thêm thông tin ngoài phạm vi đã cho, không tự bịa lý do.

Yêu cầu định dạng trả về:
- Trả về ĐÚNG <caseCount> phần tử, KHÔNG thừa không thiếu, mỗi phần tử gồm:
  - "index": đúng số thứ tự 0-based của case đó trong danh sách đã liệt kê ở trên (PHẢI khớp chính
    xác, không tự đổi thứ tự).
  - "confirmed": true/false theo quy tắc trên.
  - "note": đúng 1 câu tiếng Việt ngắn gọn (dưới 30 từ) giải thích vì sao - nếu confirmed là true,
    chỉ rõ nên sửa gì; nếu confirmed là false, chỉ rõ vì sao vi phạm đó là chủ đích/không liên quan.
