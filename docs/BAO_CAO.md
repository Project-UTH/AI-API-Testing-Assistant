# AI API Testing Assistant: tài liệu báo cáo hợp nhất

> Tài liệu này gom bài toán, mục tiêu, kiến trúc, phương pháp, kết quả đo và hạn chế vào một chỗ để làm khung cho báo cáo đồ án. Chi tiết và số liệu gốc nằm ở các tài liệu được trỏ tới; **nếu một con số ở đây khác file JSON trong `docs/eval/` thì file JSON là đúng.** Cập nhật 2026-10-26.

## 1. Bài toán

Kiểm thử API REST thủ công tốn công và dễ bỏ sót: người kiểm thử phải đọc spec, tự nghĩ ca biên, ca sai kiểu, ca thiếu quyền, rồi chạy, rồi đoán vì sao Fail (lỗi của API, hay do dữ liệu test, token, môi trường?). Các công cụ sẵn có thường thiên về một phía: fuzz sinh nhiều request nhưng không giải thích; AI sinh test nhanh nhưng **đoán**, đặc biệt ở luật nghiệp vụ mà spec không khai báo; và không công cụ nào trả lời được câu hỏi "**số test đã đủ chưa**".

**Nguyên tắc cốt lõi của đồ án:** kiểm thử để **phát hiện** lỗi của API đích; **không sửa test hay kỳ vọng cho khớp hành vi sai** để test Pass (vd API trả 401 thay vì 400 là phát hiện thật, nhãn `API_WRONG_STATUS`). AI là trợ lý có kiểm chứng; hệ thống tất định đảm bảo chất lượng; người dùng là người quyết định.

## 2. Mục tiêu và phạm vi

- Import OpenAPI/Swagger, sinh test case theo hướng **hybrid** (mục 4), cho người dùng review/sửa trước khi chạy, thực thi test, phân loại nguyên nhân Fail kèm độ tin, hỗ trợ bug report.
- Trả lời được, bằng số liệu, ba câu hỏi: hệ thống bắt được bao nhiêu lỗi, tốn bao nhiêu (token), và "đủ" nghĩa là gì (ba trục độc lập, mục 6).
- Phạm vi: giao diện desktop >= 1024px; xác thực đích bằng token tĩnh (Bearer, Basic, API key, query string) và nhiều profile vai trò. **Ngoài phạm vi:** OAuth/refresh token, fuzzing quy mô lớn, máy trạng thái, kiểm tra BOLA, chạy trong pipeline CI/CD (các mục này là hướng phát triển, mục 8), tự host mô hình AI.

## 3. Kiến trúc

**Công nghệ.** Backend Java, Spring Boot 4, Spring AI (Anthropic), Spring Security (JWT), MySQL, Swagger Parser, Rest Assured. Frontend React (Vite), Tailwind CSS, shadcn/ui, TanStack Query. Chạy bằng Docker Compose.

**Mô hình dữ liệu cốt lõi.** `User` → `Project` → `Endpoint` → `TestCase` → `TestResult` ← `TestExecution`. Thêm: `GenerationJob`/`GenerationJobStep` (một lần bấm "Sinh test", ghi từng bước hệ thống/AI để giám sát), `BusinessRule` (luật nghiệp vụ, có nguồn và trạng thái duyệt), `RequirementDocument` (tài liệu nghiệp vụ), `TestGenerationEvent` (sổ token cấp project), `BugReport`.

**Phân lớp backend.** Controller mỏng → Service (toàn bộ nghiệp vụ) → Repository; DTO tách khỏi entity; mọi phản hồi theo một hợp đồng chung. Các nhóm service chính: `service/` (import, checklist, độ bao phủ, thực thi, bug report), `service/ai/` (gọi mô hình), `service/rule/` (luật nghiệp vụ, tài liệu, kiểm chứng đầu ra AI), `service/execution/` (chạy request thật, phân loại nguyên nhân Fail, probe OWASP), `service/job/` (job nền có giám sát và huỷ).

**Chạy nền.** Sinh test case, trích luật và thực thi test chạy trong job `@Async`; giao diện thăm dò từng bước, người dùng huỷ được; không có giới hạn thời gian cứng cho cả job, nhưng có **ngân sách token cứng** cho phần AI.

**Bảo mật.** Token/khoá của API đích mã hoá AES-256, không ghi log; mọi route (trừ `/auth/*`) cần JWT; route admin chỉ `ADMIN`; chuỗi giống bí mật trong tài liệu bị che trước khi gửi AI; phân quyền theo sở hữu project có test tích hợp thật.

**Prompt AI** chỉ nằm trong `backend/src/main/resources/prompts/*.st`, nạp qua `PromptTemplate`.

## 4. Phương pháp hybrid

