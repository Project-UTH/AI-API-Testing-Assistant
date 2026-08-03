# Roadmap — AI API Testing Agent

> Cập nhật lần cuối: (điền ngày mỗi khi sửa)

Roadmap chia theo **module công việc**, làm theo thứ tự từ trên xuống vì module sau phụ thuộc module trước. Trong mỗi module, backend/frontend có thể làm song song.

## Trạng thái chung

| Module | Trạng thái |
|---|---|
| 1. Setup nền tảng | 🟡 Đang làm |
| 2. Quản lý Project | ⬜ Chưa bắt đầu |
| 3. Import & Parse OpenAPI | ⬜ Chưa bắt đầu |
| 4. AI sinh Test Case | ⬜ Chưa bắt đầu |
| 5. Review Test Case | ⬜ Chưa bắt đầu |
| 6. Thực thi Test | ⬜ Chưa bắt đầu |
| 7. Lịch sử & Dashboard | ⬜ Chưa bắt đầu |
| 8. AI phân tích lỗi (stretch) | ⬜ Chưa bắt đầu |
| 9. Hoàn thiện & Demo | ⬜ Chưa bắt đầu |

---

## 1. Setup nền tảng
*Không phụ thuộc module nào — làm trước tiên*

**Backend**
- [ ] Khởi tạo Spring Boot project (Web, JPA, MySQL, Security, Spring AI)
- [ ] Setup package theo skill `springboot-architecture`
- [ ] DB schema — bảng `project`, `endpoint`, `test_case`, `test_execution`, `test_result`
- [ ] JWT auth (đăng ký/đăng nhập)

**Frontend**
- [ ] Khởi tạo React (Vite) + Tailwind + shadcn
- [ ] Layout khung (sidebar, header)
- [ ] Trang đăng nhập/đăng ký

**Chung**
- [ ] Git: branch `develop`, quy tắc branch/commit đã thống nhất
- [ ] `.claude/skills/` đã setup xong (8 skill hiện có)

---

## 2. Quản lý Project
*Phụ thuộc: Module 1*

**Backend**
- [ ] Entity + Repository `Project`
- [ ] Endpoint CRUD `Project` (theo format `api-contract`)

**Frontend**
- [ ] Trang danh sách Project
- [ ] Form tạo/sửa Project
- [ ] Gọi API bằng tanstack-query

**Mốc xác nhận:** tạo Project ở FE → lưu DB → hiển thị lại đúng — xác nhận pipeline DB → API → UI hoạt động.

---

## 3. Import & Parse OpenAPI
*Phụ thuộc: Module 2*

**Backend**
- [ ] Tích hợp Swagger Parser (import từ URL và từ file)
- [ ] Entity/Repository `Endpoint`, lưu path/method/schema/required fields
- [ ] Cấu hình xác thực target API (API Key/Bearer Token) — mã hoá AES trước khi lưu
- [ ] Validate/sanitize URL người dùng nhập (tránh SSRF)

**Frontend**
- [ ] Form import (URL hoặc upload file)
- [ ] Trang danh sách Endpoint, chọn endpoint cần test

---

## 4. AI sinh Test Case
*Phụ thuộc: Module 3*

**Backend**
- [ ] Tích hợp Spring AI, cấu hình ChatClient + timeout/retry
- [ ] Viết prompt template thật tại `backend/src/main/resources/prompts/generate-test-case.st`
- [ ] Service `TestCaseGenerationService` load template + format bằng Spring AI `PromptTemplate`
- [ ] Validate JSON schema test case AI trả về trước khi lưu DB
- [ ] Endpoint `POST /endpoints/{id}/generate-tests`

**Frontend**
- [ ] UI trigger sinh test case
- [ ] Hiển thị trạng thái loading/lỗi khi AI xử lý

---

## 5. Review Test Case
*Phụ thuộc: Module 4*

**Backend**
- [ ] Endpoint sửa/thêm/xoá test case thủ công

**Frontend**
- [ ] UI xem danh sách test case đã sinh
- [ ] UI sửa/thêm/xoá test case trước khi chạy

---

## 6. Thực thi Test
*Phụ thuộc: Module 5*

**Backend**
- [ ] Engine thực thi bằng Rest Assured
- [ ] Xử lý bất đồng bộ (`@Async`) khi chạy nhiều test case
- [ ] Endpoint trigger thực thi + endpoint poll trạng thái (theo `api-contract`)

**Frontend**
- [ ] Nút chạy test, hiển thị trạng thái PENDING/RUNNING/COMPLETED/FAILED

---

## 7. Lịch sử & Dashboard
*Phụ thuộc: Module 6*

**Backend**
- [ ] Lưu lịch sử TestExecution/TestResult
- [ ] Endpoint thống kê pass/fail theo project, theo thời gian

**Frontend**
- [ ] Trang lịch sử kiểm thử
- [ ] Dashboard thống kê (biểu đồ pass/fail)

---

## 8. AI phân tích lỗi (Stretch — làm nếu còn thời gian)
*Phụ thuộc: Module 7*

**Backend**
- [ ] Prompt template `backend/src/main/resources/prompts/analyze-response.st`, giải thích nguyên nhân lỗi
- [ ] Tự động sinh Bug Report từ kết quả fail

**Frontend**
- [ ] Hiển thị phân tích lỗi + bug report trong trang kết quả

---

## 9. Hoàn thiện & Demo
*Phụ thuộc: tất cả module MVP (1-7) đã xong*

- [ ] Polish UI, fix bug toàn luồng
- [ ] Viết tài liệu kỹ thuật
- [ ] Chuẩn bị kịch bản demo
- [ ] Docker hoá (nếu kịp)

---

## Tài liệu tham chiếu

- Response format: `.claude/skills/api-contract/SKILL.md`
- Kiến trúc backend: `.claude/skills/springboot-architecture/SKILL.md`
- Git flow & commit convention: `.claude/skills/deploy-github/SKILL.md`

## Cách cập nhật file này

Tick `- [x]` khi xong 1 task. Đổi trạng thái module trong bảng đầu file (⬜ Chưa bắt đầu / 🟡 Đang làm / ✅ Xong). Commit: `docs: cập nhật roadmap - module X`.
