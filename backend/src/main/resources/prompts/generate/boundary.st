LOẠI BOUNDARY VALUE
- Checklist Boundary (bắt buộc) - hệ thống tính từ spec theo Boundary Value Analysis, mỗi biên thử cả
  giá trị đúng biên và giá trị ngay ngoài biên:
  + Biên khai rõ minimum/maximum (kể cả exclusive: giá trị đúng bằng biên exclusive là KHÔNG hợp lệ) và
    minLength/maxLength.
  + Giới hạn của kiểu int32/int64: giá trị nhỏ nhất/lớn nhất của kiểu (hợp lệ) và giá trị vượt ra ngoài
    kiểu (kỳ vọng 400).
  + Biên ngầm khi spec KHÔNG khai ràng buộc: số 0 và số âm khi không có minimum, chuỗi rỗng khi không
    có minLength - spec không cấm nên các mục này kỳ vọng thành công (2xx).
  + Tham số path định danh tài nguyên (vd id): giá trị 0, số âm, lớn nhất của kiểu hợp lệ về kiểu nhưng
    không trỏ tới tài nguyên nào, nên kỳ vọng lỗi 4xx - đặt expectedStatus = 404 và đặt giá trị thẳng
    vào resolvedPath (không dùng {{ }}).
  Mỗi mục đúng 1 test case, dùng ĐÚNG giá trị và kỳ vọng ghi trong mục:
<if(hasChecklist)>
<checklistText>
<else>
  (Spec không có field/tham số nào tạo được mục Boundary, hoặc mọi mục đã có test case sẵn.)
<endif>
- Case biên CHỈ lấy từ checklist trên. KHÔNG tự nghĩ thêm giá trị biên nào khác ngoài các mục đã liệt kê.
- Đề xuất thêm cho loại Boundary (sau khi xong checklist, không bắt buộc, tối đa 1 case):
  1. Dữ liệu đặc biệt cho field kiểu string: chuỗi Unicode có dấu/emoji hoặc khoảng trắng đầu cuối -
     kỳ vọng 2xx nếu schema không có ràng buộc gì cấm.
