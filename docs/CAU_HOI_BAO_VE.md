# Câu hỏi dự kiến khi bảo vệ và câu trả lời dựa trên bằng chứng

> Đây là các câu hỏi **dự đoán** (do trợ lý soạn ngày 2026-10-20 từ số liệu đã đo), KHÔNG phải câu hỏi thật của giảng viên. Câu hỏi thật hãy dán vào mục cuối để cập nhật câu trả lời.
> Nguồn số liệu: `docs/ROADMAP.md` mục 17h (AI vs hệ thống), 17i (nghiệp vụ), 17j (hướng đi + tài liệu tham khảo đã kiểm chứng).
> Quy tắc trả lời: chỉ nêu điều có số liệu; điều chưa đo thì nói rõ là chưa đo.

## A. Định vị và khác biệt

**1. Hệ thống này khác gì Schemathesis / EvoMaster / RESTler / Postman / Keploy / Testsigma? Vì sao không dùng luôn?**
- Các công cụ fuzz/search-based (Schemathesis, EvoMaster, RESTler, AutoRestTest) mạnh ở *tìm lỗi crash (5xx) và chuỗi trạng thái*; đồ án mạnh ở *giải thích độ bao phủ so với spec, tách nguyên nhân Fail (dữ liệu test / token / kỳ vọng lệch / lỗi API) và quy trình người duyệt*.
- Theo tài liệu công khai khi khảo sát: Keploy chủ yếu ghi/phát lại traffic; Testsigma Copilot cần gắn tag endpoint; Postman Agent Mode là trợ lý chat. Chưa thấy trong mô tả công khai của họ: Coverage Matrix giải thích theo từng mục spec, phân loại nguyên nhân Fail, nhóm "cần xem xét" cho CI. (Chưa dùng thử các công cụ đó nên không khẳng định tuyệt đối.)
- **Chưa so sánh bằng số liệu bắt lỗi** với các công cụ trên: đây là lỗ hổng cần làm bằng E1 (roadmap 17j).

**2. Điểm mới thật sự là gì?**
- Kết hợp có đo đạc: checklist tất định (0 token) + bổ sung từ 1 test đúng của người dùng + lớp nghiệp vụ AI gắn nhãn GIẢ ĐỊNH tách khỏi độ bao phủ + phân loại nguyên nhân Fail. Từng thành phần không mới; cách ghép và dữ liệu đo là đóng góp.
- Công trình gần nhất tìm thấy: RESTifAI (ICSE 2026 demo, arXiv:2512.08706) — cần đọc toàn văn để nêu khác biệt chính xác.

## B. Vai trò của AI

**3. AI đóng góp gì thật sự? Có cần AI không?**
- Phần checklist (bắt buộc, null, biên, enum, bảo mật): **không cần AI**. Đã đo trên `PUT /jobs/{id}`: hệ thống 0,1 giây/0 token/19–25 test; AI 97–121 giây/20–39 nghìn token và sinh nhiều Fail giả (13/25 và 20/24) vì dùng UUID mẫu làm id.
- Phần nghiệp vụ (luật spec không khai): AI có giá trị. Đo trên 1 endpoint: 10 test, 17.472 token, chạm 5/5 luật đã biết, 2/4 Fail là lỗi API thật (min>max trả 401; tiền tệ không validate), 2/4 đoán sai mã (404 vs 400).
- Mẫu nhỏ (1 endpoint nghiệp vụ): chỉ là bằng chứng gợi ý, chưa phải kết luận thống kê.

**4. Tại sao tin kết quả AI? AI có bịa không?**
- Không tin mặc định: mọi test AI nghiệp vụ có nhãn "GIẢ ĐỊNH", người duyệt, không tính vào độ bao phủ; AI không được là oracle duy nhất (2/4 Fail do đoán sai mã). Ví dụ AI bịa: mã danh mục `IT`, `HN` (mã thật `IT_SOFTWARE`, `HA_NOI`), id = UUID mẫu.

**5. Kết quả AI có lặp lại được không?**
- Phần tất định: có (cùng spec, cùng test gốc → cùng kết quả). Phần AI: chưa chạy lặp, chưa ghi nhiệt độ/seed → cần E1 (chạy lặp 3 lần, ghi phiên bản model và prompt).

## C. Chất lượng và bằng chứng

**6. Hệ thống bắt được bao nhiêu lỗi so với baseline?**
- Hiện CHƯA có con số hợp lệ. 670 test backend chứng minh chất lượng mã, không chứng minh khả năng bắt lỗi. Kế hoạch: E1 (demo API gieo 20–30 mutant, so checklist-only / LLM-only / hybrid; metric: tỉ lệ phát hiện theo loại lỗi, Fail giả, token, thời gian, chi phí/lỗi).
- Đã bắt được lỗi thật trên API tuyển dụng (ví dụ lỗi trả 401 khi `salaryMin > salaryMax`) nhưng đây là ví dụ, không phải đo hệ thống.

**7. Tỉ lệ Fail giả (false failure) là bao nhiêu?**
- Đã có số trên 2 endpoint: hệ thống 2/23 và 2/19 (cả hai là lệch spec thật, gắn `TEST_DATA_SUSPECT`); AI sinh checklist 13/25 và 20/34 giả do id. Chưa đo trên mẫu lớn.

**8. Hệ thống có dùng được cho API khác không?**
- Đã chạy trên 4 API khác miền (tuyển dụng 110 endpoint, thư viện giả lập, shop Spring, Swagger 2.0 XML). Mỗi API chỉ 1–2 endpoint phân tích kỹ; đã sửa 7+ lỗi tổng quát hoá (enum MySQL, Swagger 2.0, basePath, form/XML, Basic/header/query auth...). Chưa thử GraphQL/gRPC.

**9. Spec sai thì sao?**
- Checklist phụ thuộc spec: spec sai thì checklist sai. Hệ thống phát hiện *lệch spec–API* ở mức nhất định (ví dụ `minLength: 0` nhưng API từ chối chuỗi rỗng → gắn `TEST_DATA_SUSPECT`), không tự sửa spec.

## D. Kỹ thuật và giới hạn

**10. Thiếu gì so với công cụ học thuật?**
- Chuỗi stateful (create→read→delete, suy luận phụ thuộc từ response kiểu RESTler): mới có Test Data Chaining thủ công. Chưa có fuzzing quy mô lớn, shrinking, retry flaky tự động, body lồng nhau sâu, upload file, đăng nhập tự lấy token.

**11. "Human review" có đo không?**
- Chưa đo thời gian duyệt/tỉ lệ chấp nhận (E2).

**12. Chi phí AI thế nào? Có kiểm soát không?**
- Quota token theo user/ngày (mặc định 500.000, chỉnh bằng `AI_DAILY_TOKEN_LIMIT`), lịch sử token theo lần sinh, timeout chỉnh bằng `AI_TIMEOUT`. AI nghiệp vụ ≈ 17k token/endpoint nên chỉ dùng chọn lọc.

**13. Bảo mật của chính công cụ?**
- Token đích mã hoá AES-256, không log; che giá trị nhạy cảm trong response lưu lại; token CI chỉ lưu SHA-256. Còn thiếu: chặn SSRF khi import URL (P0-2 roadmap).

## E. Câu hỏi thật của giảng viên (dán vào đây)

<!-- Dán nguyên văn từng câu, rồi bổ sung câu trả lời + bằng chứng bên dưới -->

