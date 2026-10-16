- Chỉ sinh test case thuộc đúng (các) loại đã được chọn: <selectedGroups> - KHÔNG sinh thêm loại nào
  khác ngoài danh sách này, kể cả khi bạn nghĩ loại đó "cũng nên có".
- THỨ TỰ BẮT BUỘC: (1) sinh 1 case "gốc" hợp lệ; (2) làm ĐỦ từng mục trong CHECKLIST của từng loại
  bên dưới, mỗi mục đúng 1 test case; (3) chỉ SAU KHI xong toàn bộ checklist mới tới phần "Đề xuất
  thêm" (nếu loại đó cho phép).
- Case "gốc" hợp lệ: đối chiếu đúng mảng "required" trong JSON Schema của requestBody (nếu schema có
  khai báo) - requestBody phải chứa ĐỦ mọi field liệt kê trong "required", không được thiếu dù chỉ 1
  field; mọi giá trị đúng kiểu dữ liệu, nằm trong ràng buộc enum/minimum/maximum/minLength/maxLength
  của spec và có ý nghĩa thực tế (vd email đúng định dạng, số điện thoại đúng dạng số điện thoại); kỳ
  vọng thành công (2xx).<if(includePositive)> Case gốc chính là case cho mục "Dữ liệu hợp lệ" trong
  checklist Positive, không sinh trùng.<else> Người dùng không chọn loại Positive - case gốc này chỉ để
  hệ thống dùng làm nền, vẫn BẮT BUỘC phải sinh.<endif>

- CHECKLIST do hệ thống tính sẵn từ spec theo kỹ thuật Equivalence Partitioning + Boundary Value
  Analysis. Mỗi case theo checklist chỉ khác case gốc ĐÚNG 1 chỗ mà mục đó mô tả - mọi field/tham số
  khác giữ nguyên giá trị hợp lệ như case gốc, KHÔNG được sai thêm chỗ nào khác (sai 2 chỗ cùng lúc
  thì API trả lỗi không chứng minh được nó kiểm đúng ràng buộc đang xét). Kỳ vọng (2xx, 400 hay
  lỗi 4xx/404) làm ĐÚNG như ghi ở từng mục. Mục ghi "tham số query"/"tham số path" thì đặt giá trị đó vào resolvedPath
  theo quy tắc tham số bên dưới. Hệ thống sẽ tự đọc NỘI DUNG từng case để chấm (không dựa vào tên),
  nên dữ liệu thật đã điền phải khớp đúng mục.

<categorySections>

- Phần "Đề xuất thêm" CHỈ dành cho tình huống KHÔNG có trong bất kỳ checklist nào ở trên và thuộc
  đúng nhóm được liệt kê trong phần của loại đó. TUYỆT ĐỐI không tự tạo thêm case thiếu field, null,
  sai kiểu, ngoài enum hay giá trị biên nằm ngoài checklist - hệ thống đã tính đủ các tình huống đó.
- Tên test case: case theo checklist ghi "[Loại] - [nội dung]" với Loại là Positive/Negative/Boundary
  Value; case đề xuất thêm ghi "Đề xuất thêm - [tên nhóm] - [nội dung]" để không bị nhầm là case bắt
  buộc. Mỗi case cần: mô tả rõ mục đích kiểm thử, requestHeaders (object, có thể rỗng), requestBody
  (chuỗi JSON hợp lệ nếu method có body, null nếu không cần), và expectedStatus (mã HTTP số nguyên).
- Không tự bịa thêm field ngoài định nghĩa OpenAPI đã cho.
- Không sinh test case về xác thực/phân quyền (401/403) - nằm ngoài phạm vi hiện tại (nhóm đó chỉ
  sinh ở lần gọi riêng dành cho Security, không trộn vào đây).
