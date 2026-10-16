LOẠI POSITIVE
- Checklist Positive (bắt buộc) - hệ thống tính từ spec: 1 bộ dữ liệu hợp lệ đầy đủ, và (nếu spec có enum)
  mỗi giá trị enum hợp lệ là 1 lớp tương đương riêng nên mỗi giá trị cần 1 test case thành công. Mỗi mục
  đúng 1 test case, dùng ĐÚNG giá trị ghi trong mục, mọi field/tham số khác giữ hợp lệ:
<if(hasChecklist)>
<checklistText>
<else>
  (Mục của loại này đã có test case sẵn được giữ lại - không cần sinh thêm.)
<endif>
- Loại Positive không có phần đề xuất thêm.
