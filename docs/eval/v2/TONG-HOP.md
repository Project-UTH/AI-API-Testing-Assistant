# Đo lại toàn bộ trên hệ thống mới (v2) - 2026-10-26

Lý do đo lại: luồng sinh test đã đổi lớn (AI lập checklist nghiệp vụ theo từng endpoint, người duyệt, rồi hệ thống tất định sinh test; có thêm gợi ý rộng, tìm id đường dẫn B/A). Các số đo cũ (`docs/eval/*.md`) thuộc hệ thống cũ, được thay bằng số liệu thư mục này. Dữ liệu thô: các file `results-*.json`, `rules-*.json`, `suggest-*.json`, `real-normal.json` và log `*.log` cùng thư mục. Mọi số token đều đối chiếu với sổ token trong CSDL (chênh lệch sổ = tổng token job).

## 1. Cấu hình A (tất định, 0 token)

| Hệ thống | Kết quả | Ghi chú |
|---|---|---|
| mutant-api (57 mutant: 42 dev + 15 giữ riêng) | gán đúng lỗi API **6/57 (11%)**, dev 5/42, held-out 1/15; phát hiện (có Fail bất kỳ) 42%; Fail giả trên bản chuẩn 1,5% | Giống hệt số cũ; 2 lượt cho kết quả y hệt (± 0) |
| mutant-shop (15 mutant, MySQL), quy ước không có | 1/15 gán đúng, phát hiện 67%; Fail giả 21,9% | |
| mutant-shop, quy ước 403 | 1/15 gán đúng, phát hiện 73%; Fail giả 11,4% | Quy ước phân quyền đổi thì Fail giả giảm |

Nhóm lỗi nghiệp vụ (BUSINESS_RULE) bằng 0/12 ở cấu hình A: checklist chung không biết luật riêng của API - đây chính là lý do có làn luật nghiệp vụ.

## 2. Cấu hình B (AI viết test nghiệp vụ, đường cũ vẫn giữ trong backend)

- 3 lượt, mutant-api: gán đúng **1/57 (2%)** ở cả 3 lượt (held-out 0/15), phát hiện 24%, **Fail giả 46,4%**.
- Tốn **289.025 token** cho 3 lượt (TB 96.342 token/lượt, 215 giây/lượt).
- Kết luận như bản cũ: AI viết test trực tiếp tốn token, tăng Fail giả và không hơn cấu hình A về lỗi nghiệp vụ.

## 3. Cấu hình C (luật nghiệp vụ trích từ tài liệu, người duyệt)

- Trích luật: 3 lượt, **44.212 token** (14.031 + 15.936 + 14.245), 5-6 luật/lượt từ tài liệu.
- Sau khi gán nhãn và duyệt (C_ALL = A + luật): **gán đúng 5-6/57 (9%)**, held-out **2/15 (13%)** so với 1/15 của A; nhóm BUSINESS_RULE 44% (5-6/12) so với 0% của A.
- Fail giả trên bản chuẩn: 3,0% (chỉ riêng test luật: 0%).
- Giới hạn: người gán nhãn chính là người phát triển; chưa có người duyệt độc lập (E2).

## 4. Gợi ý thường và gợi ý rộng (kịch bản S1: chỉ có spec, không tài liệu)

Mỗi chế độ chạy 3 lượt trên 10 endpoint; gán nhãn từng mục theo đáp án luật thật của mutant-api.

| Chế độ | Mục sinh ra (3 lượt) | Đúng | Độ chính xác | Token (sổ) |
|---|---|---|---|---|
| Gợi ý thường | 17 (5-6/lượt) | 15 | **88%** | 11.771 (3.942 + 3.938 + 3.891) |
| Gợi ý rộng | 31 (9-11/lượt) | 15 | **48%** | 17.050 (4.800 + 6.397 + 5.853) |

- Cả hai chế độ đều ra cùng 5 luật đúng ổn định 3/3 lượt: `deadline ≥ bây giờ` (POST/PUT), `salaryMin ≤ salaryMax` (POST/PUT), tổng `weight` = 100 (rubric).
- Mục sai của chế độ thường: `consent=true → coverLetter` (2 lượt).
- Mục sai thêm của chế độ rộng: `salaryMin/salaryMax → salaryCurrency` (REQUIRES), `[salaryMin, salaryMax]` đi cùng nhau (ALL_OR_NONE), `criteria[].name` không trùng, `consent → coverLetter` (3/3 lượt).
- Kết luận: gợi ý rộng cho **cùng số luật đúng** nhưng nhiều gấp ~2 mục sai và tốn thêm ~45% token; chỉ nên dùng khi người duyệt sẵn sàng lọc. Mặc định giữ gợi ý thường.

## 5. API thật (ai-recruitment-agent, SRS thật, không lỗi cài sẵn)

- 1 lượt gợi ý thường, 22 endpoint: **47.396 token** (sổ khớp job).
- Phát hiện lỗi thật của API: `POST /api/hr/jobs` trả **401 UNAUTHENTICATED** khi `salaryMin > salaryMax` dù token hợp lệ (đúng ra là 400).
- Kiểm chứng trích dẫn: **3/4 mục có đoạn trích nguyên văn nhưng đoạn trích không nêu luật** - bộ kiểm chứng chỉ bảo đảm đoạn trích có thật trong tài liệu, không bảo đảm nó nói đúng luật. Vì vậy người duyệt phải đọc đoạn trích (đã nêu trong DANH_GIA).
- Cỡ mẫu rất nhỏ (1 API, 1 lượt) nên không suy ra tỉ lệ chung.

## 6. Tổng token đã dùng cho đợt đo v2

| Khoản | Token |
|---|---|
| API thật (gợi ý thường) | 47.396 |
| Cấu hình B (3 lượt) | 289.025 |
| Cấu hình C trích luật (3 lượt) | 44.212 |
| Gợi ý thường S1 (3 lượt) | 11.771 |
| Gợi ý rộng S1 (3 lượt) | 17.050 |
| Cấu hình A (mọi hệ thống) | 0 |
| **Tổng** | **409.454** (trong mức ~470.000 đã duyệt) |

Lưu ý đo: lượt A của mutant-shop chạy đồng thời với lượt API thật nên chênh lệch sổ hiện 47.396 ở lượt đó; bản thân cấu hình A dùng 0 token.

## 7. Hạn chế cần nói thật

- Bộ kiểm chứng không kiểm đoạn trích có nêu luật hay không (3/4 trên API thật, 2/5 ở đo trước).
- Chỉ 10 mẫu luật; chuỗi id chỉ theo `$.id`.
- Mẫu nhỏ ở làn gợi ý và API thật; người gán nhãn là người phát triển; chưa có người duyệt độc lập (E2).
- Chưa có video demo; chưa tạo PR; nhánh chưa push.
- Tài khoản eval@aiapi.local vẫn giữ các project đo cũ (chỉ xoá project của demo@aiapi.local theo yêu cầu).
