# Kịch bản Test toàn bộ dự án — AI API Testing Assistant

> Mục đích: bộ kịch bản test tay (manual/API-level) phủ hết các module trong `docs/ROADMAP.md`, dùng để tự kiểm tra trước khi bảo vệ đồ án hoặc sau mỗi lần sửa code lớn. Đã được chạy 1 lần thật (2026-10-15) qua Docker — kết quả + bug phát hiện ghi ở mục "Kết quả lần chạy thật gần nhất" cuối file.
>
> **Nguyên tắc khi chạy:** KHÔNG dùng cách "AI sinh test gốc" trừ khi thật sự cần verify (tốn token thật) — ưu tiên dùng lại dữ liệu có sẵn (`freshtest@aiapi.local`/`freshtest123`). Mọi bước có gọi AI đều đánh dấu rõ **[TỐN TOKEN AI THẬT]**.

## 0. Chuẩn bị môi trường

- [ ] `docker compose build backend frontend && docker compose up -d` (gốc repo) — 3 container `Up`, `mysql` `(healthy)`.
- [ ] (Nếu cần test execution thật) khởi động `A:/shop-api-target`: `cd A:/shop-api-target && docker compose up -d` — expose port `8081`.
- [ ] `curl http://localhost:3000` → chứa `<title>AI API Testing Assistant</title>`.
- [ ] `curl http://localhost:8080/api/v1/auth/login` (POST rỗng) → không phải lỗi 5xx kết nối (xác nhận backend đã lên).
- [ ] Backend: `cd backend && ./mvnw test` — toàn bộ xanh (tham chiếu: 212/212 tại thời điểm viết file này).
- [ ] Frontend: `cd frontend && npx tsc -b --force && npm run build` — sạch.

## 1. Setup nền tảng / 2. Quản lý Project

- [ ] `POST /api/v1/auth/register` tài khoản mới tạm (email lạ) → `201` + JWT. **Xoá/không dùng tiếp tài khoản này sau test** (không đụng `freshtest@aiapi.local`).
- [ ] `POST /api/v1/auth/login` đúng mật khẩu → `200` + token; sai mật khẩu → `401`.
- [ ] `GET /api/v1/auth/me` kèm token → đúng email/role.
- [ ] `POST /api/v1/projects` tạo project mới (tên, targetBaseUrl) → `201`. `GET /api/v1/projects` thấy project vừa tạo. `PUT /api/v1/projects/{id}` sửa tên → `200`. `DELETE /api/v1/projects/{id}` → project biến mất khỏi list.
- [ ] `PUT /api/v1/projects/{id}/target-auth` set `BEARER_TOKEN` + giá trị bất kỳ → `200`, GET lại project **KHÔNG** trả giá trị token thô (đã mã hoá, không lộ qua response).
- [ ] Gọi API project của **user khác** bằng token của mình (`GET /api/v1/projects/{id-cua-user-khac}`) → `403`/`404` ownership, không rò dữ liệu.

## 3. Import & Parse OpenAPI

- [ ] `POST /api/v1/projects/{id}/endpoints/import` với file OpenAPI JSON hợp lệ → `201`, số endpoint tạo ra khớp số operation trong spec.
- [ ] Import file JSON **không hợp lệ** (text thường) → lỗi rõ ràng (không 500 trần), xem log backend không có stack trace lộ ra response.
- [ ] Import lại CÙNG 1 spec (re-import) → kiểm tra hành vi (tạo trùng hay update) đúng như thiết kế, không tăng gấp đôi endpoint.
- [ ] `GET /api/v1/projects/{id}/endpoints` → `requiresAuth` đúng với endpoint có/không có `security` trong spec (Module 15c phụ thuộc field này).

## 4. Sinh test case (job nền) — **[cách "AI sinh test gốc" TỐN TOKEN AI THẬT - cân nhắc kỹ trước khi chạy]**

> Cập nhật 2026-10-20: đường "AI sinh toàn bộ" (`POST .../generate-tests`, nút "Sinh Test Case" cũ) ĐÃ BỎ. Dùng nút "Sinh test case" (job nền `POST /api/v1/projects/{id}/generation-jobs`). Nguyên tắc: dùng project/endpoint TẠM, bật "Chạy thử" hoặc cách "chỉ bổ sung 0 token" khi không cần AI; môi trường đích phải là môi trường TEST (test gốc và test bù chạy GHI THẬT).

