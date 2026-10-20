# Câu hỏi dự kiến khi bảo vệ và câu trả lời dựa trên bằng chứng

> Đây là các câu hỏi **dự đoán** (do trợ lý soạn ngày 2026-10-20 từ số liệu đã đo), KHÔNG phải câu hỏi thật của giảng viên. Câu hỏi thật hãy dán vào mục cuối để cập nhật câu trả lời.
> Nguồn số liệu: `docs/ROADMAP.md` mục 17h (AI vs hệ thống, đo trước khi bỏ đường AI sinh toàn bộ), 17i (nghiệp vụ), 17j (hướng đi + tài liệu tham khảo đã kiểm chứng), **17n (hệ thống hiện tại + kiểm chứng thật 2026-10-20)**, 17o (kế hoạch đo độ chính xác).
> Cập nhật 2026-10-20: hệ thống ĐÃ ĐỔI - đường "AI sinh toàn bộ test" bị bỏ (AI sinh sai nhiều), nay AI chỉ sinh 1 test gốc được chạy thật xác nhận; các số 17h bên dưới là lý do của quyết định đó.
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
- **Hiện tại:** AI làm 2 việc có kiểm chứng: (1) sinh 1 test gốc hợp lệ cho mỗi endpoint - hệ thống CHẠY THẬT, nếu 4xx thì gửi lỗi thật cho AI sửa (tối đa 2–5 vòng), nếu AI không sửa được (401/403, 404 id, 5xx, 409 lặp) thì dừng và hỏi người dùng; (2) tuỳ chọn đoán luật nghiệp vụ, gắn nhãn GIẢ ĐỊNH. Đo thật: 5,9–12k token/endpoint cho test gốc, Pass ngay lần 1 ở hầu hết ca; vòng sửa chạy thật ở ca 400 và 409.
- Phần checklist (bắt buộc, null, biên, enum, bảo mật): **không cần AI**. Đã đo trên `PUT /jobs/{id}`: hệ thống 0,1 giây/0 token/19–25 test; AI 97–121 giây/20–39 nghìn token và khi chạy tách riêng test `PUT /{id}` thì có nhiều Fail giả (13/25 và 20/34 là 404) do thiếu id. **Đính chính:** lỗi này do thiếu chuỗi (không có test tạo id), không phải AI không sinh được test hợp lệ; khi nối chuỗi từ test POST thì 0 Fail 404 (7 Pass/4 Fail thật hoặc đoán sai mã).
- Phần nghiệp vụ (luật spec không khai): AI có giá trị. Đo trên 1 endpoint: 10 test, 17.472 token, chạm 5/5 luật đã biết, 2/4 Fail là lỗi API thật (min>max trả 401; tiền tệ không validate), 2/4 đoán sai mã (404 vs 400).
- Mẫu nhỏ (1 endpoint nghiệp vụ): chỉ là bằng chứng gợi ý, chưa phải kết luận thống kê.

**4. Tại sao tin kết quả AI? AI có bịa không?**
- Không tin mặc định: mọi test AI nghiệp vụ có nhãn "GIẢ ĐỊNH", người duyệt, không tính vào độ bao phủ; AI không được là oracle duy nhất (2/4 Fail do đoán sai mã). Ví dụ AI bịa/đoán sai: mã danh mục `IT`, `HN` (mã thật `IT_SOFTWARE`, `HA_NOI`), ngày hạn cứng. (UUID mẫu cho id chỉ là vấn đề khi thiếu chuỗi; hệ thống nối id bằng Test Data Chaining.)

**5. Kết quả AI có lặp lại được không?**
- Phần tất định: có (cùng spec, cùng test gốc → cùng kết quả). Phần AI: chưa chạy lặp, chưa ghi nhiệt độ/seed → cần E1 (chạy lặp 3 lần, ghi phiên bản model và prompt).

## C. Chất lượng và bằng chứng

**6. Hệ thống bắt được bao nhiêu lỗi so với baseline?**
- Hiện CHƯA có con số hợp lệ về tỉ lệ bắt lỗi cài sẵn. 708 test backend chứng minh chất lượng mã, không chứng minh khả năng bắt lỗi. Kế hoạch chi tiết: roadmap 17o (API demo gieo 20–30 mutant, so checklist-only / hybrid / AI-only; metric: tỉ lệ phát hiện theo loại lỗi, Fail giả trên bản chuẩn, token, thời gian, chi phí/lỗi).
- Có bằng chứng thực địa (không phải đo hệ thống): chạy 36 test sinh ra trên API tuyển dụng → 24 Pass / 12 Fail; **8 Fail là lỗi thật** của API đích (trả 401 thay vì 400 cho body sai kiểu/enum sai dù token hợp lệ, đã xác minh bằng curl) và hệ thống gắn nhãn `API_WRONG_STATUS` thay vì che bằng "lỗi token"; 2 Fail là chuỗi rỗng bị API từ chối (lệch spec); 2 Fail là hạn chế của bộ bù (đổi 1 field, không hiểu luật chéo field).

