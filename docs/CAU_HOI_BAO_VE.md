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
- Đã đo (17o, `docs/DANH_GIA.md`): gieo **27 lỗi cài sẵn** vào một API tuyển dụng thu nhỏ (`mutant-api`, 10 endpoint), chạy lại bộ test trên từng lỗi. "Phát hiện" = test Pass trên bản chuẩn rồi Fail khi bật đúng một lỗi, loại Fail do môi trường/nhiễu (có lượt đối chứng không bật lỗi).
- **Checklist tất định (không AI, 0 token): 14/27 (52%)**, kết quả giống hệt giữa 2 lượt. **Hybrid (thêm AI nghiệp vụ): 17/27 (63%)**, 17/17/17 qua 3 lượt, ≈ 93.000 token mỗi lượt. **Chỉ phần AI nghiệp vụ: ≈ 7/27 (27%)**. **Schemathesis (mặc định): 4/27 (15%).**
- Theo loại: bắt hết 5/5 lỗi bỏ validate, 3/3 sai mã trạng thái, 3/5 sai biên, phân quyền theo vai và mass-assignment có bắt (2/5 và 1/2). **AI chỉ bù đúng chỗ checklist không với tới: luật nghiệp vụ (+3/5)**, giá khoảng 31.000 token cho mỗi lỗi bắt thêm.
- **Điểm yếu nói thẳng:** cả hai cùng bỏ lọt 9/27 (quyền sở hữu dữ liệu, sai biên trong body lồng nhau, máy trạng thái đơn, nộp đơn trùng, lộ thông tin trong lỗi 400); và hệ thống thường *phát hiện* nhưng chưa dám *kết luận* lỗi của API (chỉ 3–4 phát hiện có nhãn đủ chắc, còn lại `UNDETERMINED`/`FLAKY`).
- Giới hạn (mục 7 của `DANH_GIA.md`): mutant do nhóm tự gieo trên API nhỏ do nhóm viết; 3 lượt; phụ thuộc phiên bản model/prompt; test gốc và ma trận phân quyền do người viết; cấu hình "chỉ AI" là xấp xỉ; người chạy biết danh sách lỗi (không mù). Không suy rộng ra "bắt được X% lỗi của mọi API".
- Bằng chứng thực địa trước đó (không phải đo hệ thống): 36 test trên API tuyển dụng thật → 24 Pass / 12 Fail, trong đó **8 Fail là lỗi thật** của API đích (401 thay vì 400, đã xác minh bằng curl; nhãn `API_WRONG_STATUS`).

**7. Tỉ lệ Fail giả (false failure) là bao nhiêu?**
- Đo được từ 17o trên **bản chuẩn đã biết là đúng** của `mutant-api`: **checklist tất định 4,3%** (7/163 test, giống hệt giữa các lượt; nguyên nhân: luật chéo field `salaryMin ≤ salaryMax`, đăng nhập với giá trị biên, máy trạng thái đơn); **hybrid 23,2% ± 1,2** (≈ 52/228 test), phần lớn từ test AI nghiệp vụ phụ thuộc lẫn nhau (`BLOCKED`) hoặc dùng id không tồn tại; **chỉ phần AI nghiệp vụ ≈ 49%** (xấp xỉ).
- Hai lần phép đo suýt cho kết quả sai đã được phát hiện và sửa (ghi ở mục 6 của `DANH_GIA.md`): spec của API demo ghi `minLength: 0` trong khi API từ chối chuỗi rỗng (Fail giả giảm 13,3% → 3,5% sau khi sửa spec), và nhiễu làm mọi lỗi cùng ra một con số.
- Một lỗi thật của hệ thống do phép đo phát hiện: test checklist do hệ thống bù không thừa kế `authProfileId` của test gốc nên endpoint cần vai khác ra 403 oan (chưa sửa, đã ghi roadmap).
- Chưa đo Fail giả trên API thật nhiều endpoint ở đợt này; số cũ trên API tuyển dụng (2/23 và 2/19 cho checklist hệ thống; 13/25 và 20/34 là 404 thiếu chuỗi id cho AI sinh toàn bộ, đường này đã bỏ) vẫn là bằng chứng thực địa.

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

