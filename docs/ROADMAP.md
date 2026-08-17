# Roadmap — AI API Testing Assistant

> Cập nhật lần cuối: 2026-08-10 (verify Module 6 + Module 7 toàn bộ từ đầu tới cuối bằng Playwright + API thật với `shop-api-target`; phát hiện + fix 2 bug quan trọng nhất trong toàn bộ dự án: **AI sinh test case thiếu field bắt buộc vì `$ref` schema không được resolve** (Module 4) và **AI làm rớt segment tĩnh nằm sau tham số path khi có query param** (Module 6) — cả 2 đều ảnh hưởng diện rộng tới chất lượng test case AI sinh, không riêng gì 1 endpoint)

Roadmap chia theo **module công việc**, làm theo thứ tự từ trên xuống vì module sau phụ thuộc module trước. Trong mỗi module, backend/frontend có thể làm song song.

## Trạng thái chung

| Module | Trạng thái |
|---|---|
| 1. Setup nền tảng | ✅ Xong |
| 2. Quản lý Project | ✅ Xong |
| 3. Import & Parse OpenAPI | ✅ Xong |
| 4. AI sinh Test Case | ✅ Xong |
| 5. Review Test Case | ✅ Xong |
| 6. Thực thi Test | ✅ Xong |
| 7. Test Data Chaining | ✅ Xong |
| 8. Lịch sử & Dashboard | ✅ Xong |
| 9. Nâng cao Test Case AI sinh (Security, Assertion, Test Data) | ⬜ Chưa bắt đầu |
| 10. AI phân tích lỗi (stretch) | ⬜ Chưa bắt đầu |
| 11. Hoàn thiện & Demo | ⬜ Chưa bắt đầu |

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

**Phạm vi đã chốt:** chỉ sinh 3 nhóm test case Cơ bản — **Positive** (happy path), **Negative** (thiếu trường bắt buộc/sai kiểu dữ liệu), **Boundary Value** (giá trị biên). Nhóm Nâng cao (Security Test Cases, Test Data Generation, Assertion Generation) và Module cao cấp (Performance Test Cases — chưa có module riêng; Bug Report Generation đã có vị trí ở Module 9) để dành cho giai đoạn sau, không làm ở đây. Không sinh case xác thực/phân quyền (401/403).

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
- [x] *(Bug nghiêm trọng nhất phát hiện trong toàn bộ dự án — tìm ra khi test bằng `shop-api-target`, tái hiện được 3/3 lần độc lập, không phải flaky)* Test case Positive do AI sinh liên tục thiếu field bắt buộc (vd `stock`) dù schema OpenAPI khai báo rõ `required`. Nguyên nhân gốc: `EndpointImportService` chỉ bật `ParseOptions.setResolve(true)` — option này CHỈ resolve `$ref` trỏ ra ngoài file (multi-file spec); `$ref` nội bộ dạng `"#/components/schemas/ProductRequest"` (kiểu khai báo phổ biến nhất, dùng trong hầu hết spec OpenAPI thực tế kể cả Petstore) vẫn giữ nguyên dạng `{"$ref": "..."}` khi serialize `Operation` để nhét vào prompt — `properties`/`required`/`type` của schema đó đều là `null`. AI hoàn toàn không thấy field nào tồn tại, chỉ đoán mò theo tên/mô tả endpoint. Fix: thêm `ParseOptions.setResolveFully(true)` — inline thật properties/required/type vào cây `Operation` trước khi serialize. Đã verify: sinh lại `POST /api/products` sau khi resolveFully, field `stock` xuất hiện đúng cả 4/4 test case; chạy Test Data Chaining thật (`POST` tạo → `GET` dùng đúng id) `PASSED` cả 2 không cần sửa tay gì. Sửa thêm prompt `generate-test-case.st` nhấn mạnh đối chiếu `required` array làm lớp bảo vệ thứ 2.

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

**Vấn đề đã phát hiện khi bàn trước khi code:**
- `TestCase` hiện chỉ có `requestHeaders`/`requestBody`/`expectedStatus` — không có chỗ lưu giá trị cụ thể cho path/query parameter, không chạy được endpoint kiểu `GET /pet/{petId}`.
- Hệ thống chưa có nơi lưu base URL của target API — `Endpoint.path` chỉ là path tương đối, import hiện đọc schema OpenAPI nhưng bỏ qua `servers[]`. URL người dùng nhập lúc import là vị trí **tài liệu OpenAPI**, khác hoàn toàn với base URL của **API thật** cần gọi lúc thực thi (có thể khác domain, và import từ file thì không có URL nào để suy luận cả) — 2 khái niệm phải tách bạch rõ.
- AI đoán cụ thể 1 giá trị path-param (vd `/pet/1`) gần như chắc chắn sai khi gọi API thật có state (server không có sẵn `id=1`) — quyết định dùng cú pháp placeholder `{{tenThamSo}}` (khớp tên tham số OpenAPI) thay vì giá trị đoán cụ thể, kèm 1 field fallback riêng cho giá trị đoán. Việc lấy được giá trị **thật** (không phải đoán) cho placeholder này là việc của Module 7.

