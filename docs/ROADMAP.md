# Roadmap — AI API Testing Assistant

> Cập nhật lần cuối: 2026-08-08

Roadmap chia theo **module công việc**, làm theo thứ tự từ trên xuống vì module sau phụ thuộc module trước. Trong mỗi module, backend/frontend có thể làm song song.

## Trạng thái chung

| Module | Trạng thái |
|---|---|
| 1. Setup nền tảng | ✅ Xong |
| 2. Quản lý Project | ✅ Xong |
| 3. Import & Parse OpenAPI | ✅ Xong |
| 4. AI sinh Test Case | ✅ Xong |
| 5. Review Test Case | ✅ Xong |
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
- [x] *(Bug phát sinh khi test, đã fix)* Trạng thái "đã sinh N test case" bị mất khi rời trang Project rồi quay lại — vì chỉ được lưu ở `generationState` (state nội bộ của `EndpointList`, mất khi component unmount), không có nguồn sự thật từ server. Fix: `EndpointResponse` (backend) bổ sung field `testCaseCount`, tính qua `TestCaseRepository.countByEndpointIds` (1 query GROUP BY cho cả trang, tránh N+1) và gắn vào `EndpointImportService.list()`; frontend dùng `endpoint.testCaseCount` làm giá trị mặc định khi chưa có `generationState` nào ghi đè trong phiên, đồng thời gọi `queryClient.invalidateQueries(["endpoints", projectId])` ngay sau khi sinh xong để đồng bộ lại số liệu thật. Đã verify bằng Playwright: sinh test case → rời trang → quay lại → nhãn "Đã sinh N test case" vẫn hiển thị đúng.

**Mốc xác nhận:** backend build xanh, 18/18 test pass (`./mvnw test`, gồm 5 test mới cho `TestCaseGenerationServiceTest`: sinh thành công, AI trả rỗng, AI trả status không hợp lệ, endpoint không thuộc project, project không phải chủ sở hữu). Spring context load thành công với Spring AI wiring mới (`BackendApplicationTests`). Đã verify end-to-end bằng Playwright thật (đăng ký → tạo project → import 3 endpoint → chọn cả 3 → bấm Sinh Test Case): cả backend (không còn 401 async-dispatch) và frontend (không còn dòng nào bị treo) đều đúng, có ảnh chụp màn hình xác nhận. `npx tsc --noEmit` không lỗi. **Đã verify sinh test case AI thật qua Groq** (`llama-3.3-70b-versatile`, key free tier): endpoint mẫu `POST /users` (có trường `email` bắt buộc) sinh ra đủ 3 nhóm — Positive (`201`, dữ liệu hợp lệ), Negative (`400`, thiếu trường `email`), Boundary Value (`201`, email ngắn hợp lệ) — tên/mô tả tiếng Việt hiển thị đúng sau khi fix lỗi font.

---

## 5. Review Test Case
*Phụ thuộc: Module 4*

**Phạm vi đã chốt:** xem test case theo 2 chế độ trong cùng 1 trang — toàn bộ project (gộp theo endpoint) và lọc riêng 1 endpoint (deep-link từ `EndpointList`). Thêm/sửa/xoá test case thủ công. AI sinh lại (`generate-tests`) chỉ thay thế test case do chính AI sinh trước đó, không đụng tới test case người dùng tự thêm — cần field `source` (`AI_GENERATED`/`MANUAL`) để phân biệt.

