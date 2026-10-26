# Câu hỏi dự kiến khi bảo vệ và câu trả lời dựa trên bằng chứng

> Đây là các câu hỏi **dự đoán**, KHÔNG phải câu hỏi thật của giảng viên. Câu hỏi thật hãy dán vào mục E để cập nhật câu trả lời.
> **Cập nhật 2026-10-26** theo số liệu hiện hành: `docs/DANH_GIA.md` mục 0 (bộ số chuẩn), 7A (hệ thống thứ hai), 7B (G5: trích luật, cấu hình C, held-out). Các câu cũ đã viết lại; số liệu 27 lỗi ở mục 1-6 của `DANH_GIA.md` chỉ còn là lịch sử.
> Quy tắc trả lời: chỉ nêu điều có số liệu; điều chưa đo thì nói rõ là chưa đo. Hai thuật ngữ không được lẫn: **"phát hiện"** (có test Pass→Fail, kể cả nhãn chưa chắc) và **"đúng nhãn"** (Fail được gán đúng là lỗi của API).

## A. Định vị và khác biệt

**1. Hệ thống này khác gì Schemathesis / EvoMaster / RESTler / Postman / Keploy / Testsigma? Vì sao không dùng luôn?**
- Các công cụ fuzz/search-based mạnh ở *tìm lỗi crash (5xx) và chuỗi trạng thái*; đồ án mạnh ở *giải thích độ bao phủ so với spec theo từng endpoint, tách nguyên nhân Fail (dữ liệu test / token / kỳ vọng lệch / lỗi API), độ tin của kết luận và quy trình người duyệt luật nghiệp vụ có nguồn*.
- **Đã so bằng số với Schemathesis (cấu hình mặc định) trên 27 lỗi đầu:** 4/27 phát hiện so với 14/27 của checklist tất định (đo gốc 2026-10-21, `DANH_GIA.md` mục 3). **Chưa so bằng số** với EvoMaster/RESTler/Keploy/Testsigma/Postman (chưa dùng thử; chỉ khảo sát tài liệu công khai).
- Công trình gần nhất tìm thấy: RESTifAI (ICSE 2026 demo, arXiv:2512.08706); chưa đọc toàn văn nên không nêu khác biệt chính xác.

**2. Điểm mới thật sự là gì?**
- Cách ghép có đo đạc: checklist tất định (0 token) + test sinh từ **luật nghiệp vụ do AI trích có trích dẫn nguyên văn và người duyệt** + phân loại nguyên nhân Fail kèm độ tin. Từng thành phần không mới; cách ghép, nguyên tắc "AI chỉ đề xuất, hệ thống kiểm chứng tất định, người quyết định" và dữ liệu đo (kể cả hạn chế) là đóng góp.

## B. Vai trò của AI

**3. AI đóng góp gì thật sự? Có cần AI không?**
- AI làm **2 việc có kiểm chứng**: (1) sinh 1 test gốc hợp lệ cho endpoint, hệ thống chạy thật để xác nhận (nếu 4xx thì gửi lỗi thật cho AI sửa, không sửa được thì dừng và hỏi người dùng); (2) **lập checklist nghiệp vụ** (tick "AI bổ sung checklist nghiệp vụ"): từ tài liệu/chỉ dẫn của người dùng, mỗi mục kèm đoạn trích nguyên văn, chỗ tài liệu thiếu AI hỏi lại; tuỳ chọn "AI gợi ý thêm" cho điều tài liệu không nói (nhãn GIẢ ĐỊNH, kèm mã lỗi gợi ý). Sau khi người dùng duyệt, test được sinh **tất định, 0 token**.
- Checklist bắt buộc/null/biên/enum/bảo mật/probe OWASP **không cần AI** (0 token).
- Chi phí đo thật (G5, API tuyển dụng 10 endpoint): trích luật ≈ **14.188 token/lượt**; cách cũ "AI đoán test nghiệp vụ" ≈ **98.856 token/lượt** (khoảng 7 lần đắt hơn).