## F. Câu trả lời mẫu theo 3 trục (cập nhật 2026-10-26, số liệu G5)

**F1. Sinh bao nhiêu test là đủ, và dựa vào đâu để nói là đủ?**
Không có một con số "đủ", nên trả lời bằng 3 trục độc lập, không gộp:
- **(a) Thoả tiêu chí:** % mục checklist của từng endpoint đã có test (trang Độ bao phủ, tính theo từng endpoint chứ không gộp cả project) và % luật nghiệp vụ đã duyệt có test. "Đủ" ở trục này nghĩa là mọi mục spec đã có test và mọi luật bạn duyệt đã có test.
- **(b) Chất lượng kết luận:** mỗi kết quả Fail có độ tin CHẮC / NGHI NGỜ (nhãn nguyên nhân); Pass trên endpoint có lệch spec được cảnh báo. Trên bản chuẩn, checklist tất định có 1,5% Fail giả trên API tuyển dụng nhưng 11-24% trên API cửa hàng vì khác quy ước (xem DANH_GIA 7A): nên "đủ" chỉ có nghĩa khi Fail giả thấp.
- **(c) Khả năng bắt lỗi:** đo bằng lỗi cài sẵn. Checklist A bắt (phát hiện) 21/42 lỗi dev và 3/15 held-out, không bắt lỗi nghiệp vụ; thêm luật có người duyệt bắt thêm đúng nhãn 4 lỗi nghiệp vụ. Đây là con số duy nhất cho biết "đủ để bắt lỗi" và nó mới đo trên một hệ thống có lỗi cài sẵn (thêm hệ thống thứ hai 15 lỗi, chỉ cấu hình A).

**F2. AI nghiệp vụ dựa vào đâu để sinh test, và còn đoán không?**
- Cách cũ (AI đoán thẳng test nghiệp vụ từ spec): đã đo, 66 test/lượt, ≈ 99 nghìn token/lượt, 19,8% Fail giả, các lỗi phát hiện chỉ ở mức nghi ngờ. **Đó là đoán** và hệ thống gắn nhãn GIẢ ĐỊNH.
- Cách mới (G3): AI **không viết test**, chỉ đề xuất *luật* theo 9 mẫu có sẵn, và mỗi luật phải kèm **đoạn trích nguyên văn** từ tài liệu của người dùng (hệ thống kiểm lại từng chữ, con số và mã lỗi). Không có trích dẫn thì bị hạ thành GIẢ ĐỊNH và người duyệt phải tự chọn mã lỗi. Người dùng duyệt luật, rồi test được sinh **tất định, 0 token**.
- Đo: 3 lượt, 42.564 token tổng, mỗi lượt 5 luật có trích dẫn, 15/15 đúng, recall 5/7, 0 GIẢ ĐỊNH. **Giới hạn nói thẳng:** chỉ trên một tài liệu do AI viết, người chấm là người phát triển, và luật nằm ngoài 9 mẫu thì không trích được.

**F3. Hệ thống có chuẩn trên các hệ thống khác nhau không?**
Phương pháp không gắn với hệ thống nào và đã chạy trên 2 API đo được; nhưng số đo cho thấy **không chuẩn tuyệt đối**: API cửa hàng (quy ước 403) lúc đầu Fail giả 24,1%, sau khi khai quy ước và sửa nhận diện đăng nhập còn 11,4% (DANH_GIA 7A). Khả năng bắt lỗi mới đo trên 1 hệ thống đầy đủ (mutant-api) và 1 hệ thống ở cấu hình A.

## E. Câu hỏi thật của giảng viên (dán vào đây)

<!-- Dán nguyên văn từng câu, rồi bổ sung câu trả lời + bằng chứng bên dưới -->

