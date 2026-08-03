---
name: springboot-architecture
description: Kiến trúc và cấu trúc code backend Spring Boot cho dự án AI API Testing Agent. Dùng khi tạo package mới, viết Controller/Service/Repository, thiết kế Entity, hoặc quyết định nơi đặt logic mới trong backend.
---

# Kiến trúc Backend — AI API Testing Agent

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
│   └── ai/           # logic gọi Spring AI (sinh test case, phân tích response)
├── repository/        # Spring Data JPA repository
├── entity/            # JPA entity (Project, Endpoint, TestCase, TestExecution, TestResult)
├── dto/
│   ├── request/       # DTO nhận từ frontend
│   └── response/      # DTO trả về frontend (khớp api-contract)
├── config/            # SecurityConfig, AsyncConfig, SpringAiConfig
├── exception/         # Custom exception + GlobalExceptionHandler
├── security/          # JWT filter, encryption util (AES cho token/API key)
└── job/                # xử lý bất đồng bộ khi chạy test suite lớn
```

## Domain model cốt lõi

- **Project** — chứa nhiều Endpoint
- **Endpoint** — sinh ra từ import OpenAPI/Swagger, chứa path/method/schema
- **TestCase** — do AI sinh hoặc người dùng tự thêm/sửa, gắn với 1 Endpoint
- **TestExecution** — 1 lần chạy test (có thể chạy nhiều TestCase cùng lúc)
- **TestResult** — kết quả từng TestCase trong 1 TestExecution (pass/fail, response, lỗi)

Quan hệ: Project 1-n Endpoint 1-n TestCase 1-n TestResult; TestExecution 1-n TestResult.

## Quy tắc bảo mật dữ liệu

- API Key / Bearer Token của target API (do người dùng nhập để hệ thống test) **phải mã hoá AES-256** trước khi lưu MySQL, giải mã chỉ khi thực thi test, không log ra console/file log dưới bất kỳ hình thức nào
- Mật khẩu người dùng hệ thống: BCrypt, không tự implement hash

## Xử lý bất đồng bộ

- Sinh test case bằng AI và thực thi test suite lớn **không được block request-response chính**
- Dùng `@Async` + `CompletableFuture`, hoặc queue nội bộ nếu suite lớn (>50 test case)
- Trả về ngay `executionId`, frontend poll trạng thái qua endpoint riêng (hoặc dùng `websocket-implementation` nếu cần realtime)

## Xử lý lỗi tập trung

- Toàn bộ exception nghiệp vụ (ProjectNotFoundException, InvalidTokenException, AiGenerationFailedException...) throw từ Service
- `@ControllerAdvice` bắt và convert sang format lỗi chuẩn — xem chi tiết ở skill `api-contract`
- Không bao giờ để exception raw (stack trace) lộ ra response cho client

## Khi thêm chức năng mới

1. Xác định chức năng thuộc domain nào (Project/Endpoint/TestCase/Execution)
2. Entity mới → cân nhắc quan hệ với entity hiện có trước khi tạo bảng mới
3. Logic AI (sinh test case, phân tích lỗi) luôn đặt trong `service/ai/`, không trộn vào service nghiệp vụ thường
4. Mọi endpoint mới phải có DTO request/response riêng, không tái dùng Entity