- [ ] Tick 1 endpoint → "Sinh test case" → hộp thoại có: nhóm test (Positive khoá + bật; Negative, Boundary bật; Security, Nghiệp vụ tắt), cách thực hiện (2 lựa chọn), số lần AI sửa 0–5, trần token, chạy thử, ô xác nhận GHI THẬT khi là POST/PUT/PATCH/DELETE (nút Bắt đầu bị khoá cho tới khi tick).
- [ ] **Chạy thử (dry-run)** → job SUCCEEDED/NEEDS_HUMAN với các bước SKIPPED, 0 token, không có test mới nào được tạo.
- [ ] **[TỐN TOKEN]** Cách "AI sinh test gốc" trên 1 endpoint chưa có test: khung giám sát hiện các bước PRECHECK → AI_BASELINE → CHAIN → RUN → LOCK → FILL; test gốc Pass và bị khoá; token hiển thị khớp lịch sử; test hệ thống bù có nhãn "Hệ thống bổ sung", test gốc nhãn "Test gốc".
- [ ] Cách "chỉ bổ sung 0 token" trên endpoint đã có test gốc Pass → 0 token, có bước PRECHECK + FILL.
- [ ] Thu nhỏ khung giám sát, chuyển sang trang khác, mở lại: vẫn theo dõi được; tải lại trang: job vẫn hiện (id lưu localStorage).
- [ ] Bấm Huỷ → hộp xác nhận → "Đang huỷ" rồi "Đã huỷ"; các bước đã làm được giữ; không còn biểu tượng xoay.
- [ ] Tick nhiều endpoint → "Sinh test case (N)": POST chạy trước, DELETE sau cùng; project đã có job đang chạy → `409`.
- [ ] Vượt quota AI/ngày (set override thấp qua Admin) → job báo lỗi quota, **không tốn request AI nào**.
- [ ] Sinh lại cho endpoint đã có test: test do người dùng tự thêm và test đã khoá được giữ nguyên; test gốc đã Pass không gọi lại AI.
- [ ] Tắt Security trong hộp thoại → Coverage của endpoint ghi `security: false` và không bù test Security/phân quyền.
- [ ] **Cô lập trạng thái:** chạy các test con (test bù) của endpoint có `{id}` cha → mỗi test dùng 1 bản ghi cha mới (số bản ghi trên API đích tăng theo số test con); tick "Dọn dữ liệu đã tạo" → số bản ghi trên API đích về như trước lần chạy.
- [ ] **Phân loại Fail:** test body sai kiểu mà API đích trả 401 dù token hợp lệ → nhãn "API trả sai mã lỗi (401 thay vì 400)" (vẫn Fail, không bị loại khỏi Bug Report); 409 lặp lại → nhãn xung đột trạng thái, nằm trong nhóm "cần xem xét" của kết luận.
- [ ] (Cũ, còn dùng được cho dữ liệu đã có) mỗi case có `rationale` không rỗng (Module 15a), `reliabilityStatus` là `RELIABLE` hoặc `NEEDS_REVIEW` (Module 15b); case `NEEDS_REVIEW` sau ít giây có `reliabilityAiConfirmed` (Tier 2, trừ khi hết quota).


## 5. Review Test Case

- [ ] `PUT .../test-cases/{id}` sửa `expectedStatus`/`requestBody` tay → lưu đúng.
- [ ] `PUT .../test-cases/{id}/lock` toggle khoá/mở khoá → phản ánh đúng khi sinh lại ở mục 4.
- [ ] `DELETE .../test-cases/{id}` → biến mất khỏi list, không ảnh hưởng case khác.
- [ ] Tạo tay 1 test case `source=MANUAL` (qua `POST .../test-cases`) → `reliabilityStatus` phải là `null` (Tier 1 không áp dụng cho case tự tạo).

## 6. Thực thi Test

- [ ] Cần `shop-api-target` đang chạy VÀ project có `targetBaseUrl` đúng với MÔI TRƯỜNG đang chạy backend:
  - Backend chạy **Docker** → `targetBaseUrl` phải là `http://host.docker.internal:8081` (KHÔNG phải `http://localhost:8081` — xem bug đã phát hiện ở cuối file).
  - Backend chạy **local** (`mvnw spring-boot:run`) → `http://localhost:8081` đúng.
