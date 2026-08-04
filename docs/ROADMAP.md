# Roadmap — AI API Testing Assistant

> Cập nhật lần cuối: 2026-08-04

Roadmap chia theo **module công việc**, làm theo thứ tự từ trên xuống vì module sau phụ thuộc module trước. Trong mỗi module, backend/frontend có thể làm song song.

## Trạng thái chung

| Module | Trạng thái |
|---|---|
| 1. Setup nền tảng | ✅ Xong |
| 2. Quản lý Project | ✅ Xong |
| 3. Import & Parse OpenAPI | ✅ Xong |
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
- [x] Khởi tạo Spring Boot project (Web, JPA, MySQL, Security) — chưa thêm Spring AI, để dành Module 4
- [x] Setup package theo skill `springboot-architecture`
- [x] DB schema — bảng `users`, `projects`, `endpoints`, `test_cases`, `test_executions`, `test_results`
- [x] JWT auth (đăng ký/đăng nhập)

**Frontend**
- [x] Khởi tạo React (Vite) + Tailwind + shadcn
- [x] Layout khung (sidebar, header)
- [x] Trang đăng nhập/đăng ký

**Chung**
- [x] Git: branch `develop`, quy tắc branch/commit đã thống nhất
- [x] `.claude/skills/` đã setup xong (8 skill hiện có)

**Ghi chú:** Entity JPA cho `Project`/`Endpoint`/`TestCase`/`TestExecution`/`TestResult` đã được tạo sẵn ở bước này để thiết lập DB schema đầy đủ cùng lúc (chỉ gồm field/quan hệ tối thiểu). Module 2/3 chỉ cần bổ sung field chi tiết + Service/Controller/DTO, không cần tạo lại entity từ đầu.

---

## 2. Quản lý Project
*Phụ thuộc: Module 1*

**Backend**
- [x] Entity + Repository `Project` (entity đã có từ Module 1, bổ sung `findAllByOwner`)
- [x] Endpoint CRUD `Project` (theo format `api-contract`) — `GET/POST /api/v1/projects`, `GET/PUT/DELETE /api/v1/projects/{id}`, phân trang, giới hạn theo owner (403/404 khi không phải chủ sở hữu)

**Frontend**
- [x] Trang danh sách Project (`/projects`, dạng card grid)
- [x] Form tạo/sửa Project (dialog, dùng chung `ProjectFormDialog`) + xoá (`DeleteProjectDialog`)
- [x] Gọi API bằng tanstack-query (`lib/projects.ts`, `apiFetchPaged`)

**Mốc xác nhận:** tạo Project ở FE → lưu DB → hiển thị lại đúng — xác nhận pipeline DB → API → UI hoạt động. ✅ Đã verify bằng Playwright: đăng ký → tạo → sửa → xem chi tiết → xoá → về lại rỗng.

**Ghi chú quan trọng:** trang chi tiết `/projects/:id` (`ProjectDetailPage.tsx`) đã được tạo sẵn ở module này — route, fetch 1 project theo id (`getProject`), hiển thị tên/mô tả/ngày tạo. Module 3 chỉ cần bổ sung phần hiển thị/quản lý Endpoint vào đúng trang này (đã có khối placeholder đánh dấu vị trí), **không tạo route hay trang mới**.

---

## 3. Import & Parse OpenAPI
*Phụ thuộc: Module 2*

**Backend**
- [x] Tích hợp Swagger Parser (import từ URL và từ file) — `EndpointImportService`, dùng chung logic parse `OpenAPIV3Parser().readContents(...)` cho cả 2 nguồn
- [x] Entity/Repository `Endpoint`, lưu path/method/schema/required fields — bổ sung field `summary`, `EndpointRepository.findAllByProject`/`deleteAllByProject`
- [x] Cấu hình xác thực target API (API Key/Bearer Token) — mã hoá AES-256/GCM trước khi lưu (`AesEncryptionService`, field `Project.targetAuthType`/`targetAuthValueEncrypted`)
- [x] Validate/sanitize URL người dùng nhập (tránh SSRF) — `SafeUrlFetcher` tự fetch (không dùng `readLocation`), chặn scheme khác http/https, IP loopback/private/link-local, không theo redirect
- [x] *(Phát sinh khi test)* Gắn auth đã nhập vào chính request tải URL nguồn (không chỉ lưu lại cho Module 6) — `SafeUrlFetcher.fetch(url, headerName, headerValue)`, `EndpointImportService.fetchUrlContent` map `BEARER_TOKEN → Authorization: Bearer`, `API_KEY → X-API-Key`. Cho phép import URL OpenAPI bị chặn sau đăng nhập.
- [x] *(Phát sinh khi test)* Fix lỗi xoá Project khi đã có Endpoint — MySQL từ chối xoá do vi phạm khoá ngoại `endpoints.project_id` (lỗi 1451/500). `ProjectService.delete()` nay xoá hết Endpoint con trước rồi mới xoá Project, gói trong `@Transactional`.

**Frontend**
- [x] Form import (URL hoặc upload file) — `ImportOpenApiDialog.tsx`, hỗ trợ cả 2 kiểu trong cùng 1 dialog + cấu hình auth tuỳ chọn
- [x] Bổ sung danh sách Endpoint, chọn endpoint cần test **vào trang `ProjectDetailPage.tsx` đã có sẵn từ Module 2** (không tạo trang mới) — `EndpointList.tsx`
- [x] *(Phát sinh khi test)* Tách UI xác thực theo chế độ import — chế độ "Từ file" ẩn khối xác thực (ghi chú sẽ cấu hình lại khi thiết lập chạy test case ở Module 6); chế độ "Từ URL" giữ khối xác thực, đổi label + chú thích rõ là để tải được URL nếu nó yêu cầu đăng nhập. Chặn ở `mutationFn` để auth nhập lúc ở URL mode không bị gửi kèm khi submit ở file mode.

**Mốc xác nhận:** import file OpenAPI mẫu (4 endpoint) qua UI thật (đăng ký → tạo project → import → danh sách endpoint hiển thị đúng method/path/summary) — verify bằng Playwright, kèm test thủ công qua `curl` cho case SSRF bị chặn và mã hoá AES trong DB. Unit test `EndpointImportServiceTest` (9 case) + `ProjectServiceTest` (3 case, cascade delete) pass. Auth-khi-tải-URL đã verify qua `curl`/Playwright với `httpbingo.org` và qua 1 GitHub private repo thật (token đúng → import được, token sai/không có → lỗi 401).

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
- [ ] UI chọn nhiều endpoint trong `EndpointList` (checkbox) — người dùng có nhiều endpoint sau khi import, không bắt sinh test case từng cái một
- [ ] UI trigger sinh test case cho các endpoint đã chọn — gọi lặp API `POST /endpoints/{id}/generate-tests` (đã có sẵn) cho từng endpoint, không cần API batch riêng ở backend
- [ ] Hiển thị trạng thái loading/lỗi khi AI xử lý (theo từng endpoint đang sinh)

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
