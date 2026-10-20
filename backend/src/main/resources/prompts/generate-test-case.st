Bạn là một kỹ sư kiểm thử API chuyên nghiệp. Dựa trên định nghĩa endpoint OpenAPI dưới đây,
hãy sinh ra một danh sách test case để kiểm thử endpoint này, làm ĐÚNG theo phần Yêu cầu bên dưới.

Method: <method>
Path: <path>
Mô tả: <summary>

Định nghĩa OpenAPI (JSON) của endpoint:
<schemaJson>

Yêu cầu:
<groupRules>
- BẮT BUỘC: mỗi test case phải có field "rationale" - đúng 1 câu tiếng Việt ngắn gọn (dưới 30 từ)
  giải thích CHÍNH XÁC rule/field nào trong định nghĩa OpenAPI phía trên khiến case này được sinh
  ra, không được nói chung chung kiểu "để kiểm thử endpoint". Ví dụ cụ thể theo từng loại:
  - Negative thiếu field bắt buộc: "Trường 'email' có trong mảng required của requestBody nhưng bị
    bỏ qua để kiểm tra validation."
  - Boundary vượt giới hạn: "Trường 'name' có maxLength=50 trong schema, dùng chuỗi 55 ký tự để
    kiểm tra biên trên."
  - Positive hợp lệ: "Điền đủ 3 field bắt buộc (name, price, stock) đúng kiểu dữ liệu theo schema
    để xác nhận luồng thành công cơ bản."
  - Security thiếu auth: "Endpoint có khai báo security scheme trong spec, kiểm tra hành vi khi gọi
    không kèm token."
- QUAN TRỌNG: giá trị test data (chuỗi Unicode/emoji/dài/injection ở trên) chỉ được đặt trong
  requestBody, KHÔNG BAO GIỜ được chèn vào bên trong cú pháp {{tenThamSo}} của resolvedPath (xem
  quy tắc bên dưới) - nếu 1 giá trị tình cờ chứa ký tự "{{" hoặc "}}", phải đảm bảo nó chỉ nằm
  trong requestBody, không được làm hỏng cú pháp token của resolvedPath.

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
- BẮT BUỘC chốt trước danh sách tham số query cần dùng cho MỖI test case, TRƯỚC KHI viết
  resolvedPath: chỉ gồm (a) mọi query parameter có "required": true, CỘNG (b) đúng tham số tuỳ chọn
  mà chính test case đó đang cố ý kiểm tra (nếu có) - TUYỆT ĐỐI không thêm bất kỳ tham số tuỳ chọn
  nào khác dù endpoint có khai báo, kể cả khi "thêm cho đủ" hay "thêm cho giống ví dụ mẫu bên dưới".
  Đây là lỗi RẤT HAY GẶP, ví dụ cụ thể: endpoint có 2 query param tuỳ chọn "name" và "minPrice", 1
  test case Boundary chỉ cố ý test "minPrice=0" - resolvedPath ĐÚNG là "/api/products?minPrice=0",
  SAI là "/api/products?name={{name}}&minPrice=0" (tự thêm "name" dù case không liên quan gì tới
  "name"). Ngược lại, case Boundary khác cố ý test riêng "name" thì resolvedPath ĐÚNG chỉ có "name",
  KHÔNG được tự thêm kèm "minPrice={{minPrice}}". Case Positive muốn xác nhận NHIỀU query param hợp
  lệ hoạt động đúng cùng lúc là ngoại lệ duy nhất được liệt kê đủ các param liên quan.
- Sau khi đã chốt đúng danh sách tham số cần dùng ở trên (không phải toàn bộ query param của
  endpoint), gắn chúng vào NGAY SAU phần path đã đầy đủ (đã áp dụng đúng quy tắc giữ nguyên segment
  tĩnh phía trên, kể cả segment tĩnh sau tham số path) dưới dạng query string, dùng ĐÚNG cùng cú
  pháp {{tenThamSo}} như tham số path. Ví dụ Path "/pet/{petId}" có query "status" (required) và
  "name" (tuỳ chọn), cho 1 case CHỈ cố ý test "status" -> resolvedPath
  "/pet/{{petId}}?status={{status}}" (KHÔNG có "name" vì "name" không "required" và case này không
  cố ý test "name"). Ví dụ PHỨC TẠP HƠN - Path "/pet/{petId}/stock" (có segment tĩnh "stock" sau
  tham số path) với query
  "quantity" required -> resolvedPath PHẢI là "/pet/{{petId}}/stock?quantity={{quantity}}", KHÔNG
  được bỏ sót "/stock" thành "/pet/{{petId}}?quantity={{quantity}}". Tuyệt đối không bỏ sót query
  parameter required rồi để requestBody/resolvedPath trống - endpoint sẽ báo lỗi thiếu tham số.
- Với mỗi tham số đã được chọn đưa vào resolvedPath ở trên: nếu test case không cố ý kiểm tra sai
  giá trị của chính tham số đó, dùng {{tenThamSo}} và thêm giá trị đoán hợp lý vào
  pathParamFallbacks (cùng object với path param, ví dụ {"petId": "1", "status": "available"}). Nếu
  chính tham số query đó là thứ đang bị kiểm tra sai/biên, dùng thẳng giá trị cụ thể invalid/biên
  trong resolvedPath thay vì {{ }}.

Quy tắc về ngày/giờ trong test data (rất quan trọng, tránh test bị cũ theo thời gian):
- Với field có schema format "date" hoặc "date-time", hoặc tên gợi ý ngày hạn/ngày hiệu lực
  (deadline, dueDate, expiresAt, expiryDate, startDate, endDate, scheduledAt...): MỌI test case mà
  giá trị đó phải HỢP LỆ (Positive, và Negative/Boundary của field KHÁC) PHẢI dùng placeholder ngày
  động, KHÔNG BAO GIỜ ghi cứng một ngày cụ thể (ngày cứng sẽ thành quá khứ và làm API từ chối).
  - format "date" (yyyy-MM-dd): "{{today+30d}}" (ngày trong tương lai), "{{today}}" (hôm nay), "{{today-1d}}" (hôm qua).
  - format "date-time" (ISO 8601, có Z): "{{now+1h}}" (tương lai), "{{now}}" (hiện tại), "{{now-15m}}" (quá khứ).
  - Chỉ test Negative "ngày ở quá khứ" mới dùng "{{today-1d}}" / "{{now-15m}}" hoặc ngày cũ rất xa như "2000-01-01".
  - Placeholder ngày động đặt trong requestBody (hoặc query của resolvedPath), KHÔNG phải tham số path:
    KHÔNG thêm chúng vào pathParamFallbacks, KHÔNG gán dependency. Chỉ dùng đúng các dạng ở trên
    (today/now, dấu + hoặc -, 1-4 chữ số, đơn vị d/h/m; today chỉ nhận d).
