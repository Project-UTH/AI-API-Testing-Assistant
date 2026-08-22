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

`GET /api/v1/admin/users` (paged) và `GET /api/v1/admin/users/{id}` (1 user) cùng trả `AdminUserResponse` (email/role/enabled/aiDailyTokenLimitOverride/aiTokensToday/aiCallsToday/tổng project-testcase-bugreport) - `{id}` dùng ở trang chi tiết user (Module 11e) để hiện đúng trạng thái quota khi vừa đổi xong mà không phải quay lại trang danh sách.

Admin xem được dữ liệu Project/Endpoint/TestCase/BugReport của MỌI user (không giới hạn owner) nhưng chỉ ở endpoint riêng dưới `/api/v1/admin/users/{userId}/**` (`GET .../projects`, `GET .../projects/{projectId}`, `GET .../projects/{projectId}/endpoints`, `GET .../projects/{projectId}/test-cases`, `GET .../projects/{projectId}/bug-reports`, `GET .../projects/{projectId}/bug-reports/test-cases/{testCaseId}/run-history` - CHỈ ĐỌC, không có method ghi) - không đổi hành vi ownership check của các endpoint thường (`/api/v1/projects/**` vẫn luôn giới hạn theo owner đang đăng nhập, kể cả khi người gọi là ADMIN). `BugReportService.getBugReportsForProject(Project)`/`getRunHistoryForProject(Project, UUID)` (package-private) tách riêng khỏi bản `UUID projectId` công khai để `AdminUserDataService` tái dùng với `Project` đã resolve theo owner chỉ định - không nhân bản logic tổng hợp Dashboard/lồng 3 tầng.

**Quota AI/ngày (Module 11d):** mỗi user có giới hạn TOKEN/NGÀY (giờ UTC, config `ai.quota.daily-token-limit`, mặc định 100000), có thể ADMIN GHI ĐÈ RIÊNG qua `PUT /api/v1/admin/users/{userId}/ai-quota` (body `{ "dailyTokenLimit": number | null }`, `null` = xoá ghi đè, quay lại dùng mặc định hệ thống). Vượt quota HIỆU LỰC (ghi đè nếu có, không thì mặc định) → `POST .../generate-tests` trả `429 AI_QUOTA_EXCEEDED` - chặn TRƯỚC khi gọi AI (không tốn request AI nào cho lần bị chặn). `AdminUserResponse` trả kèm `aiDailyTokenLimitOverride` (null = đang dùng mặc định) và mức dùng token thật hôm nay (`aiTokensToday`) - **frontend admin phải tự resolve `aiDailyTokenLimitOverride ?? aiDailyTokenLimit-hệ-thống` khi hiện "đã dùng/giới hạn" theo TỪNG user, không được dùng thẳng `aiDailyTokenLimit` hệ thống cho mọi hàng** (bug thật đã gặp ở `AdminUsersPage.tsx` khi mới thêm ghi đè riêng - sửa ở Module 11e đợt 2); `AdminDashboardSummaryResponse` trả `totalAiTokensToday` (toàn hệ thống) + `aiDailyTokenLimit` (mặc định hệ thống, KHÔNG phải hiệu lực riêng cho ai) - tất cả lấy từ `usage` thật của Anthropic (`ChatResponse.getMetadata().getUsage()`), không phải ước lượng. `DashboardSummaryResponse` (`/dashboard/summary`, CHÍNH user đang đăng nhập) trả kèm `aiTokensToday` + `aiDailyTokenLimit` - ở đây `aiDailyTokenLimit` đã là mức HIỆU LỰC resolve sẵn cho đúng user đó (không phải mặc định hệ thống thô như ở bản Admin), vì user không cần biết khái niệm "ghi đè" tồn tại.

**Biểu đồ usage AI theo ngày/tuần/tháng (Module 11e):** 3 endpoint cùng trả `AiUsageResponse { daily: [{date, totalTokens, callCount}, ...] }` - LUÔN đủ 90 ngày liên tục gần nhất (kể cả ngày token=0), gộp thành tuần/tháng là việc của FRONTEND (cộng dồn các điểm ngày liên tiếp), backend chỉ bucket theo ngày:
- `GET /api/v1/dashboard/ai-usage` - CỦA CHÍNH user đang đăng nhập (trang Tổng quan thường)
- `GET /api/v1/admin/users/{userId}/ai-usage` - của 1 user cụ thể (khác `/dashboard/ai-usage` - đây do ADMIN gọi cho user KHÁC)
- `GET /api/v1/admin/dashboard/ai-usage` - TOÀN HỆ THỐNG (mọi user gộp lại)

**Audit log (Module 11d):** `GET /api/v1/admin/audit-log` (paged, mới nhất trước) - ghi lại hành động khoá/mở tài khoản VÀ đổi quota AI riêng của admin (`action`: `USER_LOCKED`/`USER_UNLOCKED`/`AI_QUOTA_CHANGED`, kèm `detail` mô tả thêm cho hành động đổi quota). `AdminAuditEvent` lưu SNAPSHOT email (không FK tới `User`) để không mất dấu vết khi tài khoản liên quan bị xoá sau này.

## 5. Naming convention endpoint

- Danh từ số nhiều, kebab-case nếu nhiều từ: `/api/v1/projects`, `/api/v1/test-cases`
- Hành động không chuẩn REST dùng verb rõ ràng: `POST /api/v1/endpoints/{id}/generate-tests`, `POST /api/v1/executions/{id}/cancel`

## Quy tắc bắt buộc khi thay đổi

- Thêm field mới vào response: **được phép** tự do (frontend nên ignore field lạ, không vỡ)
- Đổi tên field, đổi kiểu dữ liệu, hoặc xoá field: **phải cập nhật đồng thời** DTO backend và TypeScript type ở frontend trong cùng 1 PR
- Thêm mã lỗi mới: bổ sung vào bảng ở mục 2 của chính file này
