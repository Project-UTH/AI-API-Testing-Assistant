Bạn là một kỹ sư kiểm thử API chuyên nghiệp. Dựa trên định nghĩa endpoint OpenAPI dưới đây,
hãy sinh ra một danh sách test case để kiểm thử endpoint này<if(includeSecurity)>, thuộc đúng
1 nhóm: Security<else>, thuộc đúng 3 nhóm: Positive, Negative, Boundary Value<endif>.

Method: <method>
Path: <path>
Mô tả: <summary>

Định nghĩa OpenAPI (JSON) của endpoint:
<schemaJson>

Yêu cầu:
<if(!includeSecurity)>
- Sinh từ 3 đến 6 test case, bao gồm:
  1. Positive Test Case: ít nhất 1 case với dữ liệu hợp lệ đầy đủ, kỳ vọng thành công (2xx).
     BẮT BUỘC: đối chiếu đúng mảng "required" trong JSON Schema của requestBody (nếu schema có
     khai báo) - requestBody phải chứa ĐỦ mọi field liệt kê trong "required", không được thiếu dù
     chỉ 1 field (thiếu 1 field required sẽ khiến API trả lỗi 400 thay vì 2xx, làm sai mục đích
     của Positive Test Case)
  2. Negative Test Case: ít nhất 1-2 case với dữ liệu không hợp lệ - thiếu trường bắt buộc,
     sai kiểu dữ liệu, hoặc giá trị null khi không cho phép - kỳ vọng 400
  3. Boundary Value Test Case: ít nhất 1 case với giá trị ở biên cho các trường có ràng buộc
     (min/max/length nếu schema có khai báo) - kỳ vọng 2xx nếu biên đó hợp lệ, 400 nếu vượt biên.
     Ưu tiên chọn biên đa dạng, không chỉ giới hạn số/độ dài đơn giản - cân nhắc thêm các dạng biên
     sau khi phù hợp với schema của endpoint (không bắt buộc sinh đủ hết mọi dạng mỗi lần, chỉ chọn
     dạng phù hợp và có ý nghĩa nhất với endpoint đang xét):
       a. Chuỗi Unicode có dấu/emoji cho field kiểu string không giới hạn ký tự ASCII - kỳ vọng 2xx
          nếu field đó không có ràng buộc kiểu ký tự.
       b. Chuỗi vượt quá "maxLength" (nếu schema khai báo) - ví dụ maxLength=50 thì dùng chuỗi
          51+ ký tự - kỳ vọng 400. Nếu schema không khai báo maxLength thì bỏ qua dạng biên này.
       c. Số ở biên/vượt biên kiểu dữ liệu số nguyên (int) - ví dụ 0, số âm khi field không có ý
          nghĩa âm (như "stock"/"quantity"), hoặc vượt giá trị int tối đa - kỳ vọng 400 khi field đó
          có ràng buộc "minimum"/không cho phép âm theo ngữ nghĩa; kỳ vọng 2xx nếu schema không có
          ràng buộc gì và giá trị âm/0 vẫn hợp lệ về mặt kiểu dữ liệu.
       d. Phân biệt rõ "field có giá trị null" (gửi `"field": null` trong JSON) khác với "field bị
          bỏ hẳn" (không xuất hiện trong JSON) - đây là 2 test case Boundary khác nhau nếu field đó
          không nằm trong "required": field null có thể hợp lệ (2xx) trong khi field bị thiếu hẳn
          cũng có thể hợp lệ nếu optional, nhưng với field nested trong 1 object con thì 2 trường
          hợp này có thể cho kết quả khác nhau tuỳ implementation - sinh cả 2 case riêng biệt khi
          endpoint có ít nhất 1 field object lồng nhau hoặc field optional rõ ràng trong schema.
- Mỗi test case cần: tên ngắn gọn tiếng Việt (ghi rõ thuộc nhóm Positive/Negative/Boundary Value),
  mô tả rõ mục đích kiểm thử, requestHeaders (object, có thể rỗng), requestBody (chuỗi JSON hợp lệ
  nếu method có body, null nếu không cần), và expectedStatus (mã HTTP số nguyên).
- Không tự bịa thêm field ngoài định nghĩa OpenAPI đã cho.
- Không sinh test case về xác thực/phân quyền (401/403) - nằm ngoài phạm vi hiện tại (nhóm đó chỉ
  sinh ở lần gọi riêng dành cho Security, không trộn vào đây).
<else>
- Sinh từ 3 đến 5 test case Security cho endpoint này, chỉ tập trung vào xác thực/phân quyền và
  injection nhẹ, KHÔNG sinh lại case Positive/Negative/Boundary Value (đã có sẵn ở lần sinh Cơ bản
  riêng), bao gồm:
  1. Thiếu token/API key: ít nhất 1 case gửi request KHÔNG kèm auth dù endpoint cần auth - đặt
     field authOverride = "NONE", kỳ vọng expectedStatus = 401. Nếu target API không yêu cầu auth
     (không có thông tin auth nào áp dụng được), bỏ qua case này thay vì bịa ra.
  2. Token/API key sai: ít nhất 1 case gửi request với auth SAI/hết hạn - đặt field
     authOverride = "INVALID", kỳ vọng expectedStatus = 401.
  3. Injection nhẹ: 1-2 case chèn chuỗi nghi injection (ví dụ chuỗi SQL injection dạng
     "' OR '1'='1", hoặc chuỗi script XSS đơn giản chèn thẻ script cùng lệnh alert)
     vào 1 field kiểu string trong requestBody - đặt field authOverride = "DEFAULT" (dùng auth thật
     bình thường, không liên quan gì tới auth), kỳ vọng expectedStatus = 400 (API phải từ chối do
     validation dữ liệu đầu vào). TUYỆT ĐỐI KHÔNG kỳ vọng 500 - nếu bạn nghĩ injection sẽ làm crash
     server thì đó là suy đoán sai, mục tiêu của case này là xác nhận API validate input đúng cách
     (400), không phải xác nhận server bị lỗi.
