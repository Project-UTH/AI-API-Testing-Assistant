---
name: api-contract
description: Hợp đồng format response, error, pagination giữa Spring Boot backend và React frontend của AI API Testing Assistant. Dùng khi tạo/sửa endpoint backend, khi viết code gọi API ở frontend, hoặc khi cần đối chiếu format dữ liệu giữa 2 phía.
---

# API Contract — AI API Testing Assistant

Đây là **hợp đồng bắt buộc** cho toàn bộ API giữa backend và frontend. Bất kỳ thay đổi nào ở đây phải được cả người phụ trách backend và frontend xác nhận, vì đổi 1 phía mà phía kia không biết sẽ gây lỗi runtime.

## 1. Response thành công

```json
{
  "data": { },
  "meta": { }
}
```

- `data`: nội dung chính (object hoặc array)
- `meta`: thông tin phụ trợ (optional) — vd. thời gian xử lý, số lượng test case đã sinh

Ví dụ — tạo project thành công:
```json
{
  "data": {
    "id": "uuid",
    "name": "My Project",
    "createdAt": "2026-08-03T10:00:00Z"
  },
  "meta": null
}
```

## 2. Response lỗi (dùng cho MỌI lỗi, không ngoại lệ)

```json
{
  "code": "PROJECT_NOT_FOUND",
  "message": "Không tìm thấy project với id đã cho",
  "timestamp": "2026-08-03T10:00:00Z"
}
```

- `code`: SCREAMING_SNAKE_CASE, duy nhất theo loại lỗi, frontend dùng để xử lý logic (không parse `message`)
- `message`: mô tả cho người dùng, tiếng Việt, không lộ chi tiết kỹ thuật (không có stack trace, không có tên bảng DB)

### Danh sách mã lỗi chuẩn của dự án (bổ sung khi có lỗi mới)

| Code | HTTP Status | Ý nghĩa |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Input không hợp lệ |
| `UNAUTHORIZED` | 401 | Chưa đăng nhập / token hết hạn |
| `FORBIDDEN` | 403 | Không có quyền truy cập project/resource này |
| `PROJECT_NOT_FOUND` | 404 | Không tìm thấy project |
| `ENDPOINT_NOT_FOUND` | 404 | Không tìm thấy endpoint |
| `TEST_CASE_NOT_FOUND` | 404 | Không tìm thấy test case |
| `TEST_EXECUTION_NOT_FOUND` | 404 | Không tìm thấy lần thực thi test |
| `TEST_CASE_HAS_DEPENDENTS` | 409 | Không xoá/sinh lại test case vì đang có test case khác phụ thuộc (Test Data Chaining) |
| `BUG_REPORT_NOT_FOUND` | 404 | Không tìm thấy bug report |
| `TEST_RESULT_NOT_FOUND` | 404 | Không tìm thấy lần chạy (TestResult) |
| `SWAGGER_PARSE_FAILED` | 422 | Không parse được file/URL OpenAPI |
| `AI_GENERATION_FAILED` | 502 | LLM lỗi hoặc trả về sai định dạng khi sinh test case |
| `AI_QUOTA_EXCEEDED` | 429 | Đã vượt quota token AI/ngày của user (xem `ai.quota.daily-token-limit`) |
| `TEST_EXECUTION_FAILED` | 500 | Lỗi khi thực thi test (không phải lỗi của API được test) |
| `INTERNAL_ERROR` | 500 | Lỗi hệ thống chung, không xác định được nguyên nhân cụ thể |
| `EMAIL_ALREADY_EXISTS` | 409 | Email đã được đăng ký |
| `INVALID_CREDENTIALS` | 401 | Sai email hoặc mật khẩu |
| `INVALID_CURRENT_PASSWORD` | 401 | Sai mật khẩu hiện tại khi đổi mật khẩu (`POST /auth/change-password`) |
| `INVALID_RESET_CODE` | 400 | Mã OTP quên mật khẩu sai/hết hạn/vượt số lần thử (`POST /auth/reset-password`) - CHUNG 1 thông báo cho mọi trường hợp (kể cả email không tồn tại), không phân biệt để tránh lộ email đã đăng ký |
| `GOOGLE_AUTH_FAILED` | 401 | ID token Google sai chữ ký/hết hạn/sai audience/email chưa xác thực (`POST /auth/google`) |
| `ACCOUNT_DISABLED` | 403 | Tài khoản đã bị khoá bởi admin (không cho đăng nhập/gọi API) |
| `USER_NOT_FOUND` | 404 | Không tìm thấy user (trang Admin) |

## 3. Pagination (dùng cho mọi API trả danh sách)

