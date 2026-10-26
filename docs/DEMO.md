# Kịch bản demo (~12 phút, desktop >= 1024px)

**Nguyên tắc:** trên sân khấu KHÔNG gọi AI (không tốn token, không phụ thuộc mạng, không phụ thuộc API key). Mọi bước tốn token đã chạy sẵn trước giờ demo và kết quả đã lưu; dữ liệu tạo bằng chính API của hệ thống, không chèn SQL. Demo gồm 2 phần: **A** (độ bao phủ, nguyên nhân Fail, bug report trên Shop API) và **B** (tài liệu nghiệp vụ → luật → test từ luật → bắt lỗi trên API tuyển dụng).

## Chuẩn bị (làm trước giờ demo, ~15 phút; sau mỗi lần khởi động lại máy phải làm lại bước 1-3)

1. **Docker Desktop + hệ thống:** mở Docker Desktop, chờ chạy, rồi `docker compose up -d --build` (giao diện `http://localhost:3000`, backend 8080).
2. **API đích phần A:** `shop-api-target` ở cổng 8081 (`A:/shop-api-target`).
3. **API đích phần B:** `cd mutant-api && MUTANT_CONTROL=true ./mvnw spring-boot:run` (cổng 8083). Phải có `MUTANT_CONTROL=true` để bật/tắt lỗi cài sẵn.
4. **Seed phần A (0 token):** `python scripts/seed-demo.py --recreate` → tài khoản `demo@aiapi.local` / `demo12345`, project **"Demo - Shop API"** (25 test thủ công, 7 lượt chạy, bug report).
5. **Seed phần B:**
   - Không tốn token: `python scripts/seed-demo-rules.py` → project **"Demo - Luật nghiệp vụ (API tuyển dụng)"** có 10 test gốc (Pass), tài liệu SRS đã tải, 5 luật chờ duyệt (luật nhập tay, không có nút tô sáng trích dẫn).
   - **Khuyến nghị làm 1 lần trước giờ demo** (tốn khoảng **14.000 token** đo thật, trần cứng 25.000): `python scripts/seed-demo-rules.py --with-ai` → luật do AI trích thật, có đoạn trích nguyên văn và nút "Xem trong tài liệu". Script in số token đã dùng.
6. **Kiểm tra trước giờ demo:** đăng nhập tài khoản demo, mở cả 2 project, mở Kết quả thực thi; `curl localhost:8083/__mutants` trả JSON. Token của shop hết hạn sau 7 ngày (chạy lại bước 4 nếu quá hạn). Sau mỗi lần demo phần B, tắt lỗi: `curl -X POST localhost:8083/__reset`.
7. **Bản dự phòng (E4):** quay video toàn bộ kịch bản và chụp màn hình các bước 9-14; nếu Docker/mạng hỏng thì chiếu video và mở `docs/DANH_GIA.md` mục 7B.

## Phần A: độ bao phủ, nguyên nhân Fail, bug report (Project "Demo - Shop API")

| # | Màn hình | Làm gì | Nói gì |
|---|---|---|---|
| 1 | Project "Demo - Shop API" | Chỉ thanh tab | "Hệ thống nhận OpenAPI, tạo test case, chạy rồi **giải thích** vì sao fail. Hôm nay demo phần đã chạy sẵn, không gọi AI." |
| 2 | Tab Test case | Mở 1 test; chỉ chuỗi dữ liệu `{{id}}` (POST → GET → PUT → PATCH → DELETE) | "Người dùng review/sửa trước khi chạy. Id lấy từ kết quả POST thay vì đoán." |
| 3 | Tab Lịch sử → lượt gần nhất | Mở kết quả; bấm từng chip nguyên nhân | "Không phải đỏ hết: mỗi fail có **nguyên nhân kèm bằng chứng, tất định, 0 token**." Ví dụ 404 do id dự phòng, 403 thay vì 401. |
| 4 | Lượt số 4 (token sai) | Mở lượt đó | "Khi token hết hạn, hàng chục test đỏ không có nghĩa API hỏng; hệ thống **không tạo bug**." |
| 5 | Tab Coverage | Chỉ độ bao phủ **theo từng endpoint** và chip độ tin | "Coverage đo checklist EP/BVA suy ra từ spec, không đo code, tính riêng từng endpoint. Kết luận có độ tin CHẮC / NGHI NGỜ." |
| 6 | Tab Bug Report | Mở 1 bug | "Bug report tạo theo mẫu cố định, **không dùng AI** nên không bịa." |