**Backend**
- [x] `Project` thêm `targetBaseUrl` — suy ra từ `openApi.getServers()[0]` lúc import (cả 2 nguồn file/URL, lọc bỏ server mặc định `"/"` mà swagger-parser tự điền khi spec không khai báo `servers`), luôn để người dùng xem lại/sửa tay trong dialog import trước khi xác nhận
- [x] Trích `TargetAuthHeaderResolver` dùng chung cho việc map `TargetAuthType → header`, dùng lại ở cả `EndpointImportService` và `RestAssuredTestRunner`
- [x] Bổ sung `TestExecutionRepository.deleteAllByProject`, `TestResultRepository.deleteAllByTestCaseEndpointProject`, gọi trước khi bulk-xoá `TestCase`/`Project` ở `EndpointImportService.doImport()`/`ProjectService.delete()` — tránh lỗi khoá ngoại 1451 lần 3
- [x] `TestCase` thêm `resolvedPath` + `pathParamFallbacks`
- [x] `TestCaseGenerationService`/prompt `generate-test-case.st` sinh thêm `resolvedPath`/`pathParamFallbacks` theo đúng quy tắc đã chốt
- [x] *(Bug phát hiện khi người dùng tự tay dùng thật sau demo, không phải lỗi vặt)* Hệ thống ban đầu không có khái niệm tham số **query** (OpenAPI `"in": "query"`) — chỉ có path-param và requestBody/Headers. Endpoint kiểu `POST /pet/{petId}` (Petstore "update with form data") nhận `name`/`status` qua query string chứ không phải JSON body; AI sinh test case không biết biểu diễn ở đâu nên để trống, khiến request thật gửi đi thiếu tham số bắt buộc, luôn fail dù app không có bug. Fix: không thêm field mới — tái dùng nguyên cơ chế `{{tenThamSo}}` đã có, gắn thẳng query string vào cuối `resolvedPath` (vd `/pet/{{petId}}?name={{name}}`), giá trị dự phòng vẫn chung 1 object `pathParamFallbacks`. `TestCasePathValidator`/`RestAssuredTestRunner`/engine thực thi không cần sửa gì (đã tổng quát hoá sẵn cho mọi token `{{}}` trong `resolvedPath`, không phân biệt path hay query) — chỉ cần dạy lại prompt AI + cập nhật hint UI
- [x] *(Bug tiếp theo phát hiện khi test query-param với `shop-api-target` — endpoint có tham số path KHÔNG nằm ở cuối)* Với path dạng `/pet/{petId}/stock` (segment tĩnh "stock" nằm SAU tham số path), AI sinh `resolvedPath` bị RỚT MẤT segment "stock", ra `/pet/{{petId}}?quantity=...` — gọi nhầm sang path khác (path đó tồn tại cho method khác nên Spring trả lỗi rõ ràng `HttpRequestMethodNotSupportedException`, không phải 404 mơ hồ). Xác nhận không phải lỗi `RestAssuredTestRunner` (gọi thẳng bằng `curl` tới đúng path có "/stock" thành công 200; thêm test tích hợp mới `run_patchMethodWithTrailingStaticSegmentAndQueryParams...` xác nhận runner gửi đúng verb/path khi `resolvedPath` đúng). Nguyên nhân: ví dụ trong prompt cho quy tắc query-param trước đó chỉ có trường hợp tham số path nằm cuối (`/pet/{petId}`), khiến AI ngộ nhận luôn vậy. Fix: thêm quy tắc + ví dụ rõ ràng cho trường hợp có segment tĩnh theo sau tham số path.
- [x] `TestCasePathValidator` (service dùng chung, gọi từ cả `TestCaseGenerationService` và `TestCaseService`): kiểm token còn sót 1-ngoặc, mỗi token `{{}}` phải có fallback; đồng thời có `extractPlaceholders`/`substitute` dùng lại ở engine thực thi và gợi ý liên kết (Module 7)
- [x] `TestResult` thiết kế lại: bỏ `passed`, thêm `status` (`TestResultStatus`), unique `(execution_id, test_case_id)`; `ExecutionStatus` ghi rõ ngữ nghĩa COMPLETED/FAILED trong Javadoc
- [x] Thêm `io.rest-assured:rest-assured`; `service/execution/RestAssuredTestRunner` build request thật, thay token cả trong `requestBody`/`requestHeaders` (không chỉ path), gắn auth target giải mã đúng lúc thực thi
- [x] *(Bug phát sinh khi viết test tích hợp, nghiêm trọng)* Mọi lệnh gọi `RestAssuredTestRunner.run()` đều crash `NullPointerException` sâu trong `groovy.lang.MetaClassImpl`/`ClosureMetaClass` — nguyên nhân: `rest-assured:5.5.1` không khai báo cứng version Groovy, Maven tự chọn bản mới nhất có trong repo (`org.apache.groovy:groovy:5.0.6`), không tương thích với engine Groovy nội bộ mà rest-assured dùng để gửi request (được build/test cho Groovy 4.x). Không phát hiện được nếu chỉ mock `RestAssuredTestRunner` (như ở `TestExecutionRunnerTest`) — chỉ lộ ra khi có test tích hợp gọi `run()` thật. Fix: ghim `org.apache.groovy:groovy`/`groovy-xml`/`groovy-json` về `4.0.28` qua `dependencyManagement` trong `pom.xml`.
- [x] `service/execution/TestExecutionService` (đồng bộ, orchestration) + `service/execution/TestExecutionRunner` (`@Async` riêng) — *(Phát sinh khi làm, khác thiết kế phác thảo ban đầu)* tách thành 2 class thay vì gộp 1, vì Spring AOP proxy-based không kích hoạt `@Async` khi tự gọi qua `this` trong cùng 1 bean (self-invocation) — nếu gộp chung, `runInBackground` sẽ chạy đồng bộ, chặn luôn request thay vì chạy nền. Hành vi bên ngoài (trigger trả PENDING ngay, chạy nền tuần tự theo `createdAt`) đúng như thiết kế
- [x] `POST /api/v1/projects/{projectId}/executions`, `GET /api/v1/projects/{projectId}/executions/{executionId}` (`TestExecutionController`) theo `api-contract`; skill `api-contract` đã cập nhật giải thích `TestResultStatus` tách biệt `ExecutionStatus`, thêm mã lỗi `TEST_EXECUTION_NOT_FOUND`
- [x] *(Lỗ hổng phát hiện khi tự dựng 1 API demo riêng để test Module 6/7 — `SafeUrlFetcher` chặn đúng thiết kế mọi URL nội bộ/localhost lúc import, nên phải import bằng file; nhưng import bằng file không gọi ra ngoài nên không set được `targetAuthType`, và trước đó KHÔNG có chỗ nào khác để cấu hình auth cho target)* Thêm `PUT /api/v1/projects/{projectId}/target-auth` (`ProjectService.updateTargetAuth`) — cấu hình/đổi/xoá auth gọi API thật độc lập hoàn toàn với lúc import, dùng được bất cứ lúc nào. Khác semantics với auth lúc import: ở đây `authType: NONE` là hành động rõ ràng xoá auth (lúc import, `NONE` nghĩa là giữ nguyên auth cũ). Dedupe `validateTargetAuthValue` dùng chung giữa `ProjectService` và `EndpointImportService`. `ProjectResponse` trả thêm `targetBaseUrl`/`targetAuthType` (không bao giờ trả giá trị đã mã hoá)
- [x] *(Bug thật, nghiêm trọng — phát hiện khi đổi test case nguồn của 1 dependency đã có sẵn qua UI)* Đổi dependency (xoá dependency cũ, thêm dependency mới cho CÙNG 1 placeholder, trong CÙNG 1 lần lưu) luôn crash 500 `Duplicate entry ... for key 'uk_test_case_dependencies_test_case_placeholder'`. Nguyên nhân: `TestCaseDependencyRepository.deleteAllByTestCase` là derived delete method (không `@Modifying`) — Hibernate chỉ đánh dấu xoá trong persistence context, còn thứ tự flush MẶC ĐỊNH của Hibernate luôn chạy hết INSERT trước rồi mới tới DELETE bất kể thứ tự gọi trong code, khiến dòng insert mới (cùng khoá `test_case_id + placeholder_name` với dòng sắp bị xoá) chạy trước, vi phạm unique constraint. Fix: đổi thành `@Modifying @Query("DELETE FROM ...")` để ép chạy bulk delete thật ngay lập tức, không chờ flush

