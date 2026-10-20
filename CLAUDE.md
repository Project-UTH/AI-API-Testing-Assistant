# CLAUDE.md

Context nền cho Claude Code khi làm việc trong repo `AI-API-Testing-Assistant`. File này luôn được load — giữ ngắn gọn, đưa chi tiết dài vào skill hoặc docs/ và tham chiếu tới đây.

## Giới thiệu dự án

Hệ thống kiểm thử API: import OpenAPI/Swagger, sinh test case theo hướng **hybrid** (AI chỉ sinh 1 test gốc hợp lệ được CHẠY THẬT để xác nhận; hệ thống tất định bù checklist EP/BVA/bảo mật/phân quyền, 0 token; AI nghiệp vụ là tuỳ chọn, gắn nhãn GIẢ ĐỊNH), cho phép người dùng review/sửa trước khi chạy, thực thi test, phân loại nguyên nhân Fail và hỗ trợ sinh bug report. Việc sinh chạy NỀN (job) có khung giám sát từng bước, huỷ được.

**Nguyên tắc cốt lõi:** kiểm thử để PHÁT HIỆN lỗi của API đích và báo cho đồ án đích, KHÔNG sửa test/kỳ vọng cho khớp hành vi sai để test Pass (vd API trả 401 thay vì 400 là phát hiện thật, nhãn `API_WRONG_STATUS`, không được bỏ qua). AI là trợ lý có kiểm chứng; hệ thống tất định đảm bảo chất lượng. Hướng tiếp theo: đo độ chính xác bằng lỗi cài sẵn (roadmap 17o).

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
- TestCase do AI sinh (test gốc `AI_BASELINE`, nghiệp vụ `BUSINESS`), hệ thống bù (`SYSTEM_FILLED`) hoặc người dùng tự thêm/sửa
- GenerationJob → GenerationJobStep (1-n): một lần bấm "Sinh test case", ghi từng bước hệ thống/AI để giám sát
- TestExecution là 1 lần chạy, có thể gồm nhiều TestResult

## Quy tắc bắt buộc

- **Response API**: luôn theo format trong `.claude/skills/api-contract/SKILL.md` — không tự ý đổi cấu trúc response
- **Kiến trúc backend**: theo `.claude/skills/springboot-architecture/SKILL.md` — Controller mỏng, business logic ở Service
- **Bảo mật**: API Key/Token của target API phải mã hoá AES-256 trước khi lưu DB, không log ra console/file dưới mọi hình thức
- **Auth**: JWT, mọi endpoint trừ `/auth/*` đều yêu cầu token hợp lệ. `User` có `role` (`USER`/`ADMIN`) — route `/api/v1/admin/**` chỉ `ADMIN` mới gọi được (xem Module 11 Trang Admin trong roadmap)
- **Async**: sinh test case (job nền `generation-jobs`, 202 + polling) và thực thi test suite không được block request chính — dùng `@Async`. Không đặt giới hạn thời gian cứng cho cả job; người dùng thấy từng bước và tự huỷ
- **Enum backend ↔ frontend**: mỗi giá trị enum mới thêm ở backend (nguồn gốc test, nguyên nhân Fail, trạng thái job...) PHẢI thêm vào bảng hiển thị frontend cùng lúc (thiếu một giá trị từng làm trắng trang Test case)
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
- Refresh token — access token sống 24h là đủ

**Đã đổi phạm vi:** Multi-role phân quyền (admin/user) từng nằm trong danh sách "Không làm" — nay đã CHỐT làm, xem Module 11 (Trang Admin quản lý hệ thống) trong `docs/ROADMAP.md`. Đăng nhập/đăng ký bằng Google (Google Identity Services, không phải OAuth code flow đầy đủ) cũng đã CHỐT làm — xem Module 13 trong `docs/ROADMAP.md`. Docker hoá cũng đã CHỐT làm — xem Module 14 trong `docs/ROADMAP.md`.