```json
{
  "data": [ ],
  "page": 0,
  "size": 20,
  "totalElements": 100,
  "totalPages": 5
}
```

Query params chuẩn khi gọi: `?page=0&size=20&sort=createdAt,desc`

## 4. Trạng thái bất đồng bộ (thực thi test)

Vì thực thi test không block request, endpoint chạy test trả về ngay:

```json
{
  "data": {
    "id": "uuid",
    "status": "PENDING",
    "startedAt": "2026-08-08T10:00:00Z",
    "finishedAt": null,
    "results": [],
    "autoIncludedTestCaseIds": []
  }
}
```

Frontend poll qua `GET /api/v1/projects/{projectId}/executions/{executionId}` để lấy trạng thái. Có **2 khái niệm trạng thái tách biệt, không được nhầm lẫn**:

- **`ExecutionStatus`** (field `status` ở cấp `TestExecutionResponse`, trạng thái của *cả lần chạy*) — các giá trị hợp lệ: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`. `COMPLETED` nghĩa là đã chạy xong hết danh sách test case, **không có nghĩa "toàn bộ pass"**. `FAILED` chỉ dùng khi lỗi ở tầng orchestration (vd exception ngoài dự kiến khi lập lịch chạy) — không dùng khi chỉ có 1 vài test case bên trong bị fail.
- **`TestResultStatus`** (field `status` ở từng phần tử trong `results[]`, trạng thái của *từng test case* trong lần chạy đó) — các giá trị hợp lệ: `PASSED`, `FAILED`, `ERROR`, `BLOCKED`, `SKIPPED`.
  - `PASSED`/`FAILED`: gọi được target API, so `expectedStatus` với status thật trả về.
  - `ERROR`: lỗi hạ tầng khi gọi target API (network, timeout, không đọc được response) — không phải lỗi của API đang test.
  - `BLOCKED`: (Test Data Chaining) phụ thuộc vào 1 test case khác không `PASSED`, hoặc không trích được giá trị JSONPath từ response nguồn.
  - `SKIPPED`: dự phòng, chưa dùng.

Không được tự thêm giá trị khác cho cả 2 enum trên mà không cập nhật skill này. `autoIncludedTestCaseIds`: danh sách test case được tự động thêm vào lần chạy do là nguồn dữ liệu (Test Data Chaining) cho 1 test case đã chọn — rỗng nếu không liên quan.

## 4b. Cấu hình xác thực Target API độc lập với import

`PUT /api/v1/projects/{projectId}/target-auth` — body `{ "authType": "NONE" | "API_KEY" | "BEARER_TOKEN", "authValue": "..." }`. Dùng khi cần đặt/đổi/xoá auth gọi API thật (Module 6) mà **không** phải qua lại luồng import (vd sau khi import bằng file - file không gọi ra ngoài nên không có auth nào được set). Khác với auth nhập lúc `POST /endpoints/import` (`authType: "NONE"` ở đó nghĩa là "giữ nguyên auth cũ, không đụng vào"), ở endpoint này `authType: "NONE"` là hành động rõ ràng: xoá auth hiện có. `ProjectResponse` trả kèm `targetBaseUrl`/`targetAuthType` (không bao giờ trả `targetAuthValueEncrypted` hay giá trị gốc ra ngoài).

## 4c. Ngoại lệ: tải file nhị phân (export)

`GET /api/v1/projects/{projectId}/bug-reports/{bugReportId}/export` trả thẳng `.xlsx` (`Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, header `Content-Disposition: attachment; filename="..."`) — **không** bọc trong envelope `{data, meta}` ở mục 1, vì đây là nội dung nhị phân không phải JSON. Đây là ngoại lệ hợp lệ DUY NHẤT cho quy tắc envelope, chỉ áp dụng cho endpoint tải file. Lỗi (project/bug report không tồn tại...) vẫn trả theo đúng format lỗi chuẩn ở mục 2 (Spring tự chuyển `ResponseEntity<byte[]>` thành JSON lỗi qua `GlobalExceptionHandler` khi exception được ném trước lúc build response). Frontend gọi bằng fetch thường (không qua `apiFetch`/`apiFetchPaged`), đọc `response.blob()`.

## 4d. Phân quyền Admin (Module 11)

`User` có field `role` (`USER`/`ADMIN`, mặc định `USER`) và `enabled` (khoá/mở tài khoản). **Không có API nào cấp/đổi role** - ADMIN chỉ gán được bằng SQL trực tiếp trên DB (`UPDATE users SET role='ADMIN' WHERE email='...'`), cố ý tránh bề mặt tấn công leo quyền qua HTTP. Toàn bộ `/api/v1/admin/**` yêu cầu `role=ADMIN` (403 `FORBIDDEN` nếu không đủ quyền, do `JwtAccessDeniedHandler` xử lý tự động qua Spring Security `hasRole`, không cần check thủ công trong Controller/Service).