**Frontend**
- [x] `ImportOpenApiDialog.tsx` thêm ô "Target Base URL" (luôn hiển thị, không tự động ẩn)
- [x] `TestCaseFormDialog.tsx` thêm ô `resolvedPath`/`pathParamFallbacks`, hint text cập nhật để nói rõ query-param dùng chung cú pháp `{{}}` gắn vào cuối path
- [x] `TestCasesPage.tsx` thêm chọn nhiều test case (checkbox) + nút "Chạy Test", điều hướng sang trang kết quả sau khi trigger
- [x] Trang mới `TestExecutionPage.tsx` (route `/projects/:id/executions/:executionId`) — poll bằng `refetchInterval` khi PENDING/RUNNING, hiện từng kết quả với badge PASSED/FAILED/ERROR/BLOCKED/SKIPPED; bấm vào 1 dòng kết quả để mở rộng xem chi tiết (response body format lại thành JSON dễ đọc, thông báo lỗi đầy đủ không bị cắt) — dữ liệu backend đã trả sẵn từ đầu, chỉ thiếu chỗ hiển thị
- [x] *(Bug phát hiện khi người dùng tự tay dùng thật, không lộ ra qua test tự động vì test tự động không đo layout)* Dialog dùng chung (`components/ui/dialog.tsx`) không có `max-height`/`overflow-y-auto` — nội dung dài hơn màn hình bị cắt mất, không cuộn được để bấm nút "Lưu thay đổi", ảnh hưởng mọi dialog dài trong app chứ không riêng 1 chỗ. Fix: thêm `max-h-[85vh] overflow-y-auto` vào `DialogContent`
- [x] *(Bug tương tự)* Dropdown "Test case nguồn" trong khối Test Data Chaining bị bóp xuống ~46px (không đủ hiện chữ) do dialog gốc chỉ `sm:max-w-sm` (384px) trong khi hàng chứa nó có 2 ô input cố định bề rộng + 1 nút. Fix: nới riêng dialog `TestCaseFormDialog` lên `sm:max-w-2xl`
- [x] *(Bug tương tự)* Bấm "Thêm" ở form gán dependency thủ công khi chưa chọn nguồn/điền thiếu/gõ sai tên placeholder chỉ lặng lẽ không làm gì, không báo lỗi. Fix: thêm thông báo lỗi rõ ràng theo từng trường hợp; đồng thời đổi ô "Placeholder" từ nhập tự do sang dropdown chọn đúng token `{{}}` có thật trong `resolvedPath`/`requestBody`/`requestHeaders` — loại bỏ khả năng gõ sai định dạng ngay từ đầu

**Giới hạn đã biết (chấp nhận cho bản cơ bản):** chỉ so sánh `expectedStatus` thật trả về so với dự kiến — không so sánh nội dung response (đó là Assertion Generation, không thuộc phạm vi module này).

**Mốc xác nhận:** `./mvnw test` xanh (bao gồm `RestAssuredTestRunnerTest` — test tích hợp thật với `com.sun.net.httpserver.HttpServer` cục bộ, kiểm method/path/header/body/status/response đúng thật, không mock), `npx tsc --noEmit` sạch, `npm run build` thành công. **Đã verify bằng Playwright E2E thật** với backend+MySQL+Swagger Petstore demo công khai (`https://petstore3.swagger.io/api/v3`): đăng ký → tạo project → import OpenAPI kèm `targetBaseUrl` → AI sinh test case → chạy test → nhận đúng status thật (bao gồm cả trường hợp Petstore demo tự trả `500` — hệ thống ghi nhận đúng `FAILED`, không phải lỗi của mình).

---

## 7. Test Data Chaining
*Phụ thuộc: Module 6*

**Phạm vi đã chốt:** 1 test case dùng lại dữ liệu thật lấy từ response của 1 test case khác đã chạy trước (vd `POST /pet` tạo pet thật, lấy `id` trả về, dùng cho `GET /pet/{petId}` thay vì đoán 1 id có thể không tồn tại). Chaining hoàn toàn **opt-in do người dùng xác nhận** — AI không tự suy luận quan hệ giữa các endpoint (rủi ro chọn nhầm nguồn, vì `generate-tests` chỉ nhìn 1 endpoint/lần) — nhưng có **gợi ý tự động bằng quy tắc tất định** (dò endpoint `POST` cùng resource) để giảm ma sát của việc phải tự nhớ gán, kể cả khi người dùng sinh test case cho endpoint con trước endpoint cha.