- [ ] `POST /api/v1/projects/{id}/executions` với 1-2 `testCaseIds` → `201` `PENDING`, sau vài giây `GET /api/v1/projects/{id}/executions/{executionId}` → `COMPLETED`, mỗi result có `responseStatus` thật (không phải giả lập) và `status` (`PASSED`/`FAILED`/`ERROR`) đúng theo `responseStatus` so với `expectedStatus`.
- [ ] Tắt `shop-api-target` rồi chạy lại execution → `status="ERROR"`, `errorMessage` chứa "Connection refused" hoặc tương đương — xác nhận lỗi kết nối được bọc gọn gàng, không làm sập backend/throw 500 ra ngoài.
- [ ] Chạy đồng thời nhiều test case cùng request (`testCaseIds` nhiều phần tử) → tất cả trả kết quả, không bị timeout/deadlock (log `spring.mvc.async.request-timeout=180000`).

## 7. Test Data Chaining

- [ ] Tạo dependency giữa 2 test case (vd case B dùng `id` sinh ra từ case A) qua `GET .../test-cases/{id}/dependency-suggestions` rồi gắn dependency.
- [ ] Chạy case B mà CHƯA chạy case A trước → case A tự động được gộp vào execution (`autoIncludedTestCaseIds` trong response không rỗng).
- [ ] Xoá/sinh lại nhóm case đang có dependency trỏ tới → bị chặn xoá đúng (`ensureNoDependents`) nếu còn case khác phụ thuộc, có lỗi rõ ràng không phải 500.

## 8. Lịch sử & Dashboard

- [ ] `GET /api/v1/dashboard/summary` (chú ý: **không** scope theo project, là route riêng `/api/v1/dashboard/summary`, không phải `/projects/{id}/dashboard`) → số liệu khớp dữ liệu thật (`totalProjects`, `totalTestCases`, `overallPassRate`...).
- [ ] `GET /api/v1/dashboard/ai-usage` → token AI dùng hôm nay, đúng theo giới hạn override (nếu có) hoặc mặc định hệ thống.
- [ ] `GET /api/v1/projects/{id}/history?page=0&size=10` → đúng timeline theo từng endpoint (sự kiện GENERATION/EXECUTION), lọc theo `endpointId` nếu truyền.

## 9. Nâng cao Test Case AI sinh (Security/Assertion/Test Data) — **[có bước tốn token AI thật]**

- [ ] "Sinh test case" với Security bật (hoặc "Bổ sung" ở Coverage) → case nhóm `SECURITY` có `authOverride` (`NONE`/`INVALID`) khác `DEFAULT`, `expectedStatus>=400`.
- [ ] Case Security dù dữ liệu "sai định dạng" (payload injection) → `reliabilityStatus` LUÔN `RELIABLE` (Module 15f/15g loại trừ Security khỏi Tier 1) — kiểm bằng cách xem `reliabilityNotes` rỗng dù payload rõ ràng không hợp lệ.
- [ ] Tự tay thêm assertion (Module 9b - cách DUY NHẤT, AI không còn tự sinh assertion): mở form sửa 1 test case kỳ vọng 2xx → thêm `jsonPath`/`operator`/`expectedValue` → lưu → chạy execution rồi xem `assertionResults` khớp đúng `jsonPath`/`operator`; đổi `expectedValue` sai cố ý → status tổng `FAILED` dù status code khớp.
- [ ] Trang project KHÔNG còn ô "+ Assertion" cạnh nút "Sinh test case"; trang Lịch sử (của project + Lịch sử tổng) vẫn mở bình thường với sự kiện "Sinh test case" cũ tạo trước Module 9b (snapshot cũ còn key `assertions`).

## 10. Bug Report

- [ ] Có execution với ≥1 result `FAILED` → `POST` sinh bug report cho result đó → bug report xuất hiện ở `GET /api/v1/projects/{id}/bug-reports`, đúng `priority`/`component` suy từ dữ liệu.
- [ ] Bấm sinh bug 2 LẦN cho CÙNG 1 result → không tạo trùng bug report (idempotent), hoặc có cơ chế chặn rõ ràng.
- [ ] Xuất Excel (toàn bộ + theo lựa chọn) → file tải về đúng tên, mở được, dữ liệu khớp UI.
- [ ] Đóng 1 bug → `openCount`/`closedCount` trong `summary` cập nhật đúng ngay (không cần F5 nếu test qua UI).

## 11. Trang Admin