`GET /api/v1/auth/me` trả `{ email, role }` của user hiện tại - đọc **fresh từ DB mỗi lần gọi** (không phải từ claim JWT, JWT chỉ mang `email`), để việc cấp quyền ADMIN qua SQL hoặc khoá tài khoản có hiệu lực ngay từ request tiếp theo mà không cần đăng nhập lại. `AuthResponse` (`POST /auth/login`, `/auth/register`) cũng trả kèm `role` để frontend có ngay không cần gọi `/auth/me` lần đầu.

`GET /api/v1/admin/users` (paged, query thêm `search` tuỳ chọn - tìm theo email, chứa/không phân biệt hoa thường) và `GET /api/v1/admin/users/{id}` (1 user) cùng trả `AdminUserResponse` (email/role/enabled/aiDailyTokenLimitOverride/aiTokensToday/aiCallsToday/tổng project-testcase-bugreport) - `{id}` dùng ở trang chi tiết user (Module 11e) để hiện đúng trạng thái quota khi vừa đổi xong mà không phải quay lại trang danh sách.

Admin xem được dữ liệu Project/Endpoint/TestCase/BugReport của MỌI user (không giới hạn owner) nhưng chỉ ở endpoint riêng dưới `/api/v1/admin/users/{userId}/**` (`GET .../projects`, `GET .../projects/{projectId}`, `GET .../projects/{projectId}/endpoints`, `GET .../projects/{projectId}/test-cases`, `GET .../projects/{projectId}/bug-reports`, `GET .../projects/{projectId}/bug-reports/test-cases/{testCaseId}/run-history` - CHỈ ĐỌC, không có method ghi) - không đổi hành vi ownership check của các endpoint thường (`/api/v1/projects/**` vẫn luôn giới hạn theo owner đang đăng nhập, kể cả khi người gọi là ADMIN). `BugReportService.getBugReportsForProject(Project)`/`getRunHistoryForProject(Project, UUID)` (package-private) tách riêng khỏi bản `UUID projectId` công khai để `AdminUserDataService` tái dùng với `Project` đã resolve theo owner chỉ định - không nhân bản logic tổng hợp Dashboard/lồng 3 tầng.

**Quota AI/ngày (Module 11d):** mỗi user có giới hạn TOKEN/NGÀY (giờ UTC, config `ai.quota.daily-token-limit`, mặc định 100000), có thể ADMIN GHI ĐÈ RIÊNG qua `PUT /api/v1/admin/users/{userId}/ai-quota` (body `{ "dailyTokenLimit": number | null }`, `null` = xoá ghi đè, quay lại dùng mặc định hệ thống). Vượt quota HIỆU LỰC (ghi đè nếu có, không thì mặc định) → `POST .../generate-tests` trả `429 AI_QUOTA_EXCEEDED` - chặn TRƯỚC khi gọi AI (không tốn request AI nào cho lần bị chặn). `AdminUserResponse` trả kèm `aiDailyTokenLimitOverride` (null = đang dùng mặc định) và mức dùng token thật hôm nay (`aiTokensToday`) - **frontend admin phải tự resolve `aiDailyTokenLimitOverride ?? aiDailyTokenLimit-hệ-thống` khi hiện "đã dùng/giới hạn" theo TỪNG user, không được dùng thẳng `aiDailyTokenLimit` hệ thống cho mọi hàng** (bug thật đã gặp ở `AdminUsersPage.tsx` khi mới thêm ghi đè riêng - sửa ở Module 11e đợt 2); `AdminDashboardSummaryResponse` trả `totalAiTokensToday` (toàn hệ thống) + `aiDailyTokenLimit` (mặc định hệ thống, KHÔNG phải hiệu lực riêng cho ai) - tất cả lấy từ `usage` thật của Anthropic (`ChatResponse.getMetadata().getUsage()`), không phải ước lượng. `DashboardSummaryResponse` (`/dashboard/summary`, CHÍNH user đang đăng nhập) trả kèm `aiTokensToday` + `aiDailyTokenLimit` - ở đây `aiDailyTokenLimit` đã là mức HIỆU LỰC resolve sẵn cho đúng user đó (không phải mặc định hệ thống thô như ở bản Admin), vì user không cần biết khái niệm "ghi đè" tồn tại.