## Phần B: tài liệu nghiệp vụ → luật → test → bắt lỗi (Project "Demo - Luật nghiệp vụ (API tuyển dụng)")

| # | Màn hình | Làm gì | Nói gì |
|---|---|---|---|
| 7 | Trang Endpoint → hộp thoại "Sinh test case", tick **"+ AI bổ sung checklist nghiệp vụ"** | Chỉ nút tải tài liệu, ô chỉ dẫn, nút **"AI gợi ý thêm"** (nhãn GIẢ ĐỊNH; tự bật và khoá khi chưa có tài liệu/chỉ dẫn) và ô ngân sách token. Tick "Chạy thử" rồi bấm → khung giám sát hiện số lô và ước tính | "Spec không nói 'lương tối thiểu ≤ lương tối đa'. Luật đó nằm trong tài liệu nghiệp vụ. AI đọc tài liệu và **chỉ lập checklist để bạn duyệt**, không viết test; nút 'AI gợi ý thêm' cho AI nêu thêm điều tài liệu không nói, nhưng gắn nhãn GIẢ ĐỊNH, không trích dẫn. Chỗ tài liệu thiếu AI hỏi lại chứ không đoán. Ngân sách do người dùng đặt, là **trần cứng**: hết thì dừng. Trích thật tốn khoảng 14.000 token; chạy thử là 0." |
| 8 | Tab **Checklist nghiệp vụ** (đã trích sẵn bằng `--with-ai`) | Mở 1 luật có trích dẫn, bấm **"Xem trong tài liệu"** | "Mỗi luật phải kèm đoạn trích **nguyên văn**, hệ thống kiểm từng chữ và từng con số. Không trích dẫn được thì thành GIẢ ĐỊNH; mục GIẢ ĐỊNH kèm mã lỗi AI gợi ý, người duyệt bấm 'Xác nhận 422' hoặc đổi mã. Mã gợi ý chỉ có hiệu lực khi người duyệt xác nhận." |
| 9 | Luật vừa mở | Bấm **Duyệt** (hoặc "Duyệt N luật có trích dẫn" rồi tick từng luật) | "AI đề xuất, **người quyết định**. Chưa duyệt thì chưa sinh test nào." |
| 10 | Nút "Sinh test từ N luật đã duyệt" | Bấm; mở 1 test vừa sinh | "Test sinh **tất định, 0 token** từ luật đã duyệt: cố ý vi phạm đúng luật, kỳ vọng API từ chối." |
| 11 | Tab Test case → chạy các test từ luật | Chạy; tất cả **Pass** | "Trên bản API chuẩn, 10/10 Pass, 0 Fail giả." |
| 12 | Terminal | `curl -X POST localhost:8083/__mutants -H 'Content-Type: application/json' -d '{"enabled":["M23"]}'` rồi chạy lại các test từ luật | "Em bật một lỗi cài sẵn: API nhận `salaryMin > salaryMax`. Test fail với nhãn **'API nhận dữ liệu vi phạm luật nghiệp vụ'**, độ tin CHẮC." (Lỗi khác để thử: M24 deadline quá khứ, M25 tổng trọng số ≠ 100.) Sau đó `curl -X POST localhost:8083/__reset`. |
| 13 | Hộp thoại "Sinh test case" | Mở, chỉ nhóm **"OWASP probe (0 token)"**, bấm Hủy | "Probe tất định: field lạ (mass assignment), JSON hỏng, sai Content-Type, phương thức TRACE. Chỉ gửi thêm request, không sửa dữ liệu thật." |
| 14 | Hộp thoại Xác thực (project) | Chỉ ô "Mã API trả khi thiếu hoặc sai token" | "API nào trả 403 thay vì 401 thì khai ở đây; đo thật trên API cửa hàng cho thấy Fail giả giảm 24,1% → 11,4% và bắt thêm lỗi xác thực." |
| 15 | `docs/DANH_GIA.md` mục 7B (bảng kết quả) | Chỉ bảng A / B / C và dòng token | "Em không nói AI giỏi: em **đo**. A (checklist, 0 token) không bắt lỗi nghiệp vụ nào; AI đoán (B) tốn khoảng 99.000 token/lượt và không gán đúng lỗi nào thêm; luật có người duyệt (C) tốn khoảng 14.000 token một lần và bắt đúng 4 lỗi nghiệp vụ với 0 Fail giả. Hạn chế ghi ngay trong tài liệu: held-out do AI viết, người gán nhãn là em, chỉ một API." |

