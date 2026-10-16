# Kịch bản Test toàn bộ dự án — AI API Testing Assistant

> Mục đích: bộ kịch bản test tay (manual/API-level) phủ hết các module trong `docs/ROADMAP.md`, dùng để tự kiểm tra trước khi bảo vệ đồ án hoặc sau mỗi lần sửa code lớn. Đã được chạy 1 lần thật (2026-10-15) qua Docker — kết quả + bug phát hiện ghi ở mục "Kết quả lần chạy thật gần nhất" cuối file.
>
> **Nguyên tắc khi chạy:** KHÔNG dùng AI sinh test case mới trừ khi thật sự cần verify shape response (tốn token thật) — ưu tiên dùng lại dữ liệu có sẵn (`freshtest@aiapi.local`/`freshtest123`). Mọi bước có gọi AI đều đánh dấu rõ **[TỐN TOKEN AI THẬT]**.

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

## 4. AI sinh Test Case — **[TỐN TOKEN AI THẬT - cân nhắc kỹ trước khi chạy]**

> Theo nguyên tắc đã chốt trong dự án: KHÔNG sinh test case AI mới trừ khi cần verify cụ thể. Nếu chạy, dùng project/endpoint TẠM, xoá ngay sau khi xong.

- [ ] `POST /api/v1/projects/{id}/endpoints/{endpointId}/generate-tests` với `{"includePositive":true,"includeNegative":true,"includeBoundary":true,"includeSecurity":false}` → `201`, mỗi case có `rationale` không rỗng (Module 15a), `reliabilityStatus` là `RELIABLE` hoặc `NEEDS_REVIEW` (Module 15b).
- [ ] Case `NEEDS_REVIEW` (nếu có) → sau ít giây, `GET` lại test case thấy `reliabilityAiConfirmed` không còn `null` (Tier 2 đã chạy, Module 15d) — **trừ khi quota đã hết**.
- [ ] Sinh lại (`generate-tests`) lần 2 cho CÙNG endpoint, nhóm case cũ (không `locked`) bị xoá và thay bằng case mới — case đã khoá (`locked=true`) vẫn còn.
- [ ] Vượt quota AI/ngày (`ai.quota.daily-token-limit`, set override thấp qua Admin để test nhanh) → gọi `generate-tests` trả `429 AI_QUOTA_EXCEEDED`, **không tốn request AI nào** (xem log backend không có lệnh gọi `chatClient` nào phát sinh).

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

- [ ] `generate-tests` với `includeSecurity:true` **[TỐN TOKEN]** → case nhóm `SECURITY` có `authOverride` (`NONE`/`INVALID`) khác `DEFAULT`, `expectedStatus>=400`.
- [ ] Case Security dù dữ liệu "sai định dạng" (payload injection) → `reliabilityStatus` LUÔN `RELIABLE` (Module 15f/15g loại trừ Security khỏi Tier 1) — kiểm bằng cách xem `reliabilityNotes` rỗng dù payload rõ ràng không hợp lệ.
- [ ] Tự tay thêm assertion (Module 9b - cách DUY NHẤT, AI không còn tự sinh assertion): mở form sửa 1 test case kỳ vọng 2xx → thêm `jsonPath`/`operator`/`expectedValue` → lưu → chạy execution rồi xem `assertionResults` khớp đúng `jsonPath`/`operator`; đổi `expectedValue` sai cố ý → status tổng `FAILED` dù status code khớp.
- [ ] Trang project KHÔNG còn ô "+ Assertion" cạnh nút "Sinh Test Case"; trang Lịch sử (của project + Lịch sử tổng) vẫn mở bình thường với sự kiện "Sinh test case" cũ tạo trước Module 9b (snapshot cũ còn key `assertions`).

## 10. Bug Report

- [ ] Có execution với ≥1 result `FAILED` → `POST` sinh bug report cho result đó → bug report xuất hiện ở `GET /api/v1/projects/{id}/bug-reports`, đúng `priority`/`component` suy từ dữ liệu.
- [ ] Bấm sinh bug 2 LẦN cho CÙNG 1 result → không tạo trùng bug report (idempotent), hoặc có cơ chế chặn rõ ràng.
- [ ] Xuất Excel (toàn bộ + theo lựa chọn) → file tải về đúng tên, mở được, dữ liệu khớp UI.
- [ ] Đóng 1 bug → `openCount`/`closedCount` trong `summary` cập nhật đúng ngay (không cần F5 nếu test qua UI).

## 11. Trang Admin