**Biểu đồ usage AI theo ngày/tuần/tháng (Module 11e):** 3 endpoint cùng trả `AiUsageResponse { daily: [{date, totalTokens, callCount}, ...] }` - LUÔN đủ 90 ngày liên tục gần nhất (kể cả ngày token=0), gộp thành tuần/tháng là việc của FRONTEND (cộng dồn các điểm ngày liên tiếp), backend chỉ bucket theo ngày:
- `GET /api/v1/dashboard/ai-usage` - CỦA CHÍNH user đang đăng nhập (trang Tổng quan thường)
- `GET /api/v1/admin/users/{userId}/ai-usage` - của 1 user cụ thể (khác `/dashboard/ai-usage` - đây do ADMIN gọi cho user KHÁC)
- `GET /api/v1/admin/dashboard/ai-usage` - TOÀN HỆ THỐNG (mọi user gộp lại)

**Audit log (Module 11d):** `GET /api/v1/admin/audit-log` (paged, mới nhất trước) - ghi lại hành động khoá/mở tài khoản VÀ đổi quota AI riêng của admin (`action`: `USER_LOCKED`/`USER_UNLOCKED`/`AI_QUOTA_CHANGED`, kèm `detail` mô tả thêm cho hành động đổi quota). `AdminAuditEvent` lưu SNAPSHOT email (không FK tới `User`) để không mất dấu vết khi tài khoản liên quan bị xoá sau này.

## 4e. Coverage Matrix + Risk Engine (Module 15c)

`GET /api/v1/projects/{projectId}/coverage` (toàn project) và `GET /api/v1/projects/{projectId}/endpoints/{endpointId}/coverage` (1 endpoint) — **tất định tuyệt đối, KHÔNG gọi AI**, tính từ `Endpoint.schema` (OpenAPI spec đã lưu lúc import) đối chiếu với `TestCase`/`TestCaseDependency`/`TestResult` hiện có. Không bao giờ dùng `TestCase.rationale` (Module 15a, lời AI tự khai) để suy coverage — xem nguyên tắc ở Module 15 trong `docs/ROADMAP.md`.

`EndpointCoverageResponse { endpointId, path, method, testCaseCount, coveragePercent, executedPercent, schemaReadable, riskScore, riskLevel, riskReasons, dimensions }`:
- `dimensions: CoverageDimensionResponse[] { category, label, status, detail, coveringTestCases, checklistType, itemKey, whyNeeded, runStatus, nearMisses }` — **Module 15j (Coverage Gap Analysis):** các dòng `category` ∈ `POSITIVE`/`NEGATIVE`/`BOUNDARY`/`SECURITY` chính là từng mục checklist EP/BVA của `TestCaseChecklistPlanner` (CÙNG checklist dùng khi sinh test case, mục 4g - `checklistType` = tên `ChecklistItemType`), chấm trên TOÀN BỘ test case của endpoint theo nội dung (quy tắc "mỗi case chỉ 1 lỗi"; endpoint yêu cầu xác thực mà case cố ý bỏ/sai token thì case đó chỉ được tính cho mục `SECURITY`). Ngoài ra: `STATUS_CODE` (mã spec khai báo; hỗ trợ dạng `4XX`, `default` → `NOT_APPLICABLE`), `RELATION` (endpoint dùng `{x}` trên path mà project có `POST` tạo đúng resource đó → cần ≥1 case có `TestCaseDependency` lấy dữ liệu từ case của `POST` đó), `AUTH` "403 Forbidden" **LUÔN** `NOT_APPLICABLE` (hệ thống chưa có mô hình vai trò/scope) — frontend không hiển thị như 1 thiếu sót. Các `category` cũ `REQUIRED_FIELD`/`BOUNDARY` (dimension tự định nghĩa của 15c) **không còn** - thay bằng mục checklist chi tiết hơn. `status` ∈ `COVERED`/`MISSING`/`NOT_APPLICABLE`.
  - `coveringTestCases: {id, name, latestResult}[]` — provenance (15f), rỗng khi MISSING/NOT_APPLICABLE; `latestResult` (15j) = `TestResultStatus` lần chạy mới nhất của case, `null` = chưa chạy.
  - `runStatus` (15j, chỉ khi COVERED) ∈ `NOT_RUN` / `NO_VERDICT` (lần mới nhất ERROR/BLOCKED) / `PASSED` / `FAILED` (có case phủ dòng này FAILED - đã kiểm và thấy API lệch, không bị case PASSED khác che).
  - `whyNeeded` (15j) — vì sao cần kiểm, câu mẫu tất định sinh từ spec (KHÔNG phải lời AI tự khai). `nearMisses: {testCaseId, testCaseName, reason}[]` (15j, chỉ khi MISSING, tối đa 3) — case "gần đạt" nhưng không được tính (đúng giá trị/vi phạm cần thử nhưng sai kỳ vọng HOẶC dư 1 lỗi khác), kèm lý do = bằng chứng cho khoảng trống. `itemKey` (15j) — khoá ổn định của mục checklist, `null` với STATUS_CODE/RELATION/AUTH (hệ thống không tự bù được loại này). `fillable` (15j, boolean) — dòng MISSING có `itemKey` mà hệ thống bù được NGAY BÂY GIỜ (tính bằng cách thử bù thật qua `TestCaseChecklistPlanner.attemptFill`, cùng hàm API bù dùng - không đoán); `fillBlockedReason` — khi chưa bù được: vì sao + cần làm gì (`null` nếu `fillable=true` hoặc dòng không có `itemKey`). **Frontend phải làm mờ nút Bổ sung khi `fillable=false` và hiện `fillBlockedReason`** (không để người dùng bấm rồi mới biết thất bại).