**7. Tỉ lệ Fail giả (false failure) là bao nhiêu?**
- Trên bản API thật (chưa có bản chuẩn đã biết là đúng): trước khi cô lập tài nguyên cha 15/36 Pass, sau đó 24/36 Pass; 9 Fail trước đó là lỗi do CÁCH CHẠY của hệ thống (409 do trạng thái cộng dồn) chứ không phải lỗi API, đã loại bằng cách mỗi test con dùng tài nguyên cha mới. Một nhận định cũ cũng bị đính chính (`weight=100` tưởng "API chặt hơn spec" thực ra do tổng trọng số cộng dồn). Fail giả đúng nghĩa (Fail trên bản chuẩn của API) chỉ đo được ở 17o. Số cũ trên 2 endpoint: hệ thống 2/23 và 2/19 (lệch spec thật, `TEST_DATA_SUSPECT`); AI sinh checklist: 13/25 và 20/34 là 404 do thiếu chuỗi id.

**8. Hệ thống có dùng được cho API khác không?**
- Đã chạy trên 4 API khác miền (tuyển dụng 110 endpoint, thư viện giả lập, shop Spring, Swagger 2.0 XML). Mỗi API chỉ 1–2 endpoint phân tích kỹ; đã sửa 7+ lỗi tổng quát hoá (enum MySQL, Swagger 2.0, basePath, form/XML, Basic/header/query auth...). Chưa thử GraphQL/gRPC.

**9. Spec sai thì sao?**
- Checklist phụ thuộc spec: spec sai thì checklist sai. Hệ thống phát hiện *lệch spec–API* ở mức nhất định (ví dụ `minLength: 0` nhưng API từ chối chuỗi rỗng → gắn `TEST_DATA_SUSPECT`), không tự sửa spec.

## D. Kỹ thuật và giới hạn

**10. Thiếu gì so với công cụ học thuật?**
- Chuỗi stateful kiểu RESTler (suy luận phụ thuộc từ response): chỉ có Test Data Chaining (POST tạo → `{id}`) tự nối cho test gốc + cô lập tài nguyên cha cho từng test con + dọn dữ liệu tuỳ chọn. Chưa có fuzzing quy mô lớn, shrinking, retry flaky tự động, body lồng nhau sâu, upload file, đăng nhập tự lấy token, luật chéo field khi bù test.

**11. "Human review" có đo không?**
- Chưa đo thời gian duyệt/tỉ lệ chấp nhận (E2).

**12. Chi phí AI thế nào? Có kiểm soát không?**
- Quota token theo user/ngày (mặc định 2.000.000, `AI_DAILY_TOKEN_LIMIT`) + trần riêng cho mỗi lần sinh (mặc định 120.000, chọn 30k–500k trong hộp thoại, kiểm trước mỗi lần gọi AI) + dừng sớm khi lỗi AI không sửa được (ví dụ 409 lặp lại tiết kiệm ~50% token), lịch sử token theo lần sinh. Không đặt giới hạn thời gian cứng cho cả job (người dùng xem từng bước và huỷ được). AI nghiệp vụ ≈ 7–17k token/endpoint nên chỉ dùng chọn lọc.

**13. Bảo mật của chính công cụ?**
- Token đích mã hoá AES-256, không log; che giá trị nhạy cảm trong response lưu lại; token CI chỉ lưu SHA-256. Còn thiếu: chặn SSRF khi import URL (P0-2 roadmap).

**14. Vì sao bỏ chức năng AI sinh toàn bộ test case?**
- Đo thật (17h): AI sinh checklist chậm (97–121 giây), tốn 20–39 nghìn token/endpoint, nhiều Fail giả do thiếu id/ngày/mã danh mục, và phần checklist EP/BVA hệ thống làm nhanh hơn, rẻ hơn, ít nhiễu hơn. Nay AI chỉ làm phần cần ngữ nghĩa (1 test gốc hợp lệ + nghiệp vụ tuỳ chọn) và luôn có kiểm chứng bằng chạy thật.

**15. Test Fail thì lỗi ở API hay ở công cụ?**
- Hệ thống tự phân loại nguyên nhân kèm bằng chứng: môi trường, token, dữ liệu test nghi sai, kỳ vọng lệch mã, xung đột trạng thái (409), sai phân quyền, mass-assignment, và `API_WRONG_STATUS` (lỗi thật của API). Không sửa kỳ vọng cho khớp hành vi sai để test Pass. Ví dụ đã làm: 9 lỗi 409 thuộc lỗi cách chạy của công cụ → đã sửa bằng cô lập tài nguyên; 8 lỗi 401-thay-400 là lỗi API → giữ nguyên Fail và báo.

**16. Chạy test có làm bẩn dữ liệu của hệ thống đích không?**
- Có, test gốc và test bù chạy GHI THẬT (có hộp xác nhận, nên dùng môi trường test). Mỗi test con tạo thêm 1 bản ghi cha; tuỳ chọn "Dọn dữ liệu đã tạo" xoá đúng các bản ghi do lần chạy tạo (đo: +36 bản ghi nếu không dọn, +0 nếu dọn). Chưa có chặn production/allowlist host.

## E. Câu hỏi thật của giảng viên (dán vào đây)

<!-- Dán nguyên văn từng câu, rồi bổ sung câu trả lời + bằng chứng bên dưới -->