- [ ] Login bằng `admintest@aiapi.local` → role `ADMIN`.
- [ ] `GET /api/v1/admin/users` bằng token **USER thường** → `403` (đã xác nhận thật ở lần test 2026-10-15).
- [ ] `PUT /api/v1/admin/users/{id}/status` khoá tài khoản user khác → user đó login thất bại ngay (401/403 tuỳ thiết kế) dù mật khẩu đúng.
- [ ] `PUT /api/v1/admin/users/{id}/ai-quota` set override thấp (vd 100) → user đó `generate-tests` bị chặn sớm đúng theo override, không theo mặc định hệ thống.
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
- [ ] **15h - checklist EP/BVA [TỐN TOKEN AI THẬT, 1 lần gọi]:** chọn 1 endpoint có body JSON nhiều ràng buộc (vd `POST /api/products`), tick Positive + Negative + Boundary (KHÔNG tick Security), bấm "Sinh Test Case" → dưới dòng endpoint hiện báo cáo "Đã sinh N test case: AI sinh X (đạt Y/Z mục checklist được giao...), hệ thống bổ sung M mục"; Z = 1 + số field required + 2 × số ràng buộc min/max/minLength/maxLength + số field enum (đếm tay từ spec phải khớp); bấm "Xem chi tiết checklist" thấy đủ từng mục kèm trạng thái.
- [ ] 15h - trang Test Case: mỗi case nhóm Basic vừa sinh có đúng 1 nhãn "AI · theo checklist" / "AI đề xuất thêm" / "Hệ thống bổ sung"; mở "Xem chi tiết" 1 case "Hệ thống bổ sung" → requestBody chỉ khác case hợp lệ đúng 1 chỗ, mục "Vì sao hệ thống tạo case này" có lý do viết từ spec. Case Security/tự thêm/case cũ trước 15h KHÔNG có nhãn này.
- [ ] 15h - khoá 1 case đạt mục checklist (vd case biên trên maximum) → sinh lại → báo cáo ghi "1 mục đã có sẵn từ case khoá/tự thêm", không sinh trùng case cho mục đó.
- [ ] 15h - chỉ tick Negative → mọi case lưu đều là case thiếu field/sai dữ liệu, KHÔNG lưu case Positive hợp lệ (case gốc AI sinh chỉ dùng làm nền bù).
- [ ] 15h - trang Lịch sử: sự kiện "Sinh test case" vừa tạo hiện đúng N case (gồm cả case hệ thống bổ sung).
- [ ] `TestCaseGenerationServiceTest`/`EndpointCoverageServiceTest`/`TestCaseReliabilityValidatorTest`/`TestCaseReliabilityDetectionEvaluationTest`/`TestCaseChecklistPlannerTest`/`TestCaseParamValuesTest` (`./mvnw test`) luôn xanh — bộ test Precision/Recall Tier 1 tự chạy lại mỗi lần, báo động sớm nếu rule tất định bị sửa hỏng.

---

## Kết quả lần chạy thật gần nhất (2026-10-15, qua Docker + shop-api-target thật)

**Đã verify OK:** login/register, project CRUD + ownership check, coverage API (schemaReadable/provenance/boundary thật hoạt động đúng trên dữ liệu thật), dashboard summary, admin users/audit-log + role enforcement (403 đúng cho non-admin), history feed, bug reports list, execution thật end-to-end khi `targetBaseUrl` đúng môi trường, backend 212/212 unit test, frontend build/tsc sạch.

**2 lỗi/hạn chế THẬT mới phát hiện — ĐÃ FIX ngay trong lần test này (2026-10-15):**

1. **(Vận hành, ảnh hưởng cao) ĐÃ FIX.** Project "Shop API Target - Fresh E2E" cấu hình `targetBaseUrl=http://localhost:8081` (tạo từ trước khi Dockerize) chạy execution qua backend Docker → LUÔN lỗi `"Connection refused"` vì `localhost` trong container không trỏ ra host. **Fix:** cập nhật trực tiếp `target_base_url` của ĐÚNG project này trong DB thành `http://host.docker.internal:8081` (không có API riêng để sửa field này sau khi tạo project — chỉ set được lúc import spec; đây là dữ liệu test của chính nhóm, không phải dữ liệu user khác). **Verify lại:** chạy lại ĐÚNG execution vừa lỗi trước đó → không còn "Connection refused", nhận được response thật từ target (`responseStatus=403`). **Lưu ý còn lại:** mọi project KHÁC tạo trước Module 14 vẫn có thể gặp lỗi này nếu còn trỏ `localhost` — cần rà soát thêm nếu có.
2. **(Code) ĐÃ FIX.** `EndpointCoverageService` trích `boundaryRules` từ CẢ path/query/header param VÀ `requestBody` nhưng khi đối chiếu với test case thật chỉ đọc `requestBody` — field có ràng buộc biên khai ở query/path param thì dimension `BOUNDARY` không bao giờ `COVERED` được. **Fix:** thêm `Location` (BODY/PATH_OR_QUERY/HEADER) vào `BoundaryRule`, đối chiếu đúng nguồn dữ liệu test case theo location (`requestBody`/`pathParamFallbacks`/`requestHeaders`). Test mới `getEndpointCoverage_boundaryFieldOnQueryParam_checksPathParamFallbacksNotBody` xác nhận field biên khai ở query param giờ COVERED đúng khi test case có giá trị tại biên trong `pathParamFallbacks`. 213/213 test pass.

**Giới hạn phương pháp của lần test này:** chưa cài Playwright trong môi trường thực thi lệnh này (cần tải Chromium) nên CHƯA verify được qua browser thật (console error, hiển thị badge/tooltip, luồng click) — chỉ test ở tầng API (curl) + DB trực tiếp + unit test. Nên bổ sung 1 lượt Playwright trước khi bảo vệ.