- `coveragePercent`: % dòng `COVERED` trên tổng dòng `COVERED`+`MISSING` (loại `NOT_APPLICABLE` khỏi mẫu số) - mẫu số chính là "Y mục cần kiểm". `executedPercent` (15j): % dòng vừa COVERED vừa `runStatus` ∈ `PASSED`/`FAILED`, cùng mẫu số, luôn ≤ `coveragePercent` (khoảng chênh = có test nhưng chưa kiểm thật). `testCaseCount` (15j): tổng test case của endpoint (0 = chưa có test nào). **`schemaReadable` (Module 15f, boolean)** — `false` khi `Endpoint.schema` không đọc được (JSON hỏng) - lúc đó `coveragePercent`/`dimensions` KHÔNG đáng tin (checklist chỉ còn mục Positive, không phải "không có gì để kiểm"). Frontend PHẢI kiểm cờ này trước khi tin số `coveragePercent` — không tự suy "100% = đã bao phủ đủ" khi `schemaReadable=false`. Frontend đếm "X/Y mục" bằng `coverageCounts()` ở `frontend/src/lib/coverage.ts`, không tự đếm riêng.
- **`POST /api/v1/projects/{projectId}/endpoints/{endpointId}/coverage/fill` (Module 15j)** — body `{ "itemKeys": string[] | null }` (null/rỗng = bù TẤT CẢ mục còn thiếu; khoá không khớp mục nào bị bỏ qua), trả `201` + `ApiResponse<CoverageFillResponse { createdCount, created: {id, name, latestResult}[], notFilled: {itemKey, label, reason}[], baselineTestCaseName, fromSpecCount }>`. **Tất định, 0 token, KHÔNG gọi AI**: chấm lại ngay lúc gọi (mục đã có case đạt thì bỏ qua), copy 1 case gốc hợp lệ đang có (ưu tiên case đã chạy PASS thật, rồi case khoá/MANUAL) rồi sửa đúng 1 chỗ bằng `TestCaseChecklistPlanner.fill()`; **không có case gốc thì thử dựng thẳng từ đặc tả** (`fromSpecCount` = số case dựng kiểu này) - CHỈ cho mục kỳ vọng LỖI khi endpoint không còn đầu vào bắt buộc nào khác (case "trống" sửa đúng 1 chỗ vẫn tự chấm đạt đúng 1 lỗi), vd `DELETE /x/{id}` chưa có test nào vẫn bù được các mục biên của `id`; mục kỳ vọng thành công (Positive, biên hợp lệ) và mục cần dữ liệu thật thì không bịa - vào `notFilled`; case mới `generationOrigin=SYSTEM_FILLED`, `source` = `AI_GENERATED` (hoặc `SECURITY` cho mục Security) nên lần "Sinh Test Case" sau sẽ thay thế nếu chưa khoá. Chép theo `TestCaseDependency` của case gốc khi case mới vẫn dùng token `{{x}}` đó. Không có case gốc hợp lệ → mục vào `notFilled` kèm lý do, không tạo case sai âm thầm.
- `riskScore` (0-100) + `riskLevel` (`LOW`/`MEDIUM`/`HIGH`/`CRITICAL`): Risk Engine **heuristic tất định do nhóm tự thiết kế** dựa trên trực giác kỹ thuật (HTTP method + yêu cầu auth + số endpoint khác phụ thuộc dữ liệu (Test Data Chaining) + field nhạy cảm trong schema + tỷ lệ fail lịch sử) — không phải LLM đoán, nhưng cũng KHÔNG phải xác suất đã được calibrate bằng dữ liệu thực nghiệm; trọng số 0-20 điểm mỗi thành phần là lựa chọn thiết kế, chưa qua khảo sát/validate. `riskReasons` là danh sách câu giải thích tương ứng từng thành phần có cộng điểm, để hiển thị trực tiếp (không cần ghép chuỗi ở frontend).