- [ ] Login bằng `admintest@aiapi.local` → role `ADMIN`.
- [ ] `GET /api/v1/admin/users` bằng token **USER thường** → `403` (đã xác nhận thật ở lần test 2026-10-15).
- [ ] `PUT /api/v1/admin/users/{id}/status` khoá tài khoản user khác → user đó login thất bại ngay (401/403 tuỳ thiết kế) dù mật khẩu đúng.
- [ ] `PUT /api/v1/admin/users/{id}/ai-quota` set override thấp (vd 100) → user đó bấm "Sinh test case" (cách có AI) bị chặn sớm đúng theo override, không theo mặc định hệ thống.
- [ ] `GET /api/v1/admin/audit-log` → có đúng dòng `USER_LOCKED`/`AI_QUOTA_CHANGED` vừa tạo ở 2 bước trên, kèm `adminEmail` đúng.
- [ ] Admin xem `TestCaseDetailDialog` của user khác (qua `AdminProjectDataPage` hoặc tương đương) → **KHÔNG** thấy nút "Đối chiếu độ tin cậy sâu hơn bằng AI" (Tier 3) — nút này chỉ hiện khi có prop `projectId` ở luồng chính chủ.

## 12. Đổi mật khẩu & Quên mật khẩu

- [ ] `POST /api/v1/auth/change-password` đúng mật khẩu cũ → đổi thành công, login lại bằng mật khẩu mới OK.
- [ ] Tài khoản Google-only (`passwordSet=false`) đổi mật khẩu KHÔNG cần `currentPassword` (theo đúng thiết kế Module 13).
- [ ] `POST /api/v1/auth/forgot-password` → OTP gửi qua email thật/MailDev (không log OTP ra console dưới bất kỳ hình thức nào — kiểm bằng `docker compose logs backend | grep -i otp`).
- [ ] Nhập sai OTP 5 lần → lần thứ 6 bị chặn dù OTP đúng (giới hạn thử sai) — **đây từng là bug bảo mật thật đã fix (Module 12), nên test lại định kỳ để chống regression**.

## 13. Đăng nhập/Đăng ký với Google

- [ ] Đăng nhập Google lần đầu bằng ID token hợp lệ → tạo `User` mới (`authProvider=GOOGLE`, `passwordSet=false`), không yêu cầu mật khẩu.
- [ ] Đăng nhập Google lần 2 cùng tài khoản → KHÔNG tạo user trùng, match theo `googleId`.
- [ ] Email Google trùng với tài khoản LOCAL đã có từ trước → tự liên kết đúng field, không tạo 2 user riêng cho cùng email.
- [ ] Token Google giả/hết hạn → bị từ chối, không đụng gì tới DB (không tạo user rác).

## 14. Hoàn thiện & Demo / Docker / CI

- [ ] `docker compose build && docker compose up -d` từ repo sạch (không cache) → cả 3 container lên đúng, không lỗi migration DB (`ddl-auto=update` tạo đủ bảng).
- [ ] CI (`.github/workflows/ci.yml`) chạy khi push/PR → job `backend` (MySQL service + `mvn test`) và `frontend` (`npm ci` + build) đều xanh. **Nếu đây là lần đầu bật CI, theo dõi sát lần chạy đầu trên GitHub Actions** vì chưa từng chạy thật trên remote.
- [ ] `VITE_API_BASE_URL` build-time đúng (bake vào bundle lúc `docker build`, không đổi được bằng env runtime) — xác nhận bằng cách đổi `.env` rồi chỉ `docker compose up -d` (không `build` lại) → KHÔNG thấy thay đổi (đúng hành vi, không phải bug).

## 15. Explainable & Reliable AI Testing

