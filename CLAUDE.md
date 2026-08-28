# CLAUDE.md

Context nền cho Claude Code khi làm việc trong repo `AI-API-Testing-Assistant`. File này luôn được load — giữ ngắn gọn, đưa chi tiết dài vào skill hoặc docs/ và tham chiếu tới đây.

## Giới thiệu dự án

Hệ thống kiểm thử API thông minh: import OpenAPI/Swagger, dùng AI sinh test case, cho phép người dùng review/sửa trước khi chạy, thực thi test, phân tích kết quả và hỗ trợ sinh bug report.

Đồ án môn học, nhóm 2-4 người.

## Tech stack

- **Backend**: Java, Spring Boot, Spring AI, Spring Security (JWT), MySQL, Swagger Parser, Rest Assured
- **Frontend**: React (Vite), Tailwind CSS, shadcn/ui, TanStack Query
- **AI**: gọi LLM thương mại qua Spring AI (chưa tự host model)
- **Chưa dùng Docker** ở giai đoạn hiện tại

## Cấu trúc thư mục

```
AI-API-Testing-Assistant/
├── docs/
│   └── ROADMAP.md              # tiến độ dự án theo module
├── .claude/skills/              # xem mục Skills bên dưới
├── backend/                     # Spring Boot
│   └── src/main/
│       ├── java/.../service/ai/     # logic gọi LLM (TestCaseGenerationService, ...)
│       └── resources/prompts/       # prompt template thật (.st), nguồn duy nhất — không viết prompt string rải rác trong code
└── frontend/                    # React
```

## Domain model cốt lõi

`User` → `Project` (1-n) → `Endpoint` (1-n) → `TestCase` (1-n) → `TestResult` (n-1) ← `TestExecution`

- Mỗi Project thuộc về 1 User tạo ra nó
- Endpoint sinh ra từ import OpenAPI/Swagger
- TestCase do AI sinh hoặc người dùng tự thêm/sửa
- TestExecution là 1 lần chạy, có thể gồm nhiều TestResult

## Quy tắc bắt buộc

- **Response API**: luôn theo format trong `.claude/skills/api-contract/SKILL.md` — không tự ý đổi cấu trúc response
- **Kiến trúc backend**: theo `.claude/skills/springboot-architecture/SKILL.md` — Controller mỏng, business logic ở Service
- **Bảo mật**: API Key/Token của target API phải mã hoá AES-256 trước khi lưu DB, không log ra console/file dưới mọi hình thức
- **Auth**: JWT, mọi endpoint trừ `/auth/*` đều yêu cầu token hợp lệ. `User` có `role` (`USER`/`ADMIN`) — route `/api/v1/admin/**` chỉ `ADMIN` mới gọi được (xem Module 11 Trang Admin trong roadmap)
- **Async**: sinh test case AI và thực thi test suite không được block request chính — dùng `@Async`
- **Prompt AI**: mọi prompt gửi cho LLM phải nằm trong `backend/src/main/resources/prompts/*.st`, load qua Spring AI `PromptTemplate` — không hardcode prompt string trực tiếp trong Java code. Sửa prompt = sửa file `.st`, không sửa logic Java

## Git workflow

Xem chi tiết: `.claude/skills/deploy-github/SKILL.md`

- Nhánh: `main` (deploy-ready) ← `develop` (tích hợp) ← `feature/<tên>` / `fix/<tên>`
- Commit: `feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`

## Roadmap

File `docs/ROADMAP.md` theo dõi tiến độ dự án theo module (không theo tuần).

- Khi hoàn thành 1 task, tự tick `[x]` tương ứng trong file này
- Khi phát sinh thay đổi kiến trúc/tính năng ngoài kế hoạch ban đầu, cập nhật module liên quan trong roadmap cho khớp thực tế
- Luôn commit roadmap cùng lúc với code liên quan: `docs: cập nhật roadmap - <mô tả ngắn>`
- Khi được hỏi "làm gì tiếp theo" hoặc "task tiếp theo là gì", đọc `docs/ROADMAP.md` để trả lời theo đúng thứ tự module và trạng thái hiện tại
- Khi sửa nội dung prompt AI, sửa trực tiếp file trong `backend/src/main/resources/prompts/`, không tạo thêm doc riêng mô tả prompt

## Skills đang dùng trong repo

| Skill | Dùng khi |
|---|---|
| `springboot-architecture` | Viết/sửa code backend, quyết định vị trí đặt logic |
| `spring-boot-workflow` | Bắt đầu 1 chức năng backend mới, checklist hoàn thành |
| `api-contract` | Tạo/sửa endpoint, hoặc gọi API từ frontend |
| `design-review` | Review UI trước khi hoàn thiện |
| `frontend-design` | Xây component/trang UI mới, cần thẩm mỹ tốt |
| `tailwind-v4-shadcn` | Viết component UI với Tailwind/shadcn |
| `playwright` | Test UI luồng import/sinh test case/dashboard |
| `deploy-github` | Tạo branch, viết commit message, quy trình PR |

## Không làm (ngoài phạm vi MVP hiện tại)

- OAuth / đăng nhập qua GitHub
- Docker hoá (chưa cần ở giai đoạn này)
- Refresh token — access token sống 24h là đủ

**Đã đổi phạm vi:** Multi-role phân quyền (admin/user) từng nằm trong danh sách "Không làm" — nay đã CHỐT làm, xem Module 11 (Trang Admin quản lý hệ thống) trong `docs/ROADMAP.md`. Đăng nhập/đăng ký bằng Google (Google Identity Services, không phải OAuth code flow đầy đủ) cũng đã CHỐT làm — xem Module 13 trong `docs/ROADMAP.md`.