| Tầng | Việc | Ai làm | Token |
|---|---|---|---|
| 1 | **1 test gốc hợp lệ** cho endpoint, chạy thật để xác nhận (nếu 4xx thì gửi lỗi thật cho AI sửa vài vòng; không sửa được thì dừng và hỏi người dùng) | AI + chạy thật | có |
| 2 | **Checklist EP/BVA, Security, probe OWASP** suy ra từ spec: thiếu field, null, sai kiểu, biên dưới/trên, enum, thiếu/sai token, field lạ (mass assignment), JSON hỏng, sai Content-Type, phương thức TRACE | Hệ thống tất định | 0 |
| 3 | **Checklist nghiệp vụ** (tick "AI bổ sung checklist nghiệp vụ" trong hộp thoại Sinh test case): **làn 1** AI đọc tài liệu/chỉ dẫn của người dùng và **chỉ đề xuất mục checklist** (luật) theo 9 mẫu, mỗi mục kèm đoạn trích **nguyên văn**, chỗ tài liệu thiếu thì AI **hỏi lại** thay vì đoán; **làn 2 "AI gợi ý thêm"** (tuỳ chọn, tự bật khi không có tài liệu) gợi ý điều tài liệu không nói, gắn nhãn GIẢ ĐỊNH, kèm mã lỗi gợi ý để người duyệt xác nhận hoặc đổi. Kiểm chứng tất định (đoạn trích, hằng số, endpoint, field); người dùng duyệt; test sinh **tất định** từ mục đã duyệt | AI đề xuất, người duyệt, hệ thống sinh | lập checklist có (ngân sách riêng, trần cứng), sinh test 0 |
| 4 | Phân loại nguyên nhân Fail kèm bằng chứng và **độ tin** (CHẮC / NGHI NGỜ / không kết luận) | Hệ thống tất định | 0 |

**Điều khiển rủi ro của AI:** mục không có trích dẫn hợp lệ bị hạ thành GIẢ ĐỊNH (người duyệt phải xác nhận hoặc đổi mã lỗi; mã AI gợi ý chỉ điền sẵn, không có hiệu lực khi chưa xác nhận); làn gợi ý thêm không giới hạn số mục, chỉ giới hạn bằng ngân sách token; duyệt hàng loạt chỉ dành cho luật có trích dẫn; mọi luật cần người duyệt trước khi sinh test; ngân sách token là trần cứng và nằm dưới quota ngày.

**Độ bao phủ** tính theo **từng endpoint** (không gộp cả project) trên checklist suy ra từ spec, tách riêng độ bao phủ luật đã duyệt; không đo bao phủ mã nguồn. **Quy ước API theo project** (vd API trả 403 khi thiếu token) do người dùng khai, chỉ áp dụng cho test cố ý bỏ/làm sai token.

## 5. Hiện thực (đã làm)

Các module 1-14 (nền tảng, project, import, review, thực thi, chuỗi dữ liệu, lịch sử, bug report, admin, mật khẩu, Google, Docker); Module 15-16 (độ bao phủ giải thích được, phân tích nguyên nhân Fail, phát hiện test không ổn định); Module 17 (sẵn sàng cho API thật, đo bằng lỗi cài sẵn) và **17q**: độ bao phủ theo endpoint + độ tin của kết luận, luật nghiệp vụ có trích dẫn (tải tài liệu, AI trích luật, duyệt, đo thời gian duyệt), probe tất định, quy ước API, đo trên hai hệ thống. **Luồng sinh test hiện tại (G3.6-G3.12, 2026-10-26):** mỗi lần "Sinh test case" cho một endpoint chạy theo bốn bước: test gốc Pass (AI sinh, chạy thật), AI lập checklist nghiệp vụ riêng cho endpoint (tài liệu có trích dẫn và/hoặc gợi ý thêm GIẢ ĐỊNH, kèm câu hỏi về chỗ tài liệu thiếu mà người dùng trả lời được), người dùng xem checklist của hệ thống và của AI rồi duyệt trên một trang chính, hệ thống sinh test tất định từ mục đã duyệt rồi chạy. Một trần token tổng cho cả lần chạy; tài liệu lưu theo project (30 tài liệu), khai báo viết cho endpoint nào, gán riêng theo endpoint; hệ thống tự tìm hoặc tự tạo id thật cho tham số đường dẫn (GET danh sách rồi POST tạo, có xác nhận ghi thật) và dừng sớm khi API báo thiếu dữ liệu tiên quyết. Nhật ký chi tiết: `docs/ROADMAP.md`. Kiểm thử: **969 test backend pass**, kịch bản test tay `docs/KICH_BAN_TEST.md`.

## 6. Đánh giá