- [ ] `GET /api/v1/projects/{id}/coverage` → mỗi `EndpointCoverageResponse` có `schemaReadable=true` cho endpoint spec hợp lệ. Thử 1 endpoint có `Endpoint.schema` bị sửa tay thành JSON hỏng trực tiếp trong DB (chỉ trong môi trường test, không đụng dữ liệu thật) → `schemaReadable=false`, UI hiện banner đỏ cảnh báo.
- [ ] Dimension `BOUNDARY` của 1 field có `minimum`/`maximum` khai báo trong `requestBody`: test case giá trị GIỮA khoảng → `MISSING` kèm detail "chưa case nào chạm giá trị biên"; test case giá trị ĐÚNG min hoặc VƯỢT max → `COVERED`, `coveringTestCases` chứa đúng id/tên case đó.
- [ ] Ràng buộc `minimum`/`maximum` khai báo ở QUERY/PATH param (không phải `requestBody`): case test biên → dimension `BOUNDARY` `COVERED` - cả khi giá trị nằm ở `pathParamFallbacks` (sửa ở 15g) LẪN khi ghi thẳng trong `resolvedPath`, vd `?quantity=0` (sửa ở 15h). Case biên query/path đúng ý đồ (kỳ vọng 400, giá trị vượt biên ghi trong URL) → Tier 1 `RELIABLE`, không còn bị gắn "Cần xem lại" oan.
- [ ] Case `expectedStatus>=400` không thuộc Security, dữ liệu KHÔNG vi phạm ràng buộc nào → `reliabilityStatus=NEEDS_REVIEW` (Module 15f/15g). Case tương tự nhưng `source=SECURITY` → luôn `RELIABLE`.
- [ ] `POST .../test-cases/{id}/confidence-check` **[TỐN TOKEN AI THẬT]** trên 1 case đã có sẵn → `agreementLevel` trả về, KHÔNG ghi đè `reliabilityStatus` đã có, KHÔNG tạo dòng `TestGenerationEvent` mới (`GET history` trước/sau phải giống nhau về số sự kiện).
- [ ] **15h - checklist EP/BVA [TỐN TOKEN AI THẬT, 1 lần gọi]:** chọn 1 endpoint có body JSON nhiều ràng buộc (vd `POST /api/products`), tick Positive + Negative + Boundary (KHÔNG tick Security), (đường AI sinh toàn bộ đã bỏ; mục này chỉ còn áp dụng cho dữ liệu sinh trước 2026-10-20 hoặc báo cáo checklist ở Coverage) → báo cáo "Đã sinh N test case: AI sinh X (đạt Y/Z mục checklist được giao...), hệ thống bổ sung M mục"; Z = 1 + số field required + 2 × số ràng buộc min/max/minLength/maxLength + số field enum (đếm tay từ spec phải khớp); bấm "Xem chi tiết checklist" thấy đủ từng mục kèm trạng thái.
- [ ] 15h - trang Test Case: mỗi case nhóm Basic vừa sinh có đúng 1 nhãn "AI · theo checklist" / "AI đề xuất thêm" / "Hệ thống bổ sung"; mở "Xem chi tiết" 1 case "Hệ thống bổ sung" → requestBody chỉ khác case hợp lệ đúng 1 chỗ, mục "Vì sao hệ thống tạo case này" có lý do viết từ spec. Case Security/tự thêm/case cũ trước 15h KHÔNG có nhãn này.
- [ ] 15h - khoá 1 case đạt mục checklist (vd case biên trên maximum) → sinh lại → báo cáo ghi "1 mục đã có sẵn từ case khoá/tự thêm", không sinh trùng case cho mục đó.
- [ ] 15h - chỉ tick Negative → mọi case lưu đều là case thiếu field/sai dữ liệu, KHÔNG lưu case Positive hợp lệ (case gốc AI sinh chỉ dùng làm nền bù).
- [ ] 15h - trang Lịch sử: sự kiện "Sinh test case" vừa tạo hiện đúng N case (gồm cả case hệ thống bổ sung).
- [ ] `TestCaseGenerationServiceTest`/`EndpointCoverageServiceTest`/`TestCaseReliabilityValidatorTest`/`TestCaseReliabilityDetectionEvaluationTest`/`TestCaseChecklistPlannerTest`/`TestCaseParamValuesTest` (`./mvnw test`) luôn xanh — bộ test Precision/Recall Tier 1 tự chạy lại mỗi lần, báo động sớm nếu rule tất định bị sửa hỏng.

---

## 16. Luật nghiệp vụ, độ bao phủ theo endpoint, probe tất định (17q, thêm 2026-10-26)

*Cần: hệ thống đang chạy, `mutant-api` cổng 8083 với `MUTANT_CONTROL=true`, project dựng bằng `python scripts/seed-demo-rules.py` (0 token). Bước **[TỐN TOKEN AI THẬT]** chỉ khi chạy `--with-ai` hoặc hộp thoại "Sinh test case" có tick "+ AI bổ sung checklist nghiệp vụ" (khoảng 14.000 token làn tài liệu; làn "AI gợi ý thêm" ước tính thêm 10.000-15.000, CHƯA đo).*

**Test tự động (backend, không cần môi trường):** `cd backend && ./mvnw test` → 943 test pass, 0 fail (gồm 6 test phân quyền tích hợp thật cần MySQL đang chạy: `RuleAndDocumentOwnershipTest`).