**4. Tại sao tin kết quả AI? AI có bịa không?**
- Không tin mặc định, có 4 lớp chặn: (1) đoạn trích phải có **nguyên văn** trong tài liệu (sau chuẩn hoá khoảng trắng/nháy), không thì luật bị hạ thành GIẢ ĐỊNH; (2) mọi hằng số trong luật phải xuất hiện trong đoạn trích; (3) endpoint, mẫu luật và field phải có thật; mã lỗi chỉ giữ khi đoạn trích nêu đúng mã đó; (4) luật chỉ sinh test sau khi **người dùng duyệt**, và luật GIẢ ĐỊNH buộc người duyệt tự chọn mã lỗi.
- Các lớp này có tác dụng thật: AI đề xuất 8, 8 và 11 luật ở 3 lượt, chỉ **15/27 = 56%** qua kiểm chứng tất định. Chưa biết lý do cụ thể từng luật bị loại (hệ thống chỉ ghi số lượng).
- Tài liệu có chuỗi giống mật khẩu/khoá được **che trước khi gửi AI**; nội dung tài liệu được bọc trong dấu phân cách ngẫu nhiên và ghi rõ là DỮ LIỆU để giảm prompt injection; lớp chặn chính là kiểm chứng tất định đầu ra (AI không tự viết được request), còn tỉ lệ chống injection **chưa đo bằng thực nghiệm**.

**5. Kết quả AI có lặp lại được không?**
- Phần tất định: có (cùng spec, cùng test gốc cho cùng kết quả).
- Phần AI: đã chạy lặp 3 lượt. Trích luật: **cùng 5 luật ở cả 3 lượt** (token 14.066 / 13.876 / 14.622). Cấu hình B: kết quả phát hiện **giống hệt** 3 lượt, token 98.593 / 98.850 / 99.125. Đây là 3 mẫu trên một API, chưa ghi nhiệt độ/seed, nên không suy rộng ra độ ổn định nói chung.

## C. Chất lượng và bằng chứng

**6. Hệ thống bắt được bao nhiêu lỗi?** (bộ chuẩn: `DANH_GIA.md` mục 7B; API tuyển dụng `mutant-api`, 10 endpoint; lỗi cài sẵn, mỗi lỗi bật riêng)

| | A: checklist (0 token) | B: A + AI đoán nghiệp vụ (+296.568 token / 3 lượt) | C: A + luật AI trích, người duyệt (+42.564 token / 3 lượt) |
|---|---|---|---|
| 42 lỗi dev, đúng nhãn / phát hiện | 5 / 21 | 5 / 25 | 3 / 3 |
| 10 lỗi held-out mới | 0 / 2 | 0 / 4 | 1 / 2 |
| Fail giả trên bản chuẩn | 1,5% | 19,8% | 0% |

- Đọc thẳng: **checklist A không bắt lỗi nghiệp vụ** (0/12); AI đoán nghiệp vụ (B) **không thêm lỗi nào được gán đúng nhãn** so với A; luật có người duyệt (C) thêm **đúng nhãn** 4 lỗi nghiệp vụ (M23, M24, M25 trong dev; H08 trong held-out) với 9-10 test và 0 Fail giả. A, B, C bổ sung cho nhau chứ không thay nhau.
- Hạn chế nói thẳng: mutant do nhóm tự gieo trên API nhỏ do nhóm viết; held-out do AI cùng họ viết; cỡ mẫu nhỏ; số liệu A tất định nên độ lệch chuẩn 0 không phải bằng chứng thống kê. Không suy rộng ra "bắt X% lỗi của mọi API".

**7. Tỉ lệ Fail giả là bao nhiêu?**
- Trên `mutant-api`: A **1,5%**; B 19,8% (test AI đoán, nhiều phụ thuộc lẫn nhau); C **0%**.
- Trên API cửa hàng (hệ thống thứ hai, quy ước khác): A lúc đầu **24,1%**, sau khi khai quy ước "thiếu token trả 403" và sửa nhận diện endpoint đăng nhập còn **11,4%** (`DANH_GIA.md` mục 7A). Trong phần còn lại, 8/114 là bất đồng thật giữa spec và API (spec không khai ràng buộc, API từ chối giá trị âm/rỗng), 5 do quy ước "403 cho mọi lỗi framework" chưa có cấu hình.
- Các sai sót của chính phép đo đã được phát hiện và sửa (mục 6 `DANH_GIA.md`).