**Backend**
- [x] Entity + repository `TestCaseDependency` — quản lý qua `TestCaseRequest.dependencies`, xoá-hết-tạo-lại mỗi lần lưu (`TestCaseService.saveDependencies`); validate nguồn phải thuộc cùng project (chặn tham chiếu chéo project khác)
- [x] Validate `placeholderName` gửi lên phải khớp đúng 1 token `{{}}` thật có trong `resolvedPath`/`requestBody`/`requestHeaders` của chính test case — chặn lưu dependency "mồ côi" do gõ sai tên
- [x] `TestExecutionService` mở rộng: BFS bao đóng bắc cầu ra `autoIncludedTestCaseIds`; xây đồ thị phụ thuộc gồm cạnh tường minh + cạnh ngầm định (`DELETE` luôn chạy sau mọi test dùng chung nguồn); kiểm tra cycle (DFS) trước khi sắp lịch; Kahn's algorithm ra **1 thứ tự tuyến tính duy nhất** (tie-break `createdAt`) — không còn khái niệm "wave"/song song, dùng chung 1 vòng lặp tuần tự với bản Module 6
- [x] `TestExecutionRunner`: `BLOCKED` khi nguồn không `PASSED` hoặc `io.restassured.path.json.JsonPath` không trích được giá trị (tự bỏ tiền tố `$.` nếu có vì thư viện dùng cú pháp GPath); giá trị thật ưu tiên cao hơn `pathParamFallbacks`; lan truyền `BLOCKED` tự nhiên qua map trạng thái tuần tự
- [x] Guard `TestCaseService.ensureNoDependents()` — dùng chung cho cả xoá tay (`delete()`) và regenerate (`TestCaseGenerationService.generate()`, inject `TestCaseService`) — mã lỗi `TEST_CASE_HAS_DEPENDENTS` (409, đã thêm vào bảng mã lỗi `api-contract`)
- [x] Dọn `TestCaseDependency` trước khi bulk-xoá ở `doImport()`/`ProjectService.delete()` — chuỗi xoá đầy đủ: `TestResult` → `TestExecution` → `TestCaseDependency` → `TestCase` → `Endpoint` → `Project`
- [x] `DependencySuggestionService` + `GET .../test-cases/{id}/dependency-suggestions` (thêm vào `TestCaseController`) — cắt path theo đúng vị trí tham số (`EndpointRepository.findByProjectAndPathAndMethod`, `TestCaseRepository.findAllByEndpointOrderByCreatedAtAsc`), verify riêng cả trường hợp tham số cuối path lẫn tham số giữa path lồng nhau
- [x] *(Bug phát hiện khi chạy Playwright E2E thật, không lộ ra ở unit test vì toàn bộ mock ở tầng response)* `TestExecutionResponse.from()` (dùng bởi `GET /executions/{id}` — endpoint mà trang kết quả poll liên tục) luôn trả `autoIncludedTestCaseIds` rỗng (hardcode `List.of()`), chỉ response ban đầu của `POST /executions` (`pending()`) có giá trị đúng — nhưng frontend không dùng response đó để render, chỉ dùng `execution.id` để điều hướng rồi luôn fetch lại qua GET. Hệ quả: banner "N test case phụ trợ tự động chạy kèm" không bao giờ hiện được dù cơ chế auto-include chạy đúng phía sau. Fix: thêm cột `autoIncluded` (boolean) vào `TestResult`, `TestExecutionRunner`/`TestExecutionService` truyền `autoIncludedIds` xuống để đánh dấu đúng lúc lưu từng kết quả, `TestExecutionResponse.from()` tính lại `autoIncludedTestCaseIds` từ chính danh sách `TestResult` đã lưu thay vì hardcode

**Frontend**
- [x] `TestCaseFormDialog.tsx`: khối "Phụ thuộc dữ liệu" — gọi gợi ý khi mở ở chế độ sửa, banner "Áp dụng" theo từng tham số chưa có dependency; gán thủ công (dropdown toàn bộ test case trong project trừ chính nó, nhập JSONPath + placeholder); chặn cycle phía client bằng DFS trên dữ liệu `dependencies` đã tải kèm mỗi test case; hiện danh sách placeholder khả dụng phát hiện từ `resolvedPath`/`requestBody`/`requestHeaders` để giảm gõ sai tên
- [x] `TestExecutionPage.tsx` (làm ở Module 6, đã có sẵn từ đầu): banner "N test case phụ trợ tự động chạy kèm" khi có `autoIncludedTestCaseIds`, badge `BLOCKED`/`SKIPPED`

**Mốc xác nhận:** `./mvnw test` xanh — gồm `TestExecutionServiceTest` (BFS auto-include, cycle giữa 2 test case chọn tường minh, `DELETE` luôn xếp cuối dù `createdAt` sớm hơn), `TestExecutionRunnerTest` (dependency PASSED dùng giá trị thật thay fallback, dependency nguồn fail → consumer `BLOCKED` không gọi target API, kết quả `TestResult.autoIncluded` đánh dấu đúng), `DependencySuggestionServiceTest` (tham số cuối path lẫn giữa path lồng nhau), guard `TestCaseHasDependentsException` ở cả `TestCaseServiceTest`/`TestCaseGenerationServiceTest`. `npx tsc --noEmit` sạch, `npm run build` thành công. **Đã verify bằng Playwright E2E thật**: sinh test case cho `POST /pet` trước, `GET /pet/{petId}` sau → mở test case Positive của `GET /pet/{petId}` → gợi ý liên kết tới `POST /pet` xuất hiện đúng → bấm Áp dụng → chỉ chọn chạy test tiêu thụ → banner "1 test case phụ trợ đã tự động chạy kèm" hiện đúng → hệ thống tự kéo theo test nguồn chạy trước. Lần chạy thật gặp đúng trường hợp Petstore demo trả `500` cho `POST /pet` — xác nhận `BLOCKED` lan truyền đúng sang `GET /pet/{petId}` (nguồn không `PASSED` nên không gọi target API với ID giả), đúng thiết kế. Phát hiện + fix 1 bug thật trong lúc E2E (xem bullet backend phía trên). **Đã bổ sung verify case `PASSED` cả 2** bằng 1 API demo tự dựng riêng (`shop-api-target`, Spring Boot + MySQL + JWT auth, resource `Product` với id backend tự sinh, xem ghi chú cuối file) — không phụ thuộc Petstore công khai: `POST /api/products` PASSED (201, id thật) → `GET /api/products/{id}` dùng đúng id đó PASSED (200). Trong lúc dựng và verify với API riêng này, phát hiện + fix thêm 2 bug thật khác (đổi dependency source bị crash 500 do thứ tự flush Hibernate; thiếu chỗ cấu hình target auth khi import bằng file) — xem 2 bullet backend phía trên. Case `DELETE`/409 vẫn chỉ có unit test, chưa tự tay click qua UI.