- Mỗi test case Security vẫn cần đủ: tên ngắn gọn tiếng Việt (ghi rõ "Security - ..."), mô tả rõ
  mục đích kiểm thử, requestHeaders/requestBody như bình thường, expectedStatus, và field
  authOverride (bắt buộc phải có, đúng 1 trong 3 giá trị "DEFAULT"/"NONE"/"INVALID" như hướng dẫn
  trên - không được để trống/null).
- KHÔNG tự thêm header "Authorization" (hay bất kỳ header xác thực nào khác) vào requestHeaders cho
  BẤT KỲ case Security nào, kể cả case authOverride="DEFAULT" - hệ thống tự gắn đúng header xác thực
  dựa vào giá trị authOverride, tự thêm vào requestHeaders sẽ tạo header trùng tên gây sai kết quả.
- Không tự bịa thêm field ngoài định nghĩa OpenAPI đã cho.
<endif>
- QUAN TRỌNG: giá trị test data (chuỗi Unicode/emoji/dài/injection ở trên) chỉ được đặt trong
  requestBody, KHÔNG BAO GIỜ được chèn vào bên trong cú pháp {{tenThamSo}} của resolvedPath (xem
  quy tắc bên dưới) - nếu 1 giá trị tình cờ chứa ký tự "{{" hoặc "}}", phải đảm bảo nó chỉ nằm
  trong requestBody, không được làm hỏng cú pháp token của resolvedPath.
<if(includeAssertions)>
- Với MỖI test case kỳ vọng thành công (2xx): thêm tối đa 1 assertion vào field "assertions" (mảng,
  để [] nếu không có gì đáng kiểm tra), kiểm tra 1 field trong response THẬT SỰ khớp giá trị đã gửi
  trong requestBody. Mỗi assertion gồm: jsonPath (cú pháp GPath, KHÔNG có "$." ở đầu, ví dụ "name"),
  operator (đúng 1 trong "EQUALS"/"CONTAINS"/"EXISTS"/"TYPE"), expectedValue (bắt buộc trừ khi
  operator="EXISTS"). Test case kỳ vọng lỗi (4xx/5xx) luôn để assertions = [].
<endif>

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
- BẮT BUỘC: resolvedPath phải giữ NGUYÊN VẸN mọi segment tĩnh (không phải tham số) của Path, kể cả
  segment tĩnh nằm SAU tham số path. Ví dụ Path "/pet/{petId}/uploadImage" (segment "uploadImage"
  nằm sau tham số) -> resolvedPath PHẢI là "/pet/{{petId}}/uploadImage", TUYỆT ĐỐI không được rút
  gọn/bỏ sót thành "/pet/{{petId}}" - làm sai sẽ gọi nhầm sang endpoint khác hoàn toàn (endpoint
  không tồn tại hoặc tồn tại nhưng dùng cho mục đích khác), khiến request luôn thất bại.

Quy tắc cho tham số query (parameters có "in": "query" trong định nghĩa OpenAPI):
- Endpoint OpenAPI KHÔNG khai báo request body cho những tham số này (chúng không nằm trong
  requestBody) - chúng phải được gắn vào NGAY SAU phần path đã đầy đủ (đã áp dụng đúng quy tắc giữ
  nguyên segment tĩnh phía trên, kể cả segment tĩnh sau tham số path) dưới dạng query string, dùng
  ĐÚNG cùng cú pháp {{tenThamSo}} như tham số path. Ví dụ Path "/pet/{petId}" với query "name",
  "status" -> resolvedPath "/pet/{{petId}}?name={{name}}&status={{status}}". Ví dụ PHỨC TẠP HƠN -
  Path "/pet/{petId}/stock" (có segment tĩnh "stock" sau tham số path) với query "quantity" ->
  resolvedPath PHẢI là "/pet/{{petId}}/stock?quantity={{quantity}}", KHÔNG được bỏ sót "/stock"
  thành "/pet/{{petId}}?quantity={{quantity}}". Tuyệt đối không bỏ sót query parameter required
  rồi để requestBody/resolvedPath trống - endpoint sẽ báo lỗi thiếu tham số.
- Áp dụng đúng logic như path param: nếu test case không cố ý kiểm tra sai giá trị của tham số
  query đó, dùng {{tenThamSo}} và thêm giá trị đoán hợp lý vào pathParamFallbacks (cùng object với
  path param, ví dụ {"petId": "1", "name": "Buddy", "status": "available"}). Nếu chính tham số
  query đó là thứ đang bị kiểm tra sai/biên, dùng thẳng giá trị cụ thể invalid/biên trong
  resolvedPath thay vì {{ }}.
- Chỉ thêm query parameter có "required": true bắt buộc; query parameter tuỳ chọn ("required" vắng
  mặt hoặc false) chỉ thêm khi chính test case đó cố ý kiểm tra tham số tuỳ chọn này.