**Tài liệu và trích luật**
- [ ] Tải tệp `.pdf` → bị từ chối "Chỉ nhận tệp .md hoặc .txt"; tải tệp không phải UTF-8 → `DOCUMENT_ENCODING_INVALID`; tệp > 200 KB → `DOCUMENT_TOO_LARGE`; tải tệp thứ 31 → `DOCUMENT_LIMIT_REACHED` (giới hạn 30 tài liệu mỗi project).
- [ ] Tải tệp có dòng `api_key = ...` → cảnh báo dòng số mấy, KHÔNG hiện giá trị; kiểm prompt gửi AI không chứa giá trị (che trước khi gửi).
- [ ] Tải lại tệp trùng tên → bản cũ bị thay, luật từ bản cũ chuyển "Lỗi thời".
- [ ] "Chạy thử (0 token)" → khung giám sát hiện số lô, ước tính token; sổ token không đổi.
- [ ] **[TỐN TOKEN AI THẬT]** Hộp thoại "Sinh test case" → tick "+ AI bổ sung checklist nghiệp vụ" (có tài liệu, "AI gợi ý thêm" tắt) với ngân sách 20.000 → job xong, luật ở "Chờ duyệt", mỗi luật có đoạn trích; "Xem trong tài liệu" tô sáng đúng đoạn.
- [ ] Ngân sách quá nhỏ (5.000) → dừng ngay, lý do "chạm ngân sách", không gọi AI.
- [ ] Hai job cùng project → job thứ hai bị từ chối (409); bấm "Sinh test từ luật" khi có job đang chạy → 409.

**Duyệt luật**
- [ ] Luật GIẢ ĐỊNH (không trích dẫn): nút Duyệt mở hộp thoại bắt chọn mã lỗi 400-499; API duyệt không kèm mã → 409.
- [ ] "Duyệt N luật có trích dẫn": mặc định không tick sẵn; luật GIẢ ĐỊNH và luật mâu thuẫn spec không nằm trong danh sách.
- [ ] Sửa một luật có trích dẫn → trích dẫn bị bỏ, nguồn thành "Bạn nhập", không còn trong danh sách duyệt hàng loạt.
- [ ] Công tắc "Đo thời gian duyệt" mặc định TẮT; bật thì hiện dòng thông báo công khai; duyệt/loại/sửa → banner tăng số quyết định; xuất JSON không có id luật hay nội dung.

**Test từ luật và bắt lỗi**
- [ ] Luật đã duyệt → "Sinh test từ N luật" → test có nhãn nguồn nghiệp vụ; chạy trên API chuẩn: tất cả Pass.
- [ ] Bật `M23` (hoặc `M24`, `M25`) bằng `/__mutants` rồi chạy lại → Fail nhãn "API nhận dữ liệu vi phạm luật nghiệp vụ", độ tin CHẮC; `POST /__reset` để tắt.
- [ ] Sinh lại test của luật đã có bug report → test có bug report được giữ lại, không bị xoá.

**Probe tất định và quy ước API**
- [ ] Hộp thoại "Sinh test case": nhóm "OWASP probe (0 token)" mặc định tắt; bật → có thêm test "JSON cú pháp hỏng" (kỳ vọng 400), "Content-Type sai" (415), "phương thức TRACE" (405). Chạy trên API chuẩn: Pass; request TRACE thật sự được gửi (không phải DELETE).
- [ ] Hộp thoại Xác thực → "Mã API trả khi thiếu hoặc sai token" = 403 → test Security "thiếu token" trên API trả 403 chuyển Pass; test khác (vd sai mật khẩu đăng nhập) không bị nới.
- [ ] Import spec có `security` toàn cục và `/auth/login` không khai `security: []` → endpoint đăng nhập được coi là công khai (có ca "sai mật khẩu", không có ca "thiếu token").

**Đo đạc (chạy lại số liệu báo cáo, 0 token trừ khi ghi khác)**
- [ ] `python scripts/eval-mutants.py --config A --runs 2 --include-heldout` → khớp số đã đo (phát hiện 21/42 dev, 3/15 held-out, Fail giả 1,5%; gán đúng 6/57).
- [ ] `cd mutant-shop && python oracle.py` → 15/15; `python scripts/eval-shop-mutants.py --runs 2 --unauthenticated-status 403` → khớp số đã đo (gán đúng 1/15, phát hiện 73%).