---

## 8. Lịch sử & Dashboard
*Phụ thuộc: Module 6, 7*

**Backend**
- [x] Entity mới `TestGenerationEvent` (1 dòng/lần gọi `generate-tests`, lưu `testCaseCount` + `snapshotJson` — snapshot danh sách test case AI sinh tại thời điểm đó) và `TestExecutionEndpoint` (bảng nối N-N `TestExecution`↔`Endpoint`, vì 1 lần chạy test có thể chọn test case của nhiều endpoint cùng lúc) — `TestCaseGenerationService.generate()`/`TestExecutionService` ghi thêm 1 dòng mỗi lần chạy, không sửa hành vi cũ
- [x] `TestHistoryService` (`service/`, đọc-tổng hợp, không phải logic AI/engine thực thi) — gộp 2 nguồn sự kiện (`TestGenerationEvent` + `TestExecution` qua `TestExecutionEndpoint`) thành 1 timeline/endpoint, sắp theo thời gian; tính `selectedCount`/`passCount`/`failCount` riêng theo từng endpoint trong 1 execution đa-endpoint từ `TestResult`, không N+1 query (gom hết theo project 1 lần rồi group trong bộ nhớ)
- [x] `GET /api/v1/projects/{projectId}/history` (`TestHistoryController`) — trả danh sách theo từng endpoint đã từng có lịch sử (endpoint chưa sinh/chưa chạy test case nào thì không xuất hiện)
- [x] *(Phát sinh khi làm — UX)* `TestResultResponse` bổ sung `endpointId` (JOIN FETCH thêm `tc.endpoint` ở `TestResultRepository.findAllByExecutionOrderByTestCaseCreatedAt`, tránh `LazyInitializationException` giống bug đã gặp ở Module 5) để trang kết quả thực thi lọc được theo đúng 1 endpoint khi vào từ Lịch sử — trước đó bấm "Xem chi tiết" ở 1 endpoint trong Lịch sử luôn hiện kết quả của TẤT CẢ endpoint chạy chung trong lần đó, gây nhầm lẫn
- [x] *(Mở rộng theo yêu cầu người dùng)* **Lịch sử tổng** gộp mọi project của user hiện tại (khác `TestHistoryService` chỉ theo 1 project) — `HistoryFeedService` (`service/`) tái dùng đúng cách gộp-trong-Java của `TestHistoryService` (không native SQL UNION, dữ liệu vẫn giới hạn theo owner nên không cần), thêm `TestGenerationEventRepository.findAllForHistoryFeed`/`TestExecutionEndpointRepository.findAllForHistoryFeed` (lọc owner/project/ngày ở JPQL) rồi gộp/sort/phân trang thủ công bằng `PageImpl`. **Bẫy đã tránh:** `TestExecutionEndpointRepository.findAllForHistoryFeed` KHÔNG nhận `endpointId` — vì `otherEndpointCount` phải tính trên toàn bộ endpoint 1 execution chạm tới, lọc endpoint sớm ở SQL sẽ luôn ra sai số 0; lọc endpoint được áp ở tầng Java sau khi đã tính xong (ngược lại, generation event lọc `endpointId` ngay ở SQL an toàn vì không có khái niệm này)
- [x] `GET /api/v1/history` (`HistoryFeedController`, top-level, không lồng `/projects/{id}`) — filter `projectId`/`endpointId`/`from`/`to`/`status` (`HistoryStatusFilter`: `ALL`/`HAS_FAIL`/`ALL_PASS`), phân trang chuẩn `PageResponse` giống `ProjectController.list()`. Filter trạng thái khác `ALL` ẩn hẳn sự kiện "Sinh test case" (không có khái niệm pass/fail) và bỏ qua luôn việc query/parse snapshot của chúng
- [x] `GET /api/v1/dashboard/summary` (`DashboardController`/`DashboardService`) — tổng số project/endpoint/test case, tổng test result đã chạy, tỷ lệ pass tổng (%, `null` khi chưa có kết quả nào để tránh chia cho 0), toàn bộ đều giới hạn theo owner hiện tại bằng count query trực tiếp ở repository (`countByOwner`/`countByProjectOwner`/`countByEndpointProjectOwner`/`countByTestCaseEndpointProjectOwner[AndStatus]`), không N+1

