# E2: đo người duyệt luật nghiệp vụ (cần người thật, CHƯA ĐO)

*Viết lại 2026-10-26 theo luồng hiện tại (AI trích luật có trích dẫn → người duyệt → test sinh tất định). Bản cũ đo "duyệt test AI nghiệp vụ đoán", luồng đó không còn là đường chính.*

**Trạng thái: chưa đo.** Mọi ô kết quả bên dưới để trống, không điền số ước đoán. Phép đo C ở `docs/DANH_GIA.md` mục 7B **giả định người duyệt cẩn thận** (duyệt đúng các luật đúng); E2 là phép đo thay cho giả định đó.

## 1. Câu hỏi cần trả lời

1. **Thời gian:** người duyệt mất bao lâu cho mỗi luật?
2. **Tác dụng của trích dẫn:** luật có đoạn trích nguyên văn (và nút "Xem trong tài liệu") được duyệt nhanh hơn và chính xác hơn luật không có trích dẫn?
3. **Độ chính xác:** người duyệt có **loại được luật sai** không, và có **duyệt nhầm** luật sai hay **loại nhầm** luật đúng không?

Câu 3 là điểm yếu lớn nhất của phép đo hiện tại: nếu người duyệt hay duyệt nhầm luật sai thì test sinh ra sẽ cho Fail giả, và con số "C: 0% Fail giả" không còn đúng với người dùng thật.

## 2. Thiết kế

**Trong từng người (mỗi người làm cả 2 điều kiện), đổi thứ tự luân phiên** (người 1: CITED trước, người 2: PLAIN trước, người 3: CITED trước, ...) để giảm hiệu ứng học.

| Điều kiện | Project | Nội dung 9 luật |
|---|---|---|
| **CITED** | `python scripts/seed-demo-rules.py --with-ai --e2 --email e2@aiapi.local --password <mật khẩu> --name "E2-P<n>-CITED"` | 5 luật do AI trích thật, **có trích dẫn và nút "Xem trong tài liệu"** (cả 5 đúng) + 4 luật nhập tay không trích dẫn |
| **PLAIN** | `python scripts/seed-demo-rules.py --e2 --email e2@aiapi.local --password <mật khẩu> --name "E2-P<n>-PLAIN"` | CÙNG 5 luật nhưng **không trích dẫn** (nhập tay) + cùng 4 luật nhập tay |

4 luật nhập tay (đáp án do script in ra, **không đưa cho người tham gia**): **C1** "số phần tử criteria ≤ 20" (đúng), **C2** "số phần tử criteria ≥ 1" (đúng), **W1** "salaryMax trong khoảng [-, 5000]" (SAI: tài liệu không nêu trần lương), **W2** "deadline ≥ bây giờ + 30 ngày" (SAI: tài liệu nói hạn nộp "chọn đúng ngày hôm nay vẫn được chấp nhận"). Người tham gia phải đối chiếu với tài liệu `docs/eval/srs-mutant-api.md` (đưa tài liệu cho họ ở cả 2 điều kiện: ở CITED có thể dùng nút "Xem trong tài liệu", ở PLAIN phải tự mở tệp).

**Người tham gia:** ít nhất 3, tốt nhất 5; nên trộn vai (sinh viên, giảng viên, người làm QA). Mỗi người khoảng 25 phút cho cả 2 điều kiện.

## 3. Chi phí token (cần bạn duyệt trước khi chạy)

Mỗi project CITED gọi AI thật 1 lần để trích luật: **≈ 14.200 token** (đo thật G5: 14.066 / 13.876 / 14.622), trần cứng 25.000. 3 người ≈ **42.600**, 5 người ≈ **71.000** token (trần cứng 75.000 và 125.000). Project PLAIN và mọi bước chấm: **0 token**. Sau khi chạy đối chiếu sổ token trước/sau như các đợt đo trước.

## 4. Quy trình mỗi phiên

**Trước phiên (người điều hành, không có mặt người tham gia):**
1. Docker Desktop + `docker compose up -d`; `cd mutant-api && MUTANT_CONTROL=true ./mvnw spring-boot:run` (script seed cần mutant-api để dựng project).
2. Dựng 2 project cho người đó (bảng mục 2), dùng tài khoản riêng cho cuộc đo (vd `e2@aiapi.local`), mã người tham gia **P1, P2, ...** (không ghi tên thật vào dữ liệu).
3. Ghi lại đáp án in ra của script vào nơi riêng.

**Hướng dẫn nói với người tham gia (đọc nguyên văn, giống nhau mọi người):**
> "Hệ thống đề xuất một danh sách luật nghiệp vụ lấy từ tài liệu này. Việc của bạn là quyết định từng luật: **Duyệt** nếu luật đúng theo tài liệu, **Loại** nếu sai hoặc tài liệu không nói, **Sửa** nếu gần đúng. Không có đáp án nào cố ý dễ hơn đáp án khác. Làm theo tốc độ bình thường, không cần vội. Bạn dừng bất cứ lúc nào cũng được. Phần mềm chỉ đo thời gian bạn xem từng luật và quyết định của bạn, không ghi chuột hay bàn phím. Bạn có đồng ý được đo không?"