**8. Sinh bao nhiêu test là đủ, và dựa vào đâu để nói là đủ?**
Không có một con số "đủ"; trả lời bằng 3 trục độc lập, không gộp:
- **(a) Thoả tiêu chí:** % mục checklist của từng endpoint đã có test (trang Độ bao phủ, theo từng endpoint) và % luật đã duyệt có test.
- **(b) Chất lượng kết luận:** mỗi Fail có độ tin CHẮC / NGHI NGỜ; Pass trên endpoint có lệch spec được cảnh báo; "đủ" chỉ có nghĩa khi Fail giả thấp (mục 7).
- **(c) Khả năng bắt lỗi:** đo bằng lỗi cài sẵn (mục 6). Đây là con số duy nhất cho biết "đủ để bắt lỗi", và mới đo đầy đủ trên một hệ thống (thêm hệ thống thứ hai 15 lỗi ở cấu hình A: phát hiện 11/15).

**9. AI nghiệp vụ dựa vào đâu để sinh test, còn "đoán" không?**
- Cách cũ (AI đoán test nghiệp vụ từ spec): có đo, ≈ 99 nghìn token/lượt, 19,8% Fail giả; vẫn có trong hệ thống nhưng gắn nhãn **GIẢ ĐỊNH**, không tính vào độ bao phủ.
- Cách mới: AI **không viết test**, chỉ đề xuất mục checklist (luật) theo 9 mẫu, phải kèm đoạn trích nguyên văn; không trích dẫn được thì thành GIẢ ĐỊNH. Làn "AI gợi ý thêm" (không tài liệu, chỉ từ spec): đo 3 lượt sau khi sửa prompt (3.800-4.050 token/lượt), cả 3 lượt có đủ 5 mục đúng với mã gợi ý 400, mục sai 0-2 mỗi lượt (`docs/eval/suggest-2026-10-26.md`); lần đo đầu gợi ý 422 trong khi API trả 400, nên mã gợi ý chỉ điền sẵn và người duyệt phải xác nhận. Đo: **5 luật khác nhau, cùng 5 luật ở 3 lượt, cả 5 đúng so với đáp án (5/5)**, 0 luật GIẢ ĐỊNH; tìm được **5/7** luật mà 9 mẫu biểu diễn được (thiếu 2 luật về số phần tử của mảng).
- **Giới hạn:** một tài liệu do AI viết (có thể dễ trích hơn tài liệu thật), người chấm là người phát triển hệ thống, luật ngoài 9 mẫu (máy trạng thái, không trùng lặp) không trích được.

**10. Hệ thống có chuẩn trên các hệ thống khác nhau không?**
- Phương pháp không gắn với hệ thống nào và đã chạy trên 2 API đo được; **nhưng không chuẩn tuyệt đối**: Fail giả phụ thuộc quy ước của API (24,1% → 11,4% sau khi khai quy ước, mục 7A). Hai lỗi tổng quát hoá thật đã tìm ra khi đo hệ thống thứ hai và đã sửa: quy ước 401/403, và endpoint đăng nhập chỉ kế thừa security toàn cục bị coi là cần token.
- **Chưa có API của bên thứ ba** (cả hai API đo đều Spring Boot do nhóm viết hoặc chọn); các API đã thử trước đó (thư viện sách Basic Auth/form, Swagger 2.0 XML) chỉ có kết quả "import được, chạy được", không có số bắt lỗi.

**11. H10 được sửa sau khi thấy kết quả held-out, vậy held-out còn ý nghĩa gì?**
- Số đo held-out ở bảng mục 6 là số **trước khi sửa**, giữ nguyên. Lỗi H10 (tổng trọng số dùng `> 100` thay vì `!= 100`) lọt vì bộ sinh test chỉ thử một phía vi phạm; đã tái hiện trực tiếp trên API và sửa (thử cả hai phía của giới hạn). Sau sửa C bắt thêm H10 nhưng **H10 không còn là mẫu độc lập** và không đưa vào số "held-out mới". Bộ dev không có lỗi nào mới được bắt (không hồi quy, M25 chặt hơn). Phần còn lại (14/15 lỗi) vẫn là phép đo chưa bị chỉnh theo.

**12. Số liệu này tin được đến mức nào, ai gán nhãn?**
- Tin ở mức "bằng chứng gợi ý trên một hệ thống nhỏ", không phải thống kê. Điểm yếu: mutant và API do nhóm viết; held-out và tài liệu SRS do AI cùng họ viết, rút từ cùng 86 luật chuẩn, 5/15 held-out chồng bộ dev; **người gán nhãn luật là người phát triển hệ thống** và biết đáp án (5 luật đều trùng đáp án nên khó thiên lệch nhưng không loại bỏ được; chưa có người chấm thứ hai); cỡ mẫu 15 lỗi held-out và 5 luật.