**Frontend**
- [x] `TestHistoryPage.tsx` (route `/projects/:id/history`, vào từ nút ở `ProjectDetailPage`) — timeline dạng dòng thời gian theo từng endpoint, 2 loại sự kiện: "Sinh test case" (xem chi tiết snapshot danh sách test case AI sinh lúc đó) và "Chạy test" (pass/fail, link sang `TestExecutionPage` đã có, ghi chú khi chạy chung nhiều endpoint)
- [x] `TestExecutionPage.tsx` đọc thêm `?endpointId=` qua `useSearchParams` (giống pattern lọc đã có ở `TestCasesPage`), lọc `results` chỉ hiện đúng test case của endpoint đó; link "Xem chi tiết" ở sự kiện Chạy test trong `TestHistoryPage` gắn kèm `endpointId` của dòng đang xem
- [x] *(Mở rộng theo yêu cầu người dùng)* `GlobalHistoryPage.tsx` (route mới `/history`, mục sidebar "Lịch sử" ngay dưới "Project") — feed phẳng mọi project, mới nhất lên đầu, filter Project/Endpoint (endpoint chỉ bật khi đã chọn project, tái dùng `listEndpoints`)/khoảng ngày (`<input type="date">`, chưa có component date-range nào trong repo)/trạng thái, phân trang Prev/Next (control phân trang đầu tiên trong app). Tách `selectClassName` từ `TestCasesPage.tsx` sang `lib/utils.ts` dùng chung cho 3 dropdown filter mới; `listProjects()` nhận thêm tham số `size` tuỳ chọn để lấy đủ project cho dropdown mà không đổi hành vi cũ ở `ProjectsPage`
- [x] `DashboardPage.tsx` — 4 thẻ KPI (project/endpoint/test case/tỷ lệ pass), biểu đồ xu hướng pass rate của N lần chạy gần nhất (SVG tự vẽ bằng tay, không thêm thư viện chart mới), feed "Hoạt động gần đây" tái dùng thẳng `getHistoryFeed` đã có ở Lịch sử tổng (không gọi API riêng) — có skeleton loading, trạng thái lỗi, trạng thái rỗng khi chưa có project nào

**Lưu ý hành vi đã có từ trước (không phải bug):** import lại OpenAPI cho 1 project sẽ xoá sạch endpoint cũ (kể cả path/method trùng) và thay bằng endpoint mới — kéo theo toàn bộ lịch sử (`TestGenerationEvent`/`TestExecution` liên quan) của endpoint cũ biến mất theo, đúng như cảnh báo sẵn có trong dialog Import ("Endpoint cũ của project sẽ được thay thế bằng danh sách mới"). Người dùng có thể nhầm là bug khi thấy lịch sử "biến mất" sau khi tự tay import lại.

**Mốc xác nhận (phần Lịch sử):** `./mvnw test` xanh (gồm `TestHistoryServiceTest` mới), `npx tsc --noEmit` sạch. **Đã verify bằng tay qua UI thật** (người dùng tự tay bấm sinh test case → chạy test → vào trang Lịch sử → xem đúng timeline theo từng endpoint, bấm "Xem chi tiết" ở sự kiện Chạy test chỉ còn hiện đúng test case của endpoint đó, không còn lẫn endpoint khác).

**Mốc xác nhận (phần Dashboard):** `./mvnw test` xanh — 79/79 test pass toàn backend, gồm `DashboardServiceTest` mới (tính đúng tỷ lệ pass từ số liệu repository, không chia cho 0 khi chưa có kết quả test nào). `npx tsc --noEmit` sạch.

---

## 9. Nâng cao Test Case AI sinh
*Phụ thuộc: Module 4, Module 6 (cần engine thực thi thật để chạy assertion/security case), Module 7 (security case dùng chung placeholder/dependency nếu cần dữ liệu thật)*

**Phạm vi:** mở rộng 3 nhóm Cơ bản (Positive/Negative/Boundary Value) đã có ở Module 4 bằng 3 nhóm Nâng cao đã ghi chú sẵn nhưng chưa làm — Security, Assertion Generation, Test Data nâng cao. Người dùng chọn nhóm muốn sinh khi bấm "Sinh Test Case" (không bắt sinh cả 6 nhóm mỗi lần, tốn quota AI).

### 9a. Security Test Cases ✅
- [x] Mở rộng `generate-test-case.st` bằng khối điều kiện ST4 `<if(includeSecurity)>...<else>...<endif>` — sinh riêng case **thiếu token** (`authOverride="NONE"`, kỳ vọng `401`), **token sai** (`authOverride="INVALID"`, kỳ vọng `401`), và **injection nhẹ** (SQL/script chèn vào field string, `authOverride="DEFAULT"`, kỳ vọng `400` chứ không phải `500`)
- [x] `TestCase` thêm field `authOverride` (enum `TestCaseAuthOverride`: `DEFAULT`/`NONE`/`INVALID`) — `RestAssuredTestRunner.resolveAuthHeader()` đọc field này để bỏ qua/thay sai/dùng thật header auth thay vì luôn tự gắn auth thật giải mã từ `Project`
- [x] `TestCaseSource` (`source`) thêm giá trị `SECURITY` — sinh Cơ bản và Security là 2 lần gọi AI + xoá-sinh-lại độc lập theo `source` (`TestCaseGenerationService.generate()` tách thành `generateGroup()` gọi 2 lần khi `includeSecurity=true`), không đụng nhau khi sinh lại
- [x] Frontend: checkbox "+ Security" cạnh nút "Sinh Test Case" ở `EndpointList`; badge riêng (`SOURCE_STYLES`/`SOURCE_LABEL`) cho case Security ở `TestCasesPage`; dropdown `authOverride` trong `TestCaseFormDialog` để tự thêm/sửa tay
- **Đã verify thật qua `shop-api-target`** (`POST /api/products`, xem chi tiết bug bên dưới): sinh đúng 4 case Security (thiếu token/token sai/SQL injection/XSS injection) với `authOverride` đúng, thực thi thật xác nhận cơ chế override auth hoạt động đúng — case thiếu/sai token nhận `403` (đúng hành vi thật của `shop-api-target`, không phải `401` như AI đoán, vì Spring Security của target trả `403` mặc định cho request chưa xác thực), case injection nhận `201` (xác nhận `shop-api-target` không validate input — đúng loại lỗ hổng mà test Security được thiết kế để phát hiện). Cả 2 lệch so với `expectedStatus` AI tự đoán là kết quả ĐÚNG mong đợi (khác API thật ≠ bug hệ thống).