**Phương pháp đo:** gieo lỗi cài sẵn (mutant) vào một API, bật **từng lỗi riêng**, chạy lại bộ test, và coi là "phát hiện" khi test Pass trên bản chuẩn chuyển Fail. Hai chỉ số: **phát hiện** (kể cả khi nhãn nguyên nhân chưa chắc) và **đúng nhãn** (được gán đúng là lỗi của API). Có lượt đối chứng không bật lỗi để loại nhiễu. Đo trên `mutant-api` (API tuyển dụng 10 endpoint, 42 lỗi dev + 15 lỗi held-out) và `mutant-shop` (API cửa hàng 7 endpoint, 15 lỗi). Bảng số chuẩn: **`docs/DANH_GIA.md` mục 7B** (mục 0 giải thích 3 bộ số).

| | A: checklist (0 token) | B: A + AI đoán nghiệp vụ | C: A + luật AI trích, người duyệt |
|---|---|---|---|
| 42 lỗi dev (đúng nhãn / phát hiện) | 5 / 21 | 5 / 25 | 3 / 3 |
| 10 lỗi held-out mới | 0 / 2 | 0 / 4 | 1 / 2 |
| Fail giả trên bản chuẩn | 1,5% | 19,8% | 0% |
| Token AI | 0 | 296.568 / 3 lượt | 42.564 / 3 lượt |

**Cảnh báo ngay tại bảng:** (1) "đúng nhãn" mới là số đo chặt, "phát hiện" tính cả Fail có nhãn mơ hồ; (2) bộ held-out do một agent thiết kế khi đã đọc đáp án luật (nguồn của SRS và 9 mẫu luật), 3/15 lỗi rơi đúng họ luật cấu hình C hỗ trợ và H10 đã được dùng để sửa hệ thống, nên chưa phải kiểm tra khái quát độc lập; (3) "0% Fail giả" của C là 0 trên khoảng 10 test, API chuẩn, người duyệt mô phỏng (chưa có E2), một API; (4) 3/4 lỗi nghiệp vụ C bắt được (M23-M25) nằm cùng họ luật với đáp án nên là in-sample.

**Đọc kết quả:** checklist A bắt hết lỗi validate và nhiều lỗi mã trạng thái nhưng **không bắt lỗi nghiệp vụ nào**; AI đoán (B) đắt (≈ 99.000 token/lượt) và không thêm lỗi nào được gán đúng nhãn; luật có người duyệt (C) rẻ hơn khoảng 7 lần, 0 Fail giả, bắt đúng 4 lỗi nghiệp vụ. Ba cấu hình bổ sung cho nhau. Trích luật bằng AI: 5 luật khác nhau, ổn định qua 3 lượt, cả 5 đúng; tìm được 5/7 luật mà 9 mẫu biểu diễn được.

**Tổng quát hoá:** trên API cửa hàng (quy ước khác) Fail giả ban đầu 24,1%, còn 11,4% sau khi khai quy ước 403 và sửa nhận diện endpoint đăng nhập; phát hiện 11/15 lỗi. Hai lỗi tổng quát hoá thật được tìm ra nhờ đo hệ thống thứ hai (mục 7A).

**Đo lại toàn bộ trên hệ thống mới (2026-10-26, `docs/eval/v2/TONG-HOP.md` - thay các số đo cũ):** cấu hình A giống hệt số cũ (6/57, 0 token); B 1/57 với Fail giả 46,4% và 289.025 token cho 3 lượt; C (luật từ tài liệu, người duyệt) held-out 2/15 so với 1/15 của A. Làn "AI gợi ý thêm" chỉ từ spec, 3 lượt mỗi chế độ: **gợi ý thường đúng 15/17 mục (88%), 11.771 token; gợi ý rộng đúng 15/31 mục (48%), 17.050 token** - cùng số luật đúng, gợi ý rộng nhiều gấp đôi mục sai. **Chạy thật trên API tuyển dụng của nhóm khác với SRS thật:** 47.396 token, 1 lỗi thật tìm được (`POST /api/hr/jobs` trả 401 thay vì 400 khi `salaryMin > salaryMax`), nhưng 3/4 mục "có trích dẫn" có đoạn trích không nêu luật; không có đáp án nên không tính precision/recall. Mỗi con số là 1-3 lượt, một người gán nhãn. Tổng token đợt đo: 409.454, đối chiếu sổ CSDL.

**Chi phí AI của đợt đo cuối (G5): 339.132 token** (dự đoán 320.106), đối chiếu sổ CSDL trước/sau từng bước. Các phép đo còn lại tốn 0 token.

## 7. Hạn chế (threats to validity)