**Backend**
- [x] `TestCase` entity bổ sung field `source` (enum `AI_GENERATED`/`MANUAL`, `@Enumerated(STRING)`) để phân biệt nguồn gốc test case
- [x] `TestCaseService` (`service/`, không phải `service/ai/` vì không phải logic AI) — CRUD: `listByProject` (toàn bộ test case của project, có `endpointPath`/`endpointMethod` kèm theo để FE nhóm/hiển thị mà không cần gọi thêm API), `create`/`update`/`delete` theo đúng chain ownership `getOwnedProject → findByIdAndProject → findByIdAndEndpoint` đã dùng ở Module 4
- [x] `TestCaseGenerationService.generate()` đổi sang `deleteAllByEndpointAndSource(endpoint, AI_GENERATED)` thay vì xoá sạch — đúng yêu cầu "chỉ thay case AI sinh, giữ nguyên case tự thêm"; test case AI sinh gắn `source = AI_GENERATED`, test case tạo thủ công gắn `source = MANUAL`
- [x] Endpoint mới: `GET /api/v1/projects/{projectId}/test-cases` (toàn bộ, controller riêng `ProjectTestCaseController`), `POST/PUT/DELETE /api/v1/projects/{projectId}/endpoints/{endpointId}/test-cases[/{testCaseId}]` (thêm vào `TestCaseController` cũ)
- [x] `TestCaseNotFoundException` → 404 `TEST_CASE_NOT_FOUND`, đã bổ sung vào bảng mã lỗi skill `api-contract`
- [x] *(Bug phát sinh, đã fix)* Import lại OpenAPI hoặc xoá Project khi các endpoint liên quan đã có test case → lỗi khoá ngoại `test_cases.endpoint_id` (MySQL 1451/500), vì `EndpointImportService.doImport()` và `ProjectService.delete()` xoá endpoint mà chưa dọn test case trước — cùng loại lỗi đã fix cho `Project`↔`Endpoint` ở Module 3. Fix: thêm `testCaseRepository.deleteAllByEndpointProject(project)` trước bước xoá endpoint ở cả 2 nơi.
- [x] *(Bug phát sinh khi test thật, nghiêm trọng — không unit test nào bắt được vì Mockito không mô phỏng đúng hành vi Hibernate)* `GET .../test-cases` và `PUT .../test-cases/{id}` trả `500 INTERNAL_ERROR` (nuốt lỗi hoàn toàn, không log gì — đã thêm `log.error` vào handler `Exception.class` của `GlobalExceptionHandler` để chẩn đoán được, giữ lại lâu dài). Nguyên nhân thật: `TestCase.endpoint` là `@ManyToOne(LAZY)`, `spring.jpa.open-in-view=false` nên session đóng ngay khi repository method trả về — (1) `findAllByEndpointProject`/`findByIdAndEndpoint` trả `TestCase` với `endpoint` là proxy chưa init, `TestCaseResponse.from()` đọc `endpoint.getPath()` bên ngoài session → `LazyInitializationException`; (2) `TestCaseService.update()` gọi `testCaseRepository.save(testCase)` trên entity đã có id → JPA dùng `merge()` nội bộ, trả về 1 bản managed KHÁC với association `endpoint` không cascade MERGE nên bị "quên" state đã init, quay lại thành proxy. Fix (1): thêm `JOIN FETCH tc.endpoint` vào 2 query trên. Fix (2): `update()` không dùng entity trả về từ `save()` nữa mà build response từ chính đối tượng `testCase` đang giữ (đã init sẵn `endpoint` từ bước fetch).

**Frontend**
- [x] `TestCasesPage` (route `/projects/:id/test-cases`, đọc thêm `?endpointId=` qua `useSearchParams` để lọc) — 1 trang duy nhất phục vụ cả "xem toàn bộ" (không filter) và "xem riêng 1 endpoint" (có filter), gộp test case theo endpoint phía client từ 1 lần gọi `listTestCases` (không cần API riêng cho từng chế độ vì dữ liệu nhỏ)
- [x] `TestCaseFormDialog`/`DeleteTestCaseDialog` (thư mục mới `components/testcases/`) — sao chép đúng pattern `ProjectFormDialog`/`DeleteProjectDialog` đã có (dialog + `useMutation` + invalidate query + hiện lỗi qua `ApiError`)
- [x] Điểm vào: nút "Xem tất cả Test Case" ở `ProjectDetailPage` (cạnh "Import OpenAPI"), link "Xem test case" ở từng dòng `EndpointList` khi `testCaseCount > 0` (điều hướng có kèm `?endpointId=`)
- [x] *(Phát sinh khi làm)* Đổi nhãn `EndpointList` từ "Đã sinh N test case" → "Có N test case" (số này giờ gồm cả case tự thêm, không chỉ AI sinh); cảnh báo regenerate đổi từ "Sẽ xoá N test case cũ..." sang câu tĩnh không kèm số vì giờ không xoá sạch nữa, chỉ thay case AI

**Mốc xác nhận:** backend build xanh, toàn bộ test pass (`./mvnw test`, gồm `TestCaseServiceTest` mới + cập nhật `TestCaseGenerationServiceTest`/`EndpointImportServiceTest`/`ProjectServiceTest`). `npx tsc --noEmit` sạch. Đã verify end-to-end bằng Playwright + curl thật: sinh AI → xem toàn bộ (nhãn "AI") → thêm thủ công (nhãn "Tự thêm") → sửa (tên đổi đúng, `source` giữ nguyên) → xem lọc riêng theo endpoint từ `EndpointList` → sinh lại AI (chỉ 5 case AI bị thay, case tự thêm vẫn còn) → xoá case tự thêm (mất đúng 1) → import lại OpenAPI cho project đã có test case (201, không còn 500). Dữ liệu cũ tạo trước khi có field `source` (23 dòng, từ Module 4) tự động nhận đúng giá trị `AI_GENERATED` sau khi Hibernate thêm cột — do MySQL dùng giá trị ENUM khai báo đầu tiên làm default ngầm khi `sql_mode` không bật strict, và enum khai báo đúng thứ tự `AI_GENERATED` trước `MANUAL` nên trùng khớp về mặt ngữ nghĩa (toàn bộ test case cũ đều thật sự do AI sinh).