### 9b. Assertion Generation ✅
- [x] Entity mới `TestCaseAssertion` (`testCaseId`, `jsonPath`, `operator`: `EQUALS`/`CONTAINS`/`EXISTS`/`TYPE`, `expectedValue`) — 2 cách song song: tự tay thêm qua `TestCaseFormDialog` (mọi test case, không tốn token) hoặc AI tự đề xuất kèm test case khi bật checkbox "+ Assertion" (`includeAssertions`, tốn thêm token)
- [x] `TestExecutionRunner` sau khi nhận response: dùng lại `JsonPath`/`extractJsonPathRaw` (mở rộng từ cơ chế Module 7) để trích giá trị RAW (không stringify trước, cần thiết cho operator `TYPE`) theo từng assertion, so khớp theo `operator` — status cuối `PASSED` chỉ khi status code khớp VÀ mọi assertion đều đúng; không chấm assertion nếu status code đã sai (`statusMatches=false`)
- [x] `TestResult.assertionResultsJson` (TEXT, JSON list `AssertionResultResponse`: jsonPath/operator/expectedValue/actualValue/passed) — null khi không có assertion nào hoặc status code đã sai. `TestResultResponse.assertionResults` parse lại từ JSON này
- [x] Frontend: `TestCaseFormDialog` khối "Assertion" (thêm/xoá dòng jsonPath + operator + expectedValue, disable expectedValue khi chọn EXISTS); `EndpointList` checkbox "+ Assertion"; `TestExecutionPage` hiện chip pass/fail cho từng assertion khi mở rộng 1 dòng kết quả
- **Đã verify thật qua `shop-api-target`** (`POST /api/products`): tự tay thêm 3 assertion (`price EQUALS`, `stock EQUALS`, `id EXISTS`) trên test case kỳ vọng 201 — chạy thật, cả 3 đều `passed:true`, status tổng `PASSED`. Đổi `expectedValue` của `price` thành giá trị sai cố ý — chạy lại, status code vẫn khớp (201) nhưng status tổng đúng `FAILED` do assertion sai, xác nhận cơ chế "PASSED chỉ khi cả status code lẫn mọi assertion đều đúng" hoạt động đúng. AI tự đề xuất assertion (`includeAssertions=true`) kỹ thuật đã nối dây đúng (parse/lưu không lỗi) nhưng trên tài khoản Groq hiện tại AI thường trả về `assertions: []` (rỗng) do ngân sách token đầu ra quá hẹp (xem bug #2 bên dưới) - chưa verify được sinh assertion thật từ AI, chỉ verify được cơ chế thủ công.

### 9c. Test Data nâng cao ✅
- [x] Mở rộng quy tắc Boundary Value trong `generate-test-case.st`: chuỗi Unicode/emoji, chuỗi vượt `maxLength` (nếu schema khai báo), số âm/số ở biên kiểu dữ liệu (`int`), phân biệt rõ field `null` lồng trong object khác với field bị bỏ hẳn
- [x] Thêm cảnh báo trong prompt: giá trị test data sinh ra không được vô tình phá vỡ cú pháp `{{tenThamSo}}` — không cần sửa `TestCasePathValidator` (đã xác nhận chỉ khớp `\w+` trong `{{}}`, không crash với ký tự đặc biệt/emoji)
- **Đã verify thật**: case Boundary "Tên chứa emoji Unicode" sinh và chạy đúng qua `shop-api-target`.

**Bug phát sinh khi làm/verify 9a + 9b + 9c (đã sửa hết, trừ ghi chú riêng ở bug #2):**
1. **StringTemplate compile lỗi khi bật Security** — prompt Security ban đầu có ví dụ literal `"<script>alert(1)</script>"`; do `StTemplateRenderer` cấu hình delimiter `<`/`>` (trùng cú pháp gốc của ST4), chuỗi HTML literal này bị hiểu nhầm thành cú pháp template, gây `STException: mismatched input '>' expecting LPAREN`. Sửa: đổi thành mô tả bằng lời thay vì chuỗi `<script>` literal trong prompt.
2. **Model Groq `llama-3.3-70b-versatile` đã bị ngừng hỗ trợ** (`404 model does not exist`) — đổi sang `openai/gpt-oss-20b` (`application.properties`), đồng thời phát hiện tài khoản Groq hiện tại bị giới hạn TPM khá thấp (8000 token/phút) khiến prompt dài (đã mở rộng nhiều ở 9a/9b/9c) dễ vượt hạn mức hoặc bị cắt cụt JSON giữa chừng — set `spring.ai.openai.chat.reasoning-effort=low` (model `gpt-oss` là reasoning model, nếu không giới hạn effort sẽ tốn hết token cho suy luận ẩn, không còn token trả lời JSON thật). `spring.ai.openai.chat.max-tokens` phải tinh chỉnh lại theo độ dài prompt hiện tại mỗi khi prompt phình thêm - chốt ở `1000` sau khi thêm khối hướng dẫn assertion (9b): `1400` (mức của 9a) đã đủ gây `413` khi bật thêm `includeAssertions`. **Rủi ro còn lại (đã xác nhận ảnh hưởng thật ở 9b):** ngân sách 1000 token đủ để 3-4 test case Cơ bản luôn thành công, nhưng thường không còn dư để AI thật sự điền field `assertions` (hay trả về rỗng `[]`) - tính năng AI tự đề xuất assertion vì vậy chưa đáng tin cậy trên tài khoản này; cân nhắc rút gọn prompt hoặc nâng cấp gói Groq nếu cần tính năng này hoạt động ổn định.
3. **`ddl-auto=update` không tự nới rộng cột MySQL `ENUM` đã tồn tại** khi enum Java có thêm hằng số mới — cột `source` (Module 4/5) vốn là `enum('AI_GENERATED','MANUAL')`, thêm `SECURITY` vào Java enum không khiến Hibernate ALTER cột, gây `SQLException: Data truncated for column 'source'` khi insert case Security. Sửa: đổi `source`/`authOverride` sang `columnDefinition = "VARCHAR(30)"`/`"VARCHAR(20)"` trong `TestCase` entity (tránh hẳn lớp lỗi này cho mọi enum mở rộng sau này), cộng với `ALTER TABLE` thủ công 1 lần cho DB cục bộ hiện có. **Lưu ý cho máy khác:** DB cũ đã có dữ liệu (tạo trước khi có field này) cũng cần chạy tay `ALTER TABLE test_cases MODIFY COLUMN source VARCHAR(30) NOT NULL, MODIFY COLUMN auth_override VARCHAR(20) NOT NULL;` — `ddl-auto=update` không tự làm việc này.
4. **Timeout 30s mặc định của Spring MVC async quá ngắn** khi bật Security (2 lần gọi AI tuần tự thay vì 1) — thêm `spring.mvc.async.request-timeout=180000` (180s).
5. **`RestAssuredTestRunner` gửi trùng header `Authorization`** — code cũ gọi `.headers(map)` rồi `.header(name, value)` riêng cho auth; REST Assured's `.header()` gọi SAU `.headers()` sẽ THÊM header trùng tên thay vì thay thế. Khi 1 case Security (`authOverride=DEFAULT`) có AI tự sinh sẵn header `Authorization` giả trong `requestHeaders` (dù đã dặn không cần), request có 2 header `Authorization` (giả + thật), khiến `shop-api-target` từ chối `403` dù giá trị auth thật vẫn đúng. Sửa: gộp toàn bộ header (kể cả auth) vào 1 `Map` case-insensitive trước khi gọi `.headers()` đúng 1 lần; đồng thời thêm dòng cấm trong prompt để AI không tự sinh header `Authorization` giả nữa.
6. **1 số endpoint LUÔN bị `413` dù đơn giản hơn endpoint khác vẫn sinh được** — nguyên nhân: `EndpointImportService` cũ nhúng NGUYÊN `Operation` OpenAPI (kể cả full schema body của MỌI response code đã document: 200/400/401/404/500...) vào `schemaJson` gửi AI. Do parser dùng `setResolveFully(true)`, mỗi response code lại được inline riêng gần như nguyên vẹn schema Product, gây trùng lặp nặng - đo thực tế 1 endpoint chỉ 2 response code mà field `responses` đã chiếm 18191/26519 ký tự (68%). Endpoint nào được document nhiều response code hơn (`PUT`/`PATCH`/`GET theo id`) thì input phình to hơn hẳn `POST`, có thể vượt hẳn 8000 TPM ngay từ input dù `max-tokens` đầu ra set bao nhiêu cũng không cứu được. Sửa: `buildSchemaJson()` chỉ giữ `summary`/`parameters`/`requestBody` (những gì AI thực sự cần để sinh dữ liệu test) + `responseStatusCodes` (chỉ danh sách mã, bỏ hẳn schema body chi tiết từng mã) - đo lại thực tế sau khi sửa: `PUT /api/products/{id}` 26519→8175 ký tự (-69%), `GET /api/products/{id}` 19950→1588 (-92%), `PATCH .../stock` 23056→4688 (-80%). Cả 3 endpoint trước đó luôn `413` giờ sinh test case thành công. **Lưu ý:** chỉ áp dụng cho endpoint import MỚI SAU khi sửa - project đã import từ trước cần import lại để có schema gọn hơn.

**Lưu ý chung đã xác nhận:** AI sinh case Security/injection hoặc assertion có thể tự đoán sai kỳ vọng (vd tưởng injection phải trả `500`, hoặc đoán `401` trong khi target trả `403`) — không phải bug hệ thống, chỉ là giới hạn suy đoán của AI khi chưa biết hành vi thật của target; nên review kỹ trước khi tin tưởng hoàn toàn.

**Module 9 (9a/9b/9c) hoàn thành.** Còn thiếu duy nhất: verify AI thật sự tự sinh được assertion (không chỉ cơ chế thủ công) — cần tài khoản Groq có TPM cao hơn, xem bug #2.

---

## 10. AI phân tích lỗi (Stretch — làm nếu còn thời gian)
*Phụ thuộc: Module 8*

**Backend**
- [ ] Prompt template `backend/src/main/resources/prompts/analyze-response.st`, giải thích nguyên nhân lỗi
- [ ] Tự động sinh Bug Report từ kết quả fail

**Frontend**
- [ ] Hiển thị phân tích lỗi + bug report trong trang kết quả

---

## 11. Hoàn thiện & Demo
*Phụ thuộc: tất cả module MVP (1-8) đã xong*

- [ ] Polish UI, fix bug toàn luồng
- [ ] Viết tài liệu kỹ thuật
- [ ] Chuẩn bị kịch bản demo
- [ ] Docker hoá (nếu kịp)

---

## Tài liệu tham chiếu

- Response format: `.claude/skills/api-contract/SKILL.md`
- Kiến trúc backend: `.claude/skills/springboot-architecture/SKILL.md`
- Git flow & commit convention: `.claude/skills/deploy-github/SKILL.md`
- **`shop-api-target`** (`A:/shop-api-target`, project riêng — KHÔNG phải 1 module của app này) — API demo Spring Boot + MySQL tự dựng để test Module 6/7 mà không phụ thuộc API công khai bên ngoài (vốn có thể flaky/không kiểm soát được, như Swagger Petstore demo). Có JWT auth (`POST /auth/login`, tài khoản `admin`/`admin123`) chặn cả `/v3/api-docs` lẫn `/api/**` — dùng để test đúng kịch bản "nhập token mới xuất được endpoint". Resource `Product` id do backend tự sinh (nguồn cho Test Data Chaining), có endpoint `PATCH /api/products/{id}/stock` chỉ nhận query parameter (không body) để test riêng tính năng query-param. Chạy port `8081` (`./mvnw spring-boot:run`), UI tối giản tại `/login.html` (lấy token) và `/products.html`. Vì `SafeUrlFetcher` chặn URL nội bộ/localhost đúng thiết kế, phải import bằng **file** (tải `/v3/api-docs` bằng `curl` kèm token rồi lưu ra file JSON) thay vì URL, sau đó cấu hình target auth riêng qua `PUT /projects/{id}/target-auth` (nút "Sửa xác thực" trong UI).

## Cách cập nhật file này

Tick `- [x]` khi xong 1 task. Đổi trạng thái module trong bảng đầu file (⬜ Chưa bắt đầu / 🟡 Đang làm / ✅ Xong). Commit: `docs: cập nhật roadmap - module X`.
