# Roadmap — AI API Testing Assistant

> Cập nhật lần cuối: 2026-08-06

Roadmap chia theo **module công việc**, làm theo thứ tự từ trên xuống vì module sau phụ thuộc module trước. Trong mỗi module, backend/frontend có thể làm song song.

## Trạng thái chung

| Module | Trạng thái |
|---|---|
| 1. Setup nền tảng | ✅ Xong |
| 2. Quản lý Project | ✅ Xong |
| 3. Import & Parse OpenAPI | ✅ Xong |
| 4. AI sinh Test Case | ✅ Xong |
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

**Phạm vi đã chốt:** chỉ sinh 3 nhóm test case Cơ bản — **Positive** (happy path), **Negative** (thiếu trường bắt buộc/sai kiểu dữ liệu), **Boundary Value** (giá trị biên). Nhóm Nâng cao (Security Test Cases, Test Data Generation, Assertion Generation) và Module cao cấp (Performance Test Cases — chưa có module riêng; Bug Report Generation đã có vị trí ở Module 8) để dành cho giai đoạn sau, không làm ở đây. Không sinh case xác thực/phân quyền (401/403).

**Backend**
- [x] Tích hợp Spring AI (`spring-ai-bom` 2.0.0, `spring-ai-starter-model-openai`), cấu hình `ChatClient` + timeout/retry (`spring.ai.retry.max-attempts`, `spring.http.client.connect-timeout`/`read-timeout`)
- [x] *(Phát sinh khi làm)* LLM provider thực tế dùng **Groq** (free tier, không cần thẻ) thay vì OpenAI trả phí — do tương thích 100% format OpenAI nên chỉ đổi `spring.ai.openai.base-url=https://api.groq.com/openai/v1` + `chat.options.model=llama-3.3-70b-versatile` + biến môi trường `GROQ_API_KEY`, không đổi dependency hay code Java nào
- [x] Viết prompt template thật tại `backend/src/main/resources/prompts/generate-test-case.st` (chỉ Positive/Negative/Boundary Value)
- [x] Service `TestCaseGenerationService` (`service/ai/`) load template + format bằng Spring AI `PromptTemplate` (delimiter tuỳ chỉnh `<>` để tránh xung đột với JSON nhúng trong prompt), dùng structured output (`ChatClient...entity(...)`)
- [x] Validate test case AI trả về trước khi lưu DB (danh sách không rỗng, tên không blank, `expectedStatus` hợp lệ 100-599) → lỗi thì trả `AI_GENERATION_FAILED` (502), không lưu rác vào DB
- [x] Endpoint `POST /api/v1/projects/{projectId}/endpoints/{endpointId}/generate-tests` (route đầy đủ theo đúng pattern nested resource đang dùng, không phải `/endpoints/{id}/generate-tests` như ghi tắt ban đầu) — `@Async`, trả `CompletableFuture` để không chiếm thread trong lúc chờ AI, nhưng vẫn là 1 request/1 response bình thường phía frontend (không cần polling)
- [x] *(Phát sinh khi làm)* `TestCase` entity bổ sung field `requestHeaders`/`requestBody`/`expectedStatus` (TEXT/Integer) để lưu nội dung test case AI sinh ra — trước đó chỉ có `name`/`description`
- [x] *(Phát sinh khi làm)* Regenerate = xoá hết test case cũ của endpoint rồi lưu lại (`deleteAllByEndpoint` + `saveAll`), giống pattern `EndpointImportService` đang xoá-rồi-sinh-lại endpoint. Cần xem lại hành vi này ở Module 5 khi có test case sửa thủ công, để không xoá nhầm
- [x] *(Bảo mật, quan trọng)* `TestCaseGenerationService` **không đụng tới** `Project.targetAuthType`/`targetAuthValueEncrypted` dưới mọi hình thức — không giải mã, không đưa vào prompt gửi AI, không gắn vào `requestHeaders` của test case. Việc gắn token/API key thật vào request để gọi target API thật để dành cho Module 6 lúc thực thi (đúng quy tắc CLAUDE.md "giải mã chỉ khi thực thi test")
- [x] *(Bug phát sinh khi test, nghiêm trọng)* Mọi lần gọi `generate-tests` đều trả `401 UNAUTHORIZED` dù JWT hợp lệ — do controller trả `CompletableFuture` khiến Spring MVC dispatch lại request lần 2 (`DispatcherType.ASYNC`) để trả response sau khi `@Async` xong; `JwtAuthFilter` (`OncePerRequestFilter`) không chạy lại ở lần dispatch này nên không có gì xác thực, bị chặn ở `.anyRequest().authenticated()`. Xác nhận bằng debug log (`FilterChainProxy`/`AnonymousAuthenticationFilter`), fix bằng `SecurityConfig.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()`. Đồng thời wrap `AsyncConfig.taskExecutor()` bằng `DelegatingSecurityContextAsyncTaskExecutor` để `SecurityContextHolder` (dùng trong `CurrentUserService`) cũng có giá trị đúng bên trong chính luồng xử lý `@Async`.
- [x] *(Bug phát sinh khi test với AI thật)* Tên/mô tả test case AI sinh ra bị lỗi font tiếng Việt (vd. `"Táº¡o"` thay vì `"Tạo"`) — xác nhận bằng cách gọi thẳng Groq API: nội dung AI tự sinh (không phải do người dùng gõ) trả về đúng UTF-8 hoàn toàn, nên lỗi nằm ở phía đọc file `generate-test-case.st`. Nguyên nhân: `PromptTemplate.builder().resource(promptResource)` để Spring AI tự suy ra charset khi đọc `Resource`, trên Windows charset tiến trình (`sun.jnu.encoding`) không đảm bảo là UTF-8 dù JVM 18+ đã set `file.encoding=UTF-8` mặc định — khiến hướng dẫn tiếng Việt gửi cho AI đã bị lỗi font từ trước, AI "bắt chước" luôn kiểu lỗi đó khi trả lời. Fix: đọc rõ `promptResource.getContentAsString(StandardCharsets.UTF_8)` rồi truyền vào `PromptTemplate.builder().template(text)` thay vì `.resource(...)`.