`ProjectCoverageResponse { projectId, overallCoveragePercent, overallExecutedPercent, overallRiskWeightedCoveragePercent, endpoints }` — `overallExecutedPercent` (15j) cùng công thức `executedPercent` trên toàn project. `overallRiskWeightedCoveragePercent` KHÁC `overallCoveragePercent` (phẳng): mỗi endpoint đóng góp theo trọng số `riskScore` của chính nó, nên endpoint rủi ro cao mà thiếu coverage kéo điểm này xuống nhiều hơn endpoint rủi ro thấp. 2 số được CHỦ Ý để khác nhau (giống cách `executedTestCaseCount` khác `passedTestResults`/`totalTestResults` ở Module 11e) — khoảng cách giữa 2 số chính là insight, không phải lỗi hiển thị.

## 4f. Rationale + Reliability (Tier 1/2/3) trên TestCase (Module 15a/15b/15d/15e)

`TestCaseResponse` thêm 5 field so với trước Module 15: `rationale` (String, null cho case MANUAL/dữ liệu cũ — **lời AI TỰ KHAI, không phải evidence đã kiểm chứng độc lập, có thể không khớp lý do thật bên trong model, chỉ phục vụ con người đọc hiểu, KHÔNG dùng để tính Coverage Matrix**), `reliabilityStatus` (`RELIABLE`/`NEEDS_REVIEW`/null - null = không áp dụng, Tier 1 giờ kiểm cả case Negative/Boundary kỳ vọng lỗi trừ nhóm Security - xem Module 15f), `reliabilityNotes` (`string[]`, luôn `[]` khi không có), `reliabilityAiConfirmed` (`Boolean`, null = Tier 2 chưa chạy), `reliabilityAiNote` (String, null khi `reliabilityAiConfirmed` null). **Frontend không tự suy luận cách phối hợp 4 field reliability — dùng thẳng `getReliabilityDisplay(testCase)` ở `frontend/src/lib/testcases.ts`** (gộp sẵn logic Tier 1 + Tier 2 ở 1 chỗ, tránh lặp/sai lệch giữa các trang).

`POST /api/v1/projects/{projectId}/endpoints/{endpointId}/test-cases/{testCaseId}/confidence-check` (Module 15e, Tier 3) — **thủ công, người dùng tự bấm, TUYỆT ĐỐI không gọi tự động ở đâu khác** (tốn thêm token AI thật mỗi lần gọi). Trả `ConfidenceCheckResponse { agreementLevel: HIGH|PARTIAL|LOW, expectedStatusMatches, requestBodyShapeMatches, regeneratedExpectedStatus, regeneratedRequestBody, note }` — đây là **kiểm tra TÍNH NHẤT QUÁN giữa 2 lần AI suy luận độc lập, KHÔNG phải bằng chứng case đó đúng tuyệt đối** (2 lần gọi cùng 1 model có thể cùng mắc đúng 1 lỗi hệ thống/shared bias — đồng thuận cao không loại trừ khả năng này). KHÔNG ghi đè `reliabilityStatus`/`reliabilityAiConfirmed` đã có, chỉ là kết quả đối chiếu riêng hiển thị tạm thời. Dùng chung cơ chế quota (`ensureWithinDailyQuota`) với `generate-tests` - trả `429 AI_QUOTA_EXCEEDED` nếu vượt. **Chỉ hiện nút gọi endpoint này ở luồng CHÍNH CHỦ xem test case của mình** (`TestCaseDetailDialog` chỉ hiện nút khi được truyền prop `projectId`) — **KHÔNG được hiện ở luồng Admin xem dữ liệu user khác** (Module 11, Admin chỉ đọc, không được tốn token của người dùng khác).

## 4g. Sinh test case theo checklist EP/BVA (Module 15h)

`POST /api/v1/projects/{projectId}/endpoints/{endpointId}/generate-tests` — body `{ includeSecurity, includePositive, includeNegative, includeBoundary }` (KHÔNG còn `includeAssertions` từ Module 9b — assertion chỉ thêm tay qua `TestCaseRequest.assertions`). **Breaking change có chủ đích:** response đổi từ `ApiResponse<TestCaseResponse[]>` sang `ApiResponse<GenerateTestCasesResponse>`:

```json
{
  "data": {
    "testCases": [ /* TestCaseResponse[] - gồm cả case Basic lẫn Security vừa sinh */ ],
    "planSummary": {
      "checklistTotal": 7, "alreadyCoveredCount": 0, "aiAskedCount": 7, "aiSatisfiedCount": 5,
      "systemFilledCount": 2, "notFillableCount": 0, "notApplicableCount": 0, "aiExtraCount": 1,
      "checklistCaseCount": 7, "extraCaseNames": ["Negative - age la chu"],
      "items": [{ "type": "NEGATIVE_MISSING_FIELD", "field": "email", "description": "Thiếu field bắt buộc 'email' ...",
                  "status": "SYSTEM_FILLED", "reason": null, "coveredBy": ["Negative - Thiếu field bắt buộc 'email'"] }]
    }
  }
}
```

- **Module 15i:** `planSummary` **luôn có** - mỗi loại test case có checklist chuẩn riêng, kể cả Security; sinh cả nhóm Cơ bản + Security thì báo cáo GỘP chung (items nối nhau, các count cộng dồn). Kiểu frontend vẫn cho `null` để an toàn với dữ liệu cũ.
- `items[].type` - tiền tố tên là loại test case, frontend chia báo cáo theo tiền tố này: `POSITIVE_BASELINE` / `POSITIVE_ENUM_VALUE` (mỗi giá trị enum hợp lệ 1 mục); `NEGATIVE_MISSING_FIELD` (body, hoặc tham số query/header bắt buộc) / `NEGATIVE_NULL_FIELD` / `NEGATIVE_WRONG_TYPE` / `NEGATIVE_INVALID_ENUM` (15h gọi là `BOUNDARY_INVALID_ENUM` - backend vẫn đọc được tên cũ trong báo cáo đã lưu); `BOUNDARY_AT_/BELOW_MINIMUM`, `BOUNDARY_AT_/ABOVE_MAXIMUM`, `BOUNDARY_AT_/BELOW_MIN_LENGTH`, `BOUNDARY_AT_/ABOVE_MAX_LENGTH`, `BOUNDARY_AT_/ABOVE_TYPE_MAXIMUM` + `BOUNDARY_AT_/BELOW_TYPE_MINIMUM` (int32/int64, hai đầu; minimum/maximum `exclusive` được tính đúng), biên ngầm `BOUNDARY_ZERO` / `BOUNDARY_NEGATIVE_NUMBER` / `BOUNDARY_EMPTY_STRING` (spec không khai ràng buộc → kỳ vọng 2xx, UI gắn nhãn "⚠ biên ngầm"; riêng tham số **path** các giá trị 0/-1/lớn nhất int64 kỳ vọng **lỗi 4xx**, description ghi "kỳ vọng lỗi 4xx", bù `expectedStatus=404`); `SECURITY_MISSING_TOKEN` / `SECURITY_INVALID_TOKEN` (chấm theo `authOverride` + 401, `NOT_APPLICABLE` khi endpoint không `requiresAuth`).
- `items[].status`: `ALREADY_COVERED` (case khoá/tự thêm sẵn có đã đạt, không giao AI) / `AI_SATISFIED` / `SYSTEM_FILLED` / `NOT_FILLABLE` (kèm `reason`) / `NOT_APPLICABLE` (kèm `reason`, vd body không phải JSON).
- Mục "đạt" do backend **chấm theo NỘI DUNG case** (quy tắc mỗi case chỉ 1 lỗi), không dựa vào tên hay lời AI tự khai.
- `TestCaseResponse` thêm `generationOrigin` (`AI_CHECKLIST` / `AI_EXTRA` / `SYSTEM_FILLED`, null cho MANUAL/dữ liệu trước 15h; từ 15i case Security cũng có giá trị) và `checklistItem` (mô tả mục checklist case đó đạt, null nếu không). Frontend dùng `GENERATION_ORIGIN_DISPLAY` ở `frontend/src/lib/testcases.ts`, không tự đặt nhãn riêng.

`GET /api/v1/projects/{projectId}/endpoints/{endpointId}/generation-plan` — trả `ApiResponse<GenerationPlanSummary | null>`: báo cáo checklist của lần sinh nhóm Basic gần nhất (lưu ở `TestGenerationEvent.planSummaryJson`), để xem lại sau khi tải lại trang. Đọc dòng `TestGenerationEvent` MỚI NHẤT của endpoint; `data = null` khi chưa sinh lần nào hoặc dòng đó tạo trước khi có tính năng. `checklistCaseCount`/`extraCaseNames` là số liệu chụp lúc sinh — frontend hiển thị từ đây, KHÔNG ghép với bộ test case đang sống (có thể đã bị sửa/xoá sau đó).

## 4h. Nguyên nhân nghi ngờ của kết quả fail (Module 16, giai đoạn 1)