**13. AI trích luật chỉ bắt thêm vài lỗi nghiệp vụ, chi phí đó có đáng không? Ngoài 9 mẫu thì sao?**
- Đo: 42.564 token cho 3 lượt trích (≈ 14k/lượt) rồi 0 token cho mọi lần sinh test; bắt đúng nhãn 4 lỗi nghiệp vụ mà A không bắt được và B (≈ 99k token/lượt) cũng không gán đúng. Tính đúng: 42.564 là tổng của 3 lượt trích, mỗi lượt (≈ 14,2k token) đều bắt cùng 4 lỗi, nên ≈ 3,5k token mỗi lỗi mỗi lượt và 0 token cho các lần sinh test sau đó; rẻ hơn cách cũ (≈ 99k token/lượt, không gán đúng nhãn lỗi nào).
- Ngoài 9 mẫu (máy trạng thái, không nộp đơn trùng, luật quyền theo ngữ cảnh) hệ thống **không trích được**; 3/15 lỗi held-out thuộc loại luật đã trích, 2 lỗi (H11, H12) nằm ngoài khả năng.

**14. Người dùng thật duyệt luật mất bao lâu, có duyệt sai không?**
- **Chưa đo (E2).** Đã có công cụ đo trong giao diện (công tắc mặc định tắt, chỉ ghi thời gian có focus, xuất JSON ẩn danh). Phép đo C mô phỏng người duyệt cẩn thận (duyệt đúng các luật đúng); người thật có thể duyệt sai. Cơ chế giảm rủi ro duyệt bừa: luật GIẢ ĐỊNH buộc chọn mã lỗi, duyệt hàng loạt chỉ dành cho luật có trích dẫn và phải tick từng luật.

## D. Kỹ thuật và giới hạn

**15. Thiếu gì so với công cụ học thuật?**
- Có: probe tất định độ bền request (JSON hỏng, sai Content-Type, TRACE), mass assignment, quét rò rỉ (dữ liệu nhạy cảm, stack trace, lỗi SQL), Test Data Chaining cho test gốc.
- Chưa có: suy luận chuỗi stateful kiểu RESTler từ response, fuzzing quy mô lớn, shrinking, retry flaky tự động, body lồng sâu, upload file, tự lấy token đăng nhập, kiểm tra BOLA, chạy trong pipeline CI/CD (hướng phát triển, câu 21).

**16. Chi phí AI thế nào? Có kiểm soát không?**
- Quota token theo user/ngày (mặc định 2.000.000); **trần cứng cho mỗi lần trích luật** do người dùng đặt (mặc định 60.000, khoảng 5.000-1.000.000; kiểm trước mỗi lần gọi, hết thì dừng và giữ phần đã làm); "chạy thử" cho biết số lô và ước tính, 0 token; sổ token cấp project (xoá project hay import lại không đặt lại quota ngày).
- Lưu ý: phần vào của ngân sách chỉ là ước lượng (sai lệch đo được +3% đến +6%); chạy nhiều job song song cùng chủ có thể vượt quota ngày vài chục nghìn token (chưa giữ chỗ trước).
- Đợt đo G5 dùng **339.132 token** (dự đoán 320.106, +5,9%), đối chiếu sổ CSDL trước/sau từng bước.

**17. Bảo mật của chính công cụ?**
- Token đích mã hoá AES-256, không log; che giá trị nhạy cảm trong response và trong tài liệu trước khi gửi AI; phân quyền theo sở hữu project có **test tích hợp thật** (Spring Security + JWT + MySQL) cho API luật, tài liệu và job trích luật: người dùng khác bị 403, id của project khác không dùng được. Phạm vi chặn SSRF khi import URL xem roadmap P0-2.

**18. Vì sao bỏ chức năng AI sinh toàn bộ test case?**
- Đo thật: AI sinh checklist chậm (97-121 giây), tốn 20-39 nghìn token/endpoint, nhiều Fail giả do thiếu id/ngày/mã danh mục; phần EP/BVA hệ thống làm nhanh hơn, rẻ hơn, ít nhiễu hơn. Nay AI chỉ làm phần cần ngữ nghĩa và luôn có kiểm chứng.

