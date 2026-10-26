# AI API Testing Assistant

Hệ thống kiểm thử API REST: import OpenAPI/Swagger, sinh test case theo hướng **hybrid** (AI chỉ sinh 1 test gốc hợp lệ được chạy thật để xác nhận; hệ thống tất định bù checklist EP/BVA, bảo mật, probe OWASP; checklist nghiệp vụ do AI lập riêng cho từng endpoint từ tài liệu **có trích dẫn nguyên văn** và/hoặc gợi ý thêm gắn nhãn GIẢ ĐỊNH; người dùng xem cả checklist của hệ thống và của AI, duyệt, rồi hệ thống mới sinh test), thực thi, phân loại nguyên nhân Fail kèm độ tin, hỗ trợ bug report. Nguyên tắc: kiểm thử để **phát hiện** lỗi của API đích, không sửa kỳ vọng cho khớp hành vi sai.

Đồ án môn học. Tài liệu báo cáo: [`docs/BAO_CAO.md`](docs/BAO_CAO.md). Kết quả đo: [`docs/DANH_GIA.md`](docs/DANH_GIA.md). Demo: [`docs/DEMO.md`](docs/DEMO.md).

## Chạy hệ thống

Cần Docker Desktop.

```bash
docker compose up -d --build        # MySQL + backend (8080) + frontend (3000)
```

Mở `http://localhost:3000`, đăng ký tài khoản, tạo project, import một OpenAPI (file hoặc URL), chọn endpoint rồi "Sinh test case".

Biến môi trường (đặt trong tệp `.env` cạnh `docker-compose.yml`): **bắt buộc** `DB_PASSWORD`, `JWT_SECRET`, `AES_SECRET` (khoá mã hoá token của API đích); **tuỳ chọn** `ANTHROPIC_API_KEY` (chỉ cần khi dùng AI) và cấu hình mail (quên mật khẩu). Quota token AI mỗi người dùng mỗi ngày là `AI_DAILY_TOKEN_LIMIT` (mặc định 2.000.000, đặt trong môi trường của backend). Không có `ANTHROPIC_API_KEY` thì mọi chức năng tất định (checklist, probe, thực thi, độ bao phủ, test từ luật đã có) vẫn chạy, chỉ phần gọi AI báo lỗi.

## Luồng sử dụng chính (sinh test case cho một endpoint)

1. Import OpenAPI, khai báo Target URL và xác thực (có thể nhiều profile vai trò).
2. Trang Endpoint → "Sinh test case" cho endpoint; tick "+ AI bổ sung checklist nghiệp vụ" nếu muốn AI lập checklist (tải tài liệu ở tab Tài liệu hoặc ngay trong hộp thoại; "AI gợi ý thêm" cho điều tài liệu không nói). Có **một trần token tổng**.
3. Trang chính của lần sinh test: AI sinh test gốc, hệ thống chạy thật xác nhận Pass (cần id thật thì tự thử lấy/tạo), rồi AI lập checklist.
4. Duyệt trên màn Checklist của endpoint (chip "Checklist" trên dòng endpoint): checklist của hệ thống (tick) và của AI (duyệt, xem đoạn trích, xác nhận mã lỗi). AI có thể hỏi lại chỗ tài liệu thiếu: bạn trả lời để nó lập thêm.
5. "Sinh test từ checklist đã duyệt" → tự mở tab Test case, chạy các test vừa sinh; Fail được phân loại nguyên nhân kèm độ tin, có thể tạo bug report.

## Kiểm thử

```bash
cd backend && ./mvnw test           # 943 test; 6 test phân quyền cần MySQL đang chạy
cd frontend && npx tsc -b --noEmit  # kiểm kiểu
```

## Tái lập số liệu đo (0 token trừ khi ghi khác)

```bash
cd mutant-api && MUTANT_CONTROL=true ./mvnw spring-boot:run            # API tuyển dụng có 42 + 15 lỗi cài sẵn, cổng 8083
python scripts/eval-mutants.py --config A --runs 2 --include-heldout   # cấu hình A, 0 token
cd mutant-shop && MUTANT_CONTROL=true ./mvnw spring-boot:run           # API cửa hàng 15 lỗi, cổng 8084, MySQL cổng 3307
python scripts/eval-shop-mutants.py --runs 2 --unauthenticated-status 403
```

Các phép đo có AI (`--config B`, `scripts/eval-rules.py extract`) **tốn token thật**: luôn chạy `--dry-run`/xem dự toán trước. Chi tiết: `docs/DANH_GIA.md` mục 8.

## Cấu trúc

```
backend/     Spring Boot (controller mỏng, service chứa nghiệp vụ, prompt trong resources/prompts/*.st)
frontend/    React + Vite + Tailwind + shadcn/ui
mutant-api/  API tuyển dụng thu nhỏ có lỗi cài sẵn (đo khả năng bắt lỗi)
mutant-shop/ API cửa hàng có lỗi cài sẵn (hệ thống thứ hai)
scripts/     seed dữ liệu demo, các script đo
docs/        báo cáo, đánh giá, demo, kịch bản test, roadmap, dữ liệu đo (docs/eval)
```