**Frontend**
- [x] UI chọn nhiều endpoint trong `EndpointList` (checkbox, dùng `npx shadcn add checkbox`) — người dùng có nhiều endpoint sau khi import, không bắt sinh test case từng cái một
- [x] UI trigger sinh test case cho các endpoint đã chọn — gọi lặp API `generate-tests` (đã có sẵn) cho từng endpoint đã chọn, không cần API batch riêng ở backend
- [x] Hiển thị trạng thái loading/lỗi khi AI xử lý (theo từng endpoint đang sinh) — gọi thẳng `generateTestCases(...)` (không qua `useMutation`) thành N Promise độc lập, mỗi cái tự cập nhật state riêng
- [x] *(Bug phát sinh khi test, nghiêm trọng)* Chọn nhiều endpoint rồi bấm sinh cùng lúc: chỉ dòng cuối cùng cập nhật đúng trạng thái, các dòng còn lại xoay vòng mãi dù network đã trả response từ lâu (xác nhận bằng Playwright: network tab có đủ request/response, nhưng UI không update). Nguyên nhân: gọi `mutate()` nhiều lần trên cùng 1 instance `useMutation()` — chỉ lần gọi cuối có callback được đảm bảo chạy. Fix: bỏ `useMutation` cho luồng này, gọi thẳng hàm API trả `Promise` cho từng endpoint, tự quản trạng thái qua `setGenerationState`.
- [x] *(Phát sinh khi làm — UX regenerate)* Endpoint đã sinh test case tự bỏ chọn checkbox ngay sau khi thành công (tránh gọi lại AI tốn quota nếu người dùng bấm "Sinh Test Case" nhiều lần liên tiếp), nhưng **không khoá `disabled`** checkbox — người dùng vẫn tự tay tích lại 1 endpoint đã sinh nếu thật sự muốn sinh lại. Khi tích lại, dòng đó đổi từ nhãn xanh "Đã sinh N test case" sang cảnh báo vàng "Sẽ xoá N test case cũ và sinh lại" trước khi bấm nút, khớp đúng hành vi backend (xoá hết rồi lưu bộ mới).

**Mốc xác nhận:** backend build xanh, 18/18 test pass (`./mvnw test`, gồm 5 test mới cho `TestCaseGenerationServiceTest`: sinh thành công, AI trả rỗng, AI trả status không hợp lệ, endpoint không thuộc project, project không phải chủ sở hữu). Spring context load thành công với Spring AI wiring mới (`BackendApplicationTests`). Đã verify end-to-end bằng Playwright thật (đăng ký → tạo project → import 3 endpoint → chọn cả 3 → bấm Sinh Test Case): cả backend (không còn 401 async-dispatch) và frontend (không còn dòng nào bị treo) đều đúng, có ảnh chụp màn hình xác nhận. `npx tsc --noEmit` không lỗi. **Đã verify sinh test case AI thật qua Groq** (`llama-3.3-70b-versatile`, key free tier): endpoint mẫu `POST /users` (có trường `email` bắt buộc) sinh ra đủ 3 nhóm — Positive (`201`, dữ liệu hợp lệ), Negative (`400`, thiếu trường `email`), Boundary Value (`201`, email ngắn hợp lệ) — tên/mô tả tiếng Việt hiển thị đúng sau khi fix lỗi font.

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