**19. Test Fail thì lỗi ở API hay ở công cụ?**
- Hệ thống tự phân loại nguyên nhân kèm bằng chứng: môi trường, token, dữ liệu test nghi sai, kỳ vọng lệch mã, xung đột trạng thái, sai phân quyền, mass assignment, **xử lý sai request lỗi định dạng**, **nhận dữ liệu vi phạm luật đã duyệt**, và `API_WRONG_STATUS` (lỗi thật như 401 thay vì 400). **Không sửa kỳ vọng cho khớp hành vi sai để test Pass.** Với API có quy ước khác (vd 403 khi thiếu token) người dùng khai quy ước ở cấp project; quy ước chỉ áp dụng cho test cố ý bỏ/làm sai token.

**20. Chạy test có làm bẩn dữ liệu của hệ thống đích không?**
- Có: test gốc và test bù chạy GHI THẬT (có hộp xác nhận, nên dùng môi trường test); tuỳ chọn "Dọn dữ liệu đã tạo". Probe phương thức không hỗ trợ cố ý dùng **TRACE** (chỉ phản chiếu request) để API lỗi cũng không xoá/sửa được dữ liệu thật. Chưa có chặn production/allowlist host.

**21. Hệ thống có tích hợp CI/CD không?**
- Đây là **hướng phát triển**. Ý tưởng: pipeline (GitHub Actions, GitLab CI, Jenkins) gọi hệ thống bằng token API gắn với một project để chạy bộ test mỗi lần đẩy code, chờ kết quả, tải báo cáo JUnit và kết thúc xanh/đỏ; chỉ Fail do lỗi thật của API làm đỏ, Fail nghi do dữ liệu test/token/môi trường chỉ cảnh báo; chạy tất định, không tốn token AI. Hệ thống có nguyên mẫu nhưng **chưa nằm trong phạm vi đánh giá hay demo**, nên không khẳng định kết quả nào về phần này.

## E. Câu hỏi thật của giảng viên (dán vào đây)

<!-- Dán nguyên văn từng câu, rồi bổ sung câu trả lời + bằng chứng bên dưới -->

**22. AI sinh test case trong hệ thống đã hoàn thiện chưa? Luồng chạy thế nào?**
- Luồng hiện tại (cho từng endpoint): (1) AI sinh 1 test gốc hợp lệ, hệ thống chạy thật xác nhận Pass; (2) AI lập checklist nghiệp vụ (tài liệu có trích dẫn và/hoặc gợi ý thêm GIẢ ĐỊNH; câu hỏi về chỗ tài liệu thiếu để người dùng trả lời); (3) người dùng xem cả checklist của hệ thống và của AI, duyệt; (4) hệ thống sinh test tất định, 0 token, rồi chạy. AI **không viết test**: chỉ sinh test gốc và đề xuất mục checklist.
- **Chưa phải hoàn thiện cuối cùng.** Đã có: luồng chạy được trọn vẹn trên 2 API, số đo token đối chiếu sổ CSDL, có test tự động (964). Còn thiếu và nên nêu thẳng: chưa đo người duyệt thật (E2); làn gợi ý thêm chỉ 3 lượt trên 1 API; chạy trên API của nhóm khác mới 1 lượt và không có đáp án; bộ kiểm chứng trích dẫn không bắt được đoạn trích đúng chữ nhưng sai nghĩa; chỉ 10 mẫu luật; test gốc cần dữ liệu tiên quyết hoặc tải tệp thì vẫn cần người; chưa có cách đo chất lượng test gốc ngoài việc nó Pass.

**23. Hệ thống xử lý thế nào khi endpoint cần id có thật hoặc dữ liệu tiên quyết?**
- Thứ tự: dùng giá trị người dùng nhập; nối chuỗi với test POST tạo tài nguyên đã Pass; tự GET danh sách lấy id đầu (0 token, chỉ đọc); tự cho AI sinh + chạy thật POST tạo tài nguyên (cần xác nhận ghi thật, body JSON, tối đa 2 cấp); không được thì dừng và nói đã thử gì. API trả 404 mã `*_NOT_FOUND` ở endpoint không có tham số (vd HR chưa tạo công ty) thì dừng ngay, không để AI sửa tốn token (một lần chạy lỗi từng tốn 51.892 token trước khi có quy tắc này). Test nháp chưa bao giờ Pass không được dùng làm nguồn nối chuỗi.
