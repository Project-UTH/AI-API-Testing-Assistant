---
name: springboot-architecture
description: Kiến trúc và cấu trúc code backend Spring Boot cho dự án AI API Testing Assistant. Dùng khi tạo package mới, viết Controller/Service/Repository, thiết kế Entity, hoặc quyết định nơi đặt logic mới trong backend.
---

# Kiến trúc Backend — AI API Testing Assistant

## Nguyên tắc phân lớp (bắt buộc)

```
Controller  → chỉ nhận request, validate input cơ bản, gọi Service, trả DTO
Service     → toàn bộ business logic nằm ở đây
Repository  → chỉ truy vấn dữ liệu, không chứa business logic
```

Không được:
- Viết business logic trong Controller
- Gọi Repository trực tiếp từ Controller
- Expose Entity trực tiếp ra ngoài API (luôn qua DTO)

## Cấu trúc package

```
com.aiapitesting.backend/
├── controller/       # REST endpoint, map request → service
├── service/          # business logic
│   ├── ai/           # logic gọi Spring AI (sinh test case, phân tích response)
│   └── execution/    # engine thực thi test (Rest Assured) + orchestration bất đồng bộ
├── repository/        # Spring Data JPA repository
├── entity/            # JPA entity (Project, Endpoint, TestCase, TestCaseDependency, TestExecution, TestResult)
├── dto/
│   ├── request/       # DTO nhận từ frontend
│   └── response/      # DTO trả về frontend (khớp api-contract)
├── config/            # SecurityConfig, AsyncConfig, SpringAiConfig
├── exception/         # Custom exception + GlobalExceptionHandler
└── security/          # JWT filter, encryption util (AES cho token/API key)
```

## Domain model cốt lõi

- **Project** — chứa nhiều Endpoint, có `targetBaseUrl` (nơi gọi API thật lúc thực thi — khác URL nhập lúc import, đó chỉ là vị trí tài liệu OpenAPI)
- **Endpoint** — sinh ra từ import OpenAPI/Swagger, chứa path/method/schema
- **TestCase** — do AI sinh hoặc người dùng tự thêm/sửa, gắn với 1 Endpoint; `resolvedPath` dùng cú pháp `{{tenThamSo}}` cho cả path-param lẫn query-param (query-param gắn trực tiếp vào cuối path dạng `?name={{name}}`, không có field riêng), `pathParamFallbacks` là giá trị dự phòng dùng chung cho cả 2 loại
- **TestCaseDependency** — Test Data Chaining: 1 TestCase (consumer) lấy giá trị thật từ response của 1 TestCase khác (nguồn) theo JSONPath, gán vào token `{{placeholderName}}`
- **TestExecution** — 1 lần chạy test (nhiều TestCase, thứ tự tính theo topological sort nếu có dependency)
- **TestResult** — kết quả từng TestCase trong 1 TestExecution (`TestResultStatus`: PASSED/FAILED/ERROR/BLOCKED/SKIPPED, response, lỗi)

Quan hệ: Project 1-n Endpoint 1-n TestCase 1-n TestResult; TestExecution 1-n TestResult; TestCase 1-n TestCaseDependency (cả 2 chiều consumer/nguồn).

## Quy tắc bảo mật dữ liệu

- API Key / Bearer Token của target API (do người dùng nhập để hệ thống test) **phải mã hoá AES-256** trước khi lưu MySQL, giải mã chỉ khi thực thi test, không log ra console/file log dưới bất kỳ hình thức nào
- Mật khẩu người dùng hệ thống: BCrypt, không tự implement hash

## Xử lý bất đồng bộ

- Sinh test case bằng AI và thực thi test suite lớn **không được block request-response chính**
- Dùng `@Async` + `CompletableFuture`, hoặc queue nội bộ nếu suite lớn (>50 test case)
- Trả về ngay `executionId`, frontend poll trạng thái qua endpoint riêng (hoặc dùng `websocket-implementation` nếu cần realtime)
- **`@Async` không có tác dụng khi tự gọi qua `this` trong cùng 1 bean** (self-invocation bỏ qua proxy AOP của Spring) — nếu 1 service cần vừa làm việc đồng bộ (validate, tạo bản ghi PENDING) vừa kích hoạt việc chạy nền, tách thành 2 class riêng (vd `TestExecutionService` đồng bộ gọi sang `TestExecutionRunner` có `@Async`), không gộp chung 1 class rồi tự gọi phương thức `@Async` của chính nó
- 2 kiểu dùng `@Async` khác nhau tuỳ độ dài tác vụ: nếu tác vụ ngắn (1 lệnh gọi AI) và frontend cần kết quả ngay, controller có thể trả `CompletableFuture<ResponseEntity<...>>` (như `generate-tests`) — nhưng phải nhớ permit `DispatcherType.ASYNC` trong `SecurityConfig`, nếu không JWT filter không chạy lại ở lần dispatch thứ 2 và trả 401 dù token hợp lệ. Nếu tác vụ có thể kéo dài (nhiều lệnh gọi HTTP ra ngoài, thực thi test suite), controller **không nên** trả `CompletableFuture` — tạo bản ghi trạng thái PENDING đồng bộ, trả về ngay, rồi mới kích hoạt `@Async void` chạy nền (fire-and-forget), frontend tự poll — tránh giữ HTTP connection lâu và né được luôn lớp bug 401-dispatch ở trên

## Xử lý lỗi tập trung

- Toàn bộ exception nghiệp vụ (ProjectNotFoundException, InvalidTokenException, AiGenerationFailedException...) throw từ Service
- `@ControllerAdvice` bắt và convert sang format lỗi chuẩn — xem chi tiết ở skill `api-contract`
- Không bao giờ để exception raw (stack trace) lộ ra response cho client

## Khi thêm chức năng mới

1. Xác định chức năng thuộc domain nào (Project/Endpoint/TestCase/Execution)
2. Entity mới → cân nhắc quan hệ với entity hiện có trước khi tạo bảng mới
3. Logic AI (sinh test case, phân tích lỗi) luôn đặt trong `service/ai/`, không trộn vào service nghiệp vụ thường
4. Mọi endpoint mới phải có DTO request/response riêng, không tái dùng Entity