---

## 6. Thực thi Test
*Phụ thuộc: Module 5*

**Vấn đề đã phát hiện khi bàn trước khi code (chưa xử lý ở bản cơ bản):** `TestCase` hiện chỉ có `requestHeaders`/`requestBody`/`expectedStatus` — không có field nào lưu giá trị cụ thể cho path parameter/query parameter. Với endpoint kiểu `GET /pet/{petId}`, không có chỗ nào biết thay `{petId}` bằng giá trị nào khi gọi thật. Đã chốt hướng xử lý tối thiểu: AI sinh thêm field `resolvedPath` (đường dẫn đã thay placeholder bằng giá trị cụ thể, ví dụ `/pet/1`) — cần làm **trước** khi engine thực thi chạy được các endpoint có tham số. Hướng đầy đủ hơn (test data chaining) xem mục "Nâng cao" bên dưới.

**Backend**
- [ ] Bổ sung `resolvedPath` vào `TestCase`/`GeneratedTestCase`/prompt `generate-test-case.st` — AI phải trả về path đã thay placeholder bằng giá trị cụ thể hợp lệ theo schema (không chỉ path gốc có `{}`)
- [ ] Engine thực thi bằng Rest Assured, dùng `resolvedPath` (không phải `Endpoint.path` thô) để build request thật
- [ ] Xử lý bất đồng bộ (`@Async`) khi chạy nhiều test case
- [ ] Endpoint trigger thực thi + endpoint poll trạng thái (theo `api-contract`)

**Frontend**
- [ ] Nút chạy test, hiển thị trạng thái PENDING/RUNNING/COMPLETED/FAILED

**Giới hạn đã biết (chấp nhận cho bản cơ bản):** chỉ so sánh `expectedStatus` thật trả về so với dự kiến — không so sánh nội dung response (đó là Assertion Generation, xem Nâng cao). Nghĩa là bắt được lỗi sai status/lỗi server, không bắt được lỗi "status đúng nhưng data sai".

### Nâng cao (stretch — làm sau khi bản cơ bản chạy ổn, không thuộc MVP)

**Test Data Chaining** — 1 test case dùng lại dữ liệu thật lấy từ response của 1 test case khác đã chạy trước (vd. `POST /pet` tạo pet thật, lấy `id` trả về, dùng cho `GET /pet/{petId}` thay vì đoán 1 id có thể không tồn tại). Đáng tin cậy hơn hẳn `resolvedPath` (dùng ID thật thay vì AI đoán) nhưng đụng cả 4 lớp kiến trúc, quy mô ngang 1 module riêng:

- [ ] **Data model**: `TestCase` thêm field khai báo "capture" (JSONPath trích giá trị từ response, vd. `{"petId": "$.id"}`) cho test case sinh dữ liệu; thêm cú pháp placeholder (vd. `{{petId}}`) dùng được trong `resolvedPath`/`requestBody`/`requestHeaders` của test case tiêu thụ; thêm quan hệ phụ thuộc giữa 2 test case (test case nào phải chạy trước)
- [ ] **Execution engine**: sắp thứ tự chạy theo dependency thay vì độc lập/song song như thiết kế cơ bản; sau khi chạy test case "sinh dữ liệu" thành công, parse response JSON thật theo JSONPath đã khai báo, lưu vào 1 bộ nhớ biến gắn với lần `TestExecution` đó; thay `{{petId}}` bằng giá trị thật trước khi gửi request của test case phụ thuộc; thêm trạng thái mới (`BLOCKED`/`SKIPPED`) cho test case phụ thuộc khi test case nguồn fail hoặc bị bỏ qua — cần cập nhật danh sách trạng thái hợp lệ trong skill `api-contract` mục 4
- [ ] **Sinh bằng AI**: **không** để AI tự suy luận quan hệ giữa các endpoint (rủi ro chọn nhầm endpoint/field để chain, vì `generate-tests` hiện chỉ nhìn 1 endpoint/lần) — chaining phải do người dùng tự khai báo thủ công ở Module 5, AI chỉ hỗ trợ gợi ý sau này nếu cần
- [ ] **UI (Module 5)**: trong `TestCaseFormDialog`, thêm cách đánh dấu 1 trường là "động" (chọn test case nguồn + JSONPath) thay vì nhập giá trị tĩnh; hiển thị được chuỗi phụ thuộc; chặn phụ thuộc vòng (A phụ thuộc B, B phụ thuộc A)

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