1. Mutant và API do nhóm tự viết, nhỏ; held-out và tài liệu nghiệp vụ do AI cùng họ viết, không độc lập hoàn toàn (5/15 held-out chồng bộ dev); cỡ mẫu nhỏ, số liệu A tất định nên độ lệch chuẩn 0 không phải bằng chứng thống kê.
2. Người gán nhãn luật là người phát triển hệ thống, biết đáp án; chưa có người chấm thứ hai. Phép đo C mô phỏng người duyệt cẩn thận; **chưa đo người duyệt thật (E2)**.
3. Lỗi H10 được sửa (bộ sinh test thử cả hai phía của giới hạn) **sau khi thấy** kết quả held-out nên không còn là mẫu độc lập; số held-out trong bảng là số trước khi sửa.
4. Chỉ trích luật trên một tài liệu và một hệ thống; luật ngoài 9 mẫu (máy trạng thái, không trùng lặp) không trích được.
5. Chưa có API của bên thứ ba; Fail giả phụ thuộc quy ước API (1,5% đến 24,1%).
6. **Bộ kiểm chứng trích dẫn chỉ kiểm đoạn trích có thật và hằng số khớp, không kiểm đoạn trích có nói đúng luật** (gặp thật ở SRS thật: `salaryMin ≤ salaryMax` gắn với câu về tạo bài đăng; hạn nộp gắn với câu về xoá mềm): người duyệt phải đọc đoạn trích. Chỉ 10 mẫu luật; luật nằm ngoài mẫu (máy trạng thái, phụ thuộc dữ liệu hiện có) không lập được. Nối chuỗi tự động chỉ lấy id qua `$.id`. Làn "AI gợi ý thêm" và chạy trên API thật chỉ 1-3 lượt, prompt mã gợi ý được chỉnh sau khi thấy kết quả. Test gốc cần dữ liệu tiên quyết (vd HR có công ty) hoặc tải tệp thì vẫn cần người.
7. Phụ thuộc phiên bản mô hình và prompt (đổi thì phải đo lại); phần vào của ngân sách token chỉ là ước lượng.

## 8. Hướng phát triển

**Tích hợp CI/CD (ý tưởng).** Cho pipeline (GitHub Actions, GitLab CI, Jenkins) tự chạy bộ test của project mỗi lần đẩy code: pipeline gọi hệ thống bằng một token API gắn với đúng một project, chờ kết quả, tải báo cáo JUnit rồi kết thúc xanh hoặc đỏ theo kết luận. Chỉ Fail do lỗi thật của API làm đỏ pipeline; Fail nghi do dữ liệu test, token hay môi trường chỉ cảnh báo. Chạy tất định, không gọi AI, không tốn token. Hệ thống hiện có nguyên mẫu của ý tưởng này (API chạy bằng token, báo cáo JUnit/HTML) nhưng **chưa nằm trong phạm vi đánh giá hay demo**, nên báo cáo trình bày nó là hướng phát triển chứ không phải kết quả.

Các hướng khác: BOLA (hai profile cùng vai, kiểm tra quyền sở hữu dữ liệu); máy trạng thái và mẫu luật "không trùng"; cấu hình thêm cho API trả 403 với mọi lỗi framework; log lý do từng luật bị bộ kiểm chứng loại; đo thời gian và tỉ lệ duyệt sai với người dùng thật (E2); đo trên API của bên thứ ba; OWASP API4 (kích thước tham số); hỗ trợ tài liệu PDF.

## 9. Chạy lại và tái lập

Hướng dẫn chạy hệ thống: `README.md`. Chạy lại các phép đo: `docs/DANH_GIA.md` mục 8 (cấu hình A/B) và các script `scripts/eval-mutants.py`, `scripts/eval-rules.py`, `scripts/eval-shop-mutants.py`. Kịch bản demo: `docs/DEMO.md`. Câu hỏi bảo vệ dự kiến: `docs/CAU_HOI_BAO_VE.md`.

## 10. Bản đồ tài liệu

| Tài liệu | Nội dung |
|---|---|
| `docs/BAO_CAO.md` (tệp này) | Khung báo cáo hợp nhất |
| `docs/DANH_GIA.md` | Phương pháp đo, kết quả, hạn chế (mục 0 đọc trước; 7B là bộ số chuẩn) |
| `docs/CAU_HOI_BAO_VE.md` | Câu hỏi dự kiến và trả lời có số liệu |
| `docs/DEMO.md` | Kịch bản demo ~12 phút, chuẩn bị, rủi ro, bản dự phòng |
| `docs/KICH_BAN_TEST.md` | Kịch bản test tay phủ mọi module |
| `docs/ROADMAP.md` | Nhật ký tiến độ theo module (lịch sử, dài) |
| `docs/eval/` | Dữ liệu đo (JSON), tài liệu nghiệp vụ mẫu, danh mục mẫu luật |
