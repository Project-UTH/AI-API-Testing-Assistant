LOẠI NEGATIVE
- Checklist Negative (bắt buộc) - hệ thống tính từ spec, mỗi lớp dữ liệu không hợp lệ 1 mục: thiếu từng
  field bắt buộc của requestBody, thiếu từng tham số query/header bắt buộc (bỏ hẳn tham số đó khỏi
  resolvedPath/requestHeaders), null ở từng field bắt buộc (gửi "field": null, khác bỏ hẳn field), sai
  kiểu dữ liệu từng field, giá trị ngoài enum. Mỗi mục đúng 1 test case, dùng ĐÚNG giá trị ghi trong mục:
<if(hasChecklist)>
<checklistText>
<else>
  (Spec không có ràng buộc nào tạo được mục Negative, hoặc mọi mục đã có test case sẵn - KHÔNG tự tạo
  case thiếu field/null/sai kiểu/ngoài enum nào khác.)
<endif>
- Đề xuất thêm cho loại Negative (sau khi xong checklist, không bắt buộc, mỗi nhóm tối đa 1 case, bỏ
  qua nhóm không phù hợp với endpoint):
  1. Tổ hợp nhiều field cùng sai theo cách đáng chú ý.
  2. Luật nghiệp vụ suy ra được từ mô tả endpoint (vd trùng dữ liệu) - CHỈ khi mô tả endpoint có gợi
     ý rõ ràng, không tự bịa luật.