## Rủi ro khi demo trực tiếp

- **Phụ thuộc môi trường:** Docker Desktop tắt sau khi khởi động lại máy; mutant-api phải chạy với `MUTANT_CONTROL=true`; Docker đôi khi báo "Network unreachable" tới `host.docker.internal` (đã gặp 1 lần khi đo, làm treo lượt chạy hơn 15 phút). Kiểm tra bằng 1 lượt chạy thử trước giờ demo.
- **Không bấm "Import OpenAPI" ở bước thật** (xoá hết dữ liệu; seed lại mất vài phút). Nếu bị hỏi, mở hộp thoại rồi bấm Hủy: nó báo số lượng sẽ mất và bắt xác nhận.
- **Không chạy "AI bổ sung checklist nghiệp vụ" thật trên sân khấu:** tốn token, cần mạng và API key. Chỉ dùng "Chạy thử" (0 token). Nếu muốn cho xem trực tiếp, đặt ngân sách thấp và nói rõ chi phí.
- **Dọn lỗi cài sẵn** sau bước 12 (`/__reset`), nếu không các lần chạy sau sẽ Fail.
- Chỉ chạy lại một ít test để chứng minh chạy thật; đừng chạy cả project nhiều lần (đổi lịch sử/ổn định).
- Bật "Dọn dữ liệu đã tạo" khi chạy test trên API demo.

## Hạn chế đã biết (nói thẳng khi bị hỏi; chi tiết ở `docs/CAU_HOI_BAO_VE.md`)

- **Số liệu đo:** mutant do nhóm tự gieo trên API nhỏ do nhóm viết; held-out do AI cùng họ viết; người gán nhãn luật là người phát triển; cỡ mẫu nhỏ (15 lỗi held-out, 5 luật). Lỗi H10 được sửa sau khi thấy held-out và đã được ghi rõ. Chưa có API của bên thứ ba. Chưa đo thời gian duyệt luật với người thật (E2), nhưng công cụ đo đã có.
- **Chuẩn trên mọi hệ thống?** Không tuyệt đối: Fail giả phụ thuộc quy ước API (đã đo 1,5% trên API tuyển dụng, 11,4% trên API cửa hàng sau khi khai quy ước).
- **Trích luật bằng AI:** chỉ 9 mẫu luật; không trích được máy trạng thái hay luật "không trùng". Recall 5/7 trên bộ đáp án; mỗi lượt AI đề xuất thêm vài luật bị bộ kiểm chứng tất định loại.
- **Làm sao biết là bug thật?** Black-box không biết chắc. Hệ thống chỉ "nghi kèm bằng chứng và độ tin"; fail nghi do test/môi trường/token/không ổn định bị chặn khỏi bug tự động. Không sửa kỳ vọng cho khớp hành vi sai.
- **Xác thực:** token tĩnh (Bearer, Basic, X-API-Key/header, query string) + nhiều profile vai trò; chưa có đăng nhập động/refresh token/OAuth2.
- **Chi phí AI:** quota theo người dùng/ngày (mặc định 2.000.000), trần cứng mỗi lần trích (mặc định 60.000); chạy nhiều job song song cùng chủ có thể vượt quota ngày vài chục nghìn token.
- **Tích hợp CI/CD** là hướng phát triển (ý tưởng ở `docs/BAO_CAO.md` mục 8), không demo.
- **Giao diện** tối ưu cho màn hình >= 1024px; chưa hỗ trợ điện thoại.