## 17. Luồng "test gốc Pass → AI lập checklist → duyệt → sinh test" theo từng endpoint (thêm 2026-10-26, G3.6-G3.12)

*Cần: hệ thống đang chạy; API đích có thật. Mục **[TỐN TOKEN AI THẬT]** chỉ khi bỏ tick "Chạy thử". Tài khoản trên API đích phải đúng vai trò và đủ điều kiện nghiệp vụ (vd HR đã có công ty) nếu không test gốc sẽ không Pass.*

**Hộp thoại và trang chính của lần sinh test**
- [ ] Hộp thoại "Sinh test case" (1 endpoint): có "+ AI bổ sung checklist nghiệp vụ"; KHÔNG có ô "ngân sách checklist" riêng (chỉ "Trần token AI của lần chạy"); KHÔNG có "Chọn tài liệu riêng cho từng endpoint" (chỉ hiện khi chọn từ 2 endpoint).
- [ ] Chọn nhiều endpoint → "Chọn tài liệu riêng cho từng endpoint" → bỏ 1 endpoint khỏi 1 tài liệu → dòng "Áp dụng cho x/N endpoint"; endpoint không có tài liệu → khung vàng cảnh báo; ước tính token đổi theo phân công.
- [ ] Bấm "Bắt đầu sinh" → tự mở `/projects/:id/jobs/:jobId` (KHÔNG còn khung job nhỏ nổi ở góc): thanh 4 bước, thẻ "Việc bạn cần làm tiếp", tiến trình chi tiết.
- [ ] Chạy thử → thẻ "Đây là lần chạy thử", không tạo checklist, sổ token không đổi.
- [ ] **[TỐN TOKEN AI THẬT]** Endpoint chưa có test gốc → AI sinh, chạy thật, Pass, khoá; sau đó AI lập checklist; checklist CHỈ lập cho endpoint đã có test gốc Pass (endpoint chưa có → bước giám sát nói rõ).
- [ ] Nếu AI không đề xuất mục nào: khung "AI lập checklist nghiệp vụ" nêu lý do (trùng spec, câu hỏi...), không báo lỗi.

**Màn duyệt (chip "Checklist" trên dòng endpoint; KHÔNG có tab Checklist chung)**
- [ ] Mở `/projects/:id/rules` không kèm endpoint → hướng dẫn "checklist thuộc từng endpoint", link về danh sách.
- [ ] Hiện cả "Checklist của hệ thống" (tick từng mục, mục chưa sinh được gom 1 dòng lý do) và checklist AI; nút cuối "Sinh test từ checklist đã duyệt (x hệ thống + y AI)".
- [ ] Mục GIẢ ĐỊNH có chip "AI gợi ý 400": "Xác nhận 400" duyệt ngay, "Đổi mã…" mở hộp thoại điền sẵn; mã gợi ý KHÔNG có hiệu lực khi chưa xác nhận.
- [ ] "Duyệt hàng loạt": mục có trích dẫn không tick sẵn; mục GIẢ ĐỊNH chỉ duyệt khi tick và có mã 4xx riêng; mã sai → bị bỏ qua kèm lý do. "Hoàn tác" (10 giây) đưa về "Chờ duyệt"; đã sinh test rồi thì hoàn tác bị từ chối.
- [ ] Bấm sinh test → tự chuyển sang tab Test case (lọc theo endpoint) với thẻ "Bước 4 hoàn tất: đã sinh N test" và nút "Chạy N test vừa sinh" (có tuỳ chọn dọn dữ liệu).
- [ ] Câu hỏi "AI cần bạn làm rõ": nhập câu trả lời → "Gửi N câu trả lời cho AI" → job mới chỉ lập lại checklist (không sinh lại test gốc, không đổi phạm vi test của endpoint), mục mới có nhãn "Từ chỉ dẫn".
- [ ] Thẻ mẫu khi thêm/sửa mục: ví dụ ghép từ field của endpoint đang chọn; mẫu không có field phù hợp bị mờ kèm lý do.

**Tài liệu**
- [ ] Tab "Tài liệu": danh sách, đọc nội dung, "Tài liệu này viết cho:" chọn endpoint; bảng "đã gửi cho AI" theo tài liệu/theo endpoint; chip "Đã dùng cho N endpoint".
- [ ] Tài liệu khai báo viết cho endpoint X: sinh test cho X → tick sẵn; sinh cho endpoint khác → bỏ tick; tài liệu chung vẫn tick.
- [ ] Gán quá 8 tài liệu cho 1 endpoint trong 1 lần sinh → bị từ chối.