`TestResultResponse` (mục `results[]` của `GET /projects/{id}/executions/{executionId}`) thêm 3 field — chỉ THÊM, không đổi field cũ:
- `durationMs` (number | null) — thời gian gọi target API; `null` với case không gọi target (ERROR cấu hình, BLOCKED) và kết quả trước Module 16.
- `errorKind` (`TIMEOUT`/`CONNECTION`/`DNS`/`OTHER` | null) — loại lỗi hạ tầng, chỉ có với `ERROR` do gọi target thất bại, phân loại theo LOẠI exception (không đọc chuỗi `errorMessage`).
- `failureCause` (`{ code, label, notApiFault, evidence[] }` | null) — nguyên nhân NGHI NGỜ của kết quả `FAILED`/`ERROR`, `null` cho `PASSED`/`BLOCKED` và kết quả cũ. `code` ∈ `TEST_SETUP` (lỗi cấu hình test, không gọi được target) / `ENVIRONMENT` (timeout, không kết nối, 502/503/504/429) / `AUTH_CONFIG` (nhận 401/403 ngoài kỳ vọng khi dùng token mặc định - case Security cố ý bỏ/sai token KHÔNG bao giờ bị gắn) / `ASSERTION_FAILED` (mã trạng thái ĐÚNG kỳ vọng nhưng assertion về nội dung phản hồi sai - nói thẳng lần fail đến từ nội dung phản hồi chứ không phải mã; `evidence` liệt kê tối đa 5 assertion sai; KHÔNG thuộc nhóm `notApiFault`) / `DATA_STATE` (từng Pass rồi Fail liên tiếp cùng mã, không xen kẽ) / `STABLE_FAILURE` (≥3 lần Fail cùng mã, chưa từng Pass) / `UNDETERMINED`. `notApiFault=true` cho 3 nhóm đầu: bằng chứng mạnh rằng fail không phải do API đang test. `evidence` là các câu bằng chứng hiển thị trực tiếp.

**Đây là NGHI NGỜ kèm bằng chứng, KHÔNG phải kết luận** — kiểm thử black-box không nhìn thấy bên trong hệ thống đích và không phân biệt được bug thật với kỳ vọng test sai (ghi rõ trong `STABLE_FAILURE`). Chấm TẤT ĐỊNH (không gọi AI, 0 token) bằng `FailureCauseClassifier`; ngưỡng (3 lần chạy, 2 lần Fail liên tiếp, 90% timeout) là tham số thiết kế chưa calibrate. Nhãn là KẾT QUẢ CHẤM LÚC CHẠY, không tự tính lại khi có thêm lịch sử. Với `ASSERTION_FAILED` và `UNDETERMINED`, nếu test case đã được SỬA kể từ lần chạy gần nhất ở phiên bản trước thì `evidence` có thêm dòng "đã được sửa ... kết quả lúc đó là Pass/Fail(mã) - lần Fail này có thể do chính thay đổi vừa làm" (lần chạy phiên bản cũ không được đếm vào mẫu nhưng vẫn là manh mối). Lịch sử chỉ so các lần chạy CÙNG phiên bản test (`TestResult.testCaseFingerprint` - hash nội dung gửi đi + kỳ vọng + assertion + liên kết dữ liệu; sửa test thì là phiên bản mới).

Ảnh hưởng tới các API khác (không đổi shape): kết quả `FAILED` có `notApiFault=true` KHÔNG làm `runStatus` của Coverage thành `FAILED` (thành `NO_VERDICT`), KHÔNG tính vào tử/mẫu của "tỷ lệ fail lịch sử" ở Risk (`riskReasons` ghi số lượt đã loại), và KHÔNG tự mở lại Bug Report đã đóng. Tự động hoá Bug Report chạy SAU bước phân loại ở cuối lượt chạy (trước đây chạy ngay lúc lưu từng kết quả). Frontend dùng `components/shared/FailureCause.tsx` (chip, khối bằng chứng, banner), không tự đặt nhãn riêng.

## 5. Naming convention endpoint

- Danh từ số nhiều, kebab-case nếu nhiều từ: `/api/v1/projects`, `/api/v1/test-cases`
- Hành động không chuẩn REST dùng verb rõ ràng: `POST /api/v1/endpoints/{id}/generate-tests`, `POST /api/v1/executions/{id}/cancel`

## Quy tắc bắt buộc khi thay đổi

- Thêm field mới vào response: **được phép** tự do (frontend nên ignore field lạ, không vỡ)
- Đổi tên field, đổi kiểu dữ liệu, hoặc xoá field: **phải cập nhật đồng thời** DTO backend và TypeScript type ở frontend trong cùng 1 PR
- Thêm mã lỗi mới: bổ sung vào bảng ở mục 2 của chính file này
