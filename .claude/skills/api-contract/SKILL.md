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
| `SWAGGER_PARSE_FAILED` | 422 | Không parse được file/URL OpenAPI |
| `AI_GENERATION_FAILED` | 502 | LLM lỗi hoặc trả về sai định dạng khi sinh test case |
| `TEST_EXECUTION_FAILED` | 500 | Lỗi khi thực thi test (không phải lỗi của API được test) |
| `INTERNAL_ERROR` | 500 | Lỗi hệ thống chung, không xác định được nguyên nhân cụ thể |
| `EMAIL_ALREADY_EXISTS` | 409 | Email đã được đăng ký |
| `INVALID_CREDENTIALS` | 401 | Sai email hoặc mật khẩu |

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

## 5. Naming convention endpoint

- Danh từ số nhiều, kebab-case nếu nhiều từ: `/api/v1/projects`, `/api/v1/test-cases`
- Hành động không chuẩn REST dùng verb rõ ràng: `POST /api/v1/endpoints/{id}/generate-tests`, `POST /api/v1/executions/{id}/cancel`

## Quy tắc bắt buộc khi thay đổi

- Thêm field mới vào response: **được phép** tự do (frontend nên ignore field lạ, không vỡ)
- Đổi tên field, đổi kiểu dữ liệu, hoặc xoá field: **phải cập nhật đồng thời** DTO backend và TypeScript type ở frontend trong cùng 1 PR
- Thêm mã lỗi mới: bổ sung vào bảng ở mục 2 của chính file này