**Tham số đường dẫn `{id}`**
- [ ] Endpoint có `{id}`, chưa có dữ liệu → bước DISCOVER_ID thử GET danh sách (0 token); danh sách rỗng → bước CREATE_RESOURCE cho AI sinh + chạy thật POST tạo tài nguyên (chỉ khi đã xác nhận ghi thật và body JSON; tối đa 2 cấp); cả hai thất bại → NEEDS_HUMAN kèm danh sách đã thử.
- [ ] POST tạo tài nguyên là tải tệp (multipart) → không thử tạo, nói rõ lý do.
- [ ] API trả 404 `*_NOT_FOUND` ở endpoint không có tham số (vd HR chưa có công ty) → dừng ngay "thiếu dữ liệu tiên quyết", KHÔNG để AI sửa tốn token.
- [ ] Test POST nháp chưa bao giờ Pass KHÔNG được dùng làm nguồn nối chuỗi; thông báo "test nguồn chưa Pass" nêu đúng nguyên nhân.
- [ ] Test gốc mới được khoá → test gốc nháp cũ của endpoint bị dọn (trừ test đang là nguồn nối chuỗi).
- [ ] Tab Test case: test là nguồn của test khác có chip "Được N test dùng"; bấm → danh sách test phụ thuộc, lấy gì từ đâu.

**Khác**
- [ ] Trang Project: ô tìm kiếm + phân trang 12 project/trang khi có hơn 12 project.

## Kết quả lần chạy thật gần nhất (2026-10-15, qua Docker + shop-api-target thật)

**Đã verify OK:** login/register, project CRUD + ownership check, coverage API (schemaReadable/provenance/boundary thật hoạt động đúng trên dữ liệu thật), dashboard summary, admin users/audit-log + role enforcement (403 đúng cho non-admin), history feed, bug reports list, execution thật end-to-end khi `targetBaseUrl` đúng môi trường, backend 212/212 unit test, frontend build/tsc sạch.

**2 lỗi/hạn chế THẬT mới phát hiện — ĐÃ FIX ngay trong lần test này (2026-10-15):**

1. **(Vận hành, ảnh hưởng cao) ĐÃ FIX.** Project "Shop API Target - Fresh E2E" cấu hình `targetBaseUrl=http://localhost:8081` (tạo từ trước khi Dockerize) chạy execution qua backend Docker → LUÔN lỗi `"Connection refused"` vì `localhost` trong container không trỏ ra host. **Fix:** cập nhật trực tiếp `target_base_url` của ĐÚNG project này trong DB thành `http://host.docker.internal:8081` (không có API riêng để sửa field này sau khi tạo project — chỉ set được lúc import spec; đây là dữ liệu test của chính nhóm, không phải dữ liệu user khác). **Verify lại:** chạy lại ĐÚNG execution vừa lỗi trước đó → không còn "Connection refused", nhận được response thật từ target (`responseStatus=403`). **Lưu ý còn lại:** mọi project KHÁC tạo trước Module 14 vẫn có thể gặp lỗi này nếu còn trỏ `localhost` — cần rà soát thêm nếu có.
2. **(Code) ĐÃ FIX.** `EndpointCoverageService` trích `boundaryRules` từ CẢ path/query/header param VÀ `requestBody` nhưng khi đối chiếu với test case thật chỉ đọc `requestBody` — field có ràng buộc biên khai ở query/path param thì dimension `BOUNDARY` không bao giờ `COVERED` được. **Fix:** thêm `Location` (BODY/PATH_OR_QUERY/HEADER) vào `BoundaryRule`, đối chiếu đúng nguồn dữ liệu test case theo location (`requestBody`/`pathParamFallbacks`/`requestHeaders`). Test mới `getEndpointCoverage_boundaryFieldOnQueryParam_checksPathParamFallbacksNotBody` xác nhận field biên khai ở query param giờ COVERED đúng khi test case có giá trị tại biên trong `pathParamFallbacks`. 213/213 test pass.

**Giới hạn phương pháp của lần test này:** chưa cài Playwright trong môi trường thực thi lệnh này (cần tải Chromium) nên CHƯA verify được qua browser thật (console error, hiển thị badge/tooltip, luồng click) — chỉ test ở tầng API (curl) + DB trực tiếp + unit test. Nên bổ sung 1 lượt Playwright trước khi bảo vệ.