**Trong phiên:**
1. Người tham gia đọc và **bật công tắc "Đo thời gian duyệt luật"** ở tab Nghiệp vụ (mặc định tắt; khi bật có dòng thông báo công khai). Không bật thì không được đo.
2. Mở project điều kiện thứ nhất, tab Nghiệp vụ. Người tham gia duyệt/loại/sửa từng luật bằng các nút trên từng luật. **Không dùng "Duyệt hàng loạt"** (không được đo); nếu người tham gia dùng, ghi chú lại.
3. Hết 9 luật thì báo; sang điều kiện thứ hai.
4. Người điều hành **không gợi ý đáp án**, chỉ ghi chú vào phiếu (mục 6): chỗ do dự, câu hỏi họ hỏi, lúc họ mở tài liệu.

**Sau phiên (chấm, 0 token):** với mỗi project:
```bash
python scripts/e2-score.py --project <id> --email e2@aiapi.local --password <mật khẩu> --out docs/eval/e2-P<n>-<CITED|PLAIN>.json
```
Script đọc quyết định cuối của từng luật, so với đáp án, và lấy số liệu thời gian từ công cụ đo trong giao diện. Chạy **trước khi** người tham gia sang điều kiện kia cũng được, miễn mỗi project được chấm đúng 1 lần.

## 5. Chỉ số

Mỗi phiên cho các số sau (đều từ `e2-score.py`):

- **Quyết định đúng** (trên 9 luật), **duyệt nhầm luật sai** (W1/W2 bị duyệt; nghiêm trọng nhất), **loại nhầm luật đúng**, **chưa quyết định**.
- **Thời gian**: số quyết định, tổng và **trung vị** giây mỗi quyết định; tách theo luật có/không có trích dẫn và theo loại quyết định. Lưu ý: công cụ đo gộp theo luật nhưng không có id luật (ẩn danh), nên không gán được thời gian cho từng luật riêng lẻ.

Tổng hợp (sau khi có ít nhất 3 người): trung vị thời gian mỗi quyết định ở CITED và ở PLAIN; số luật sai bị duyệt nhầm ở mỗi điều kiện (trên tổng 2 × số người luật sai); số luật đúng bị loại nhầm. **Chỉ mô tả, không kiểm định thống kê**: cỡ mẫu quá nhỏ.

## 6. Bảng kết quả (để trống đến khi đo)

**Bảng theo người** (mỗi người 2 dòng):

| Mã | Vai (SV/GV/QA) | Điều kiện (thứ tự) | Quyết định đúng /9 | Duyệt nhầm luật sai /2 | Loại nhầm luật đúng /7 | Chưa quyết định | Tổng giây | Trung vị giây/quyết định | Dùng duyệt hàng loạt? | Ghi chú |
|---|---|---|---|---|---|---|---|---|---|---|
| | | | | | | | | | | |
| | | | | | | | | | | |
| | | | | | | | | | | |
| | | | | | | | | | | |
| | | | | | | | | | | |
| | | | | | | | | | | |

**Bảng tổng hợp:**

| Chỉ số | CITED | PLAIN |
|---|---|---|
| Số người | | |
| Trung vị giây / quyết định | | |
| Luật sai bị duyệt nhầm (tổng / tối đa) | | |
| Luật đúng bị loại nhầm (tổng / tối đa) | | |

## 7. Giới hạn phải ghi khi báo cáo

- Cỡ mẫu nhỏ (3-5 người), không có kiểm định thống kê; người tham gia biết dự án nên có thiên lệch; người điều hành cũng là người phát triển, không mù.
- Hiệu ứng học dù đã đổi thứ tự; cả hai điều kiện dùng cùng tài liệu và cùng luật.
- **Cả 5 luật AI trích đều đúng** nên phép đo **không kiểm tra được trường hợp trích dẫn sai nhưng trông thuyết phục**; luật sai duy nhất là 2 luật nhập tay không có trích dẫn. Kết luận về "trích dẫn giúp phát hiện luật sai" vì vậy bị giới hạn.
- Công cụ đo tính thời gian luật nằm trong khung nhìn khi tab đang mở và có focus, **không** đo thời gian đọc thực sự (người dùng có thể nhìn đi chỗ khác); không đo duyệt hàng loạt.
- Tài liệu ngắn (khoảng 16.000 ký tự) và do AI viết, có thể dễ duyệt hơn tài liệu thật.
- Trong điều kiện PLAIN, ghi chú của 5 luật ghi "Luật đã được AI trích..." nên người tham gia biết nguồn; đã cố định lời hướng dẫn để không nhấn mạnh điều này.
- Đây là phép đo người duyệt, không phải đo chất lượng test sinh ra (đã có ở `docs/DANH_GIA.md` mục 7B).

## 8. Quyền riêng tư và đạo đức

Công cụ đo mặc định **tắt**, chỉ bật khi người tham gia đồng ý; chỉ ghi thời gian có focus, quyết định, nguồn/mẫu luật và việc có trích dẫn; **không** ghi chuột, bàn phím, nội dung luật hay danh tính; xuất dữ liệu ẩn danh. Dùng mã P1, P2, ... trong mọi file; không lưu tên thật cùng dữ liệu. Người tham gia được dừng bất cứ lúc nào và dữ liệu của họ bị xoá nếu họ yêu cầu (xoá project của người đó).
