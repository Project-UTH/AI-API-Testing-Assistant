# mutant-api — API demo có lỗi cài sẵn (Module 17o)

API tuyển dụng **thu nhỏ** (mô phỏng luật của `ai-recruitment-agent`) có **27 lỗi cài sẵn (mutant)** bật/tắt lúc chạy. Dùng để đo
"bộ test do AI API Testing Assistant sinh ra bắt được bao nhiêu lỗi" — xem `scripts/eval-mutants.py` và `docs/DANH_GIA.md`.

Không có DB ngoài (dữ liệu trong bộ nhớ), không đụng Dockerfile/docker-compose của dự án. **Kết quả đo bằng bộ mutant này: `docs/DANH_GIA.md`.**

## Chạy

```bash
cd mutant-api
MUTANT_CONTROL=true ./mvnw spring-boot:run        # cổng 8083 (8082 đã dùng cho ai-recruitment-agent)
# hoặc: ./mvnw -DskipTests package && MUTANT_CONTROL=true java -jar target/mutant-api-0.0.1-SNAPSHOT.jar
```

- OpenAPI: http://localhost:8083/v3/api-docs (Swagger UI: `/swagger-ui.html`)
- `MUTANT_CONTROL` mặc định **tắt**: khi tắt, API luôn là bản chuẩn và không có endpoint điều khiển.
- Từ hệ thống chạy trong Docker, API đích là `http://host.docker.internal:8083`.

Tài khoản demo (mật khẩu chung `Passw0rd!`): `hr1@demo.local`, `hr2@demo.local` (HR, hai công ty khác nhau),
`cand1@demo.local`, `cand2@demo.local` (CANDIDATE).

## 10 endpoint

| Endpoint | Luật chính của bản chuẩn |
|---|---|
| `POST /auth/login` | 400 thiếu/rỗng, 401 sai thông tin |
| `GET /api/users/me` | không trả `passwordHash` |
| `POST /api/jobs` | chỉ HR; `title` 1–200 ký tự; `description` bắt buộc; `salaryMin/Max` ≥ 0 và `salaryMin ≤ salaryMax`; `salaryCurrency` đúng 3 ký tự; `jobType` ∈ FULL_TIME/PART_TIME/INTERN; `deadline` (yyyy-MM-dd) không ở quá khứ |
| `GET /api/jobs/{id}` | 401 không token, 404 không có |
| `PUT /api/jobs/{id}` | chỉ HR chủ sở hữu; cùng luật như tạo |
| `DELETE /api/jobs/{id}` | chỉ HR chủ sở hữu, xoá mềm, 204 |
| `PUT /api/jobs/{id}/rubric` | 1–20 tiêu chí; `weight` 0,01–100; `maxScore` ≥ 1; **tổng weight = 100** |
| `POST /api/jobs/{jobId}/applications` | chỉ CANDIDATE; `consent` phải `true`; mỗi ứng viên 1 đơn/tin (409) |
| `GET /api/applications/{id}` | chỉ ứng viên chủ đơn hoặc HR sở hữu tin |
| `PATCH /api/applications/{id}/status` | HR sở hữu tin; `PENDING→INTERVIEW_INVITED\|REJECTED`, `INTERVIEW_INVITED→HIRED\|REJECTED`, sai thì 409 |

## Điều khiển mutant (chỉ khi `MUTANT_CONTROL=true`, ẩn khỏi OpenAPI)

```bash
curl localhost:8083/__mutants                                  # danh mục + mutant đang bật
curl -XPOST localhost:8083/__mutants -H 'Content-Type: application/json' -d '{"enabled":["M01","M26"]}'
curl -XPOST localhost:8083/__reset                             # tắt hết mutant + xoá job/đơn (giữ tài khoản)
```

## Danh mục mutant

Danh mục đầy đủ (id, loại, endpoint, mô tả) nằm trong `MutantFlag.java` và trả về ở `GET /__mutants`. Mỗi mutant là **một cờ rẽ
nhánh đúng một chỗ trong code**. Cột "kỳ vọng" là **giả thuyết trước khi đo** về việc checklist tất định (Positive/Negative/Boundary/
Security) có bắt được không — không phải kết luận; kết quả đo thật ở `docs/DANH_GIA.md`.

## Công tắc quy tắc (kiểm tra đầu vào và xác thực)

Ngoài 42 lỗi viết tay (`MutantFlag`: M01–M27 là số liệu đo trong báo cáo, M28–M42 bổ sung để test tay), mỗi **quy tắc kiểm tra đầu vào / xác thực**
của API có một **công tắc** để tắt đúng kiểm tra đó (82 công tắc, `RuleCatalog.java`). Id có dạng `endpoint|trường|loại`, ví dụ
`POST /auth/login|email|required`. Loại: `required` (thiếu), `notNull`, `type` (sai kiểu), `notBlank`, `minLength`, `maxLength`, `min`,
`enum`, `format`, và `auth` (`missing` không token / `invalid` token sai).

- Danh mục dựa trên **quy tắc của API**, không dựa trên test do hệ thống sinh ra. Hệ thống sinh test theo chính các quy tắc này trong
  OpenAPI nên mỗi test "từ chối dữ liệu sai" đều có công tắc tương ứng ở đúng endpoint.
- Bật qua trang `/mutants.html` (nhóm theo endpoint) hoặc `POST /__mutants` với `{"enabled":[...],"rules":["POST /auth/login|email|required"]}`.
  Không gửi `rules` thì mọi công tắc quy tắc bị tắt (script đo chỉ gửi `enabled` nên luôn bắt đầu từ trạng thái sạch).
- **Không có công tắc** cho quy tắc không quan sát được độc lập từ bên ngoài vì bị luật khác che mất: `consent` (luôn 400 `CONSENT_REQUIRED`),
  các trường của rubric ngoài `weight`/`maxScore` tối thiểu (luật tổng weight = 100), và "rỗng/khoảng trắng" của trường enum/ngày
  (đã vi phạm luật enum/định dạng). Lỗi nghiệp vụ, phân quyền theo vai trò/chủ sở hữu, lộ dữ liệu vẫn là mutant viết tay.
- Một số công tắc trùng với mutant cũ (vd `POST /api/jobs|title|required` ≈ M01). Mutant cũ được giữ để số liệu `docs/DANH_GIA.md` truy vết được.
- Chưa nằm trong số liệu đo của `docs/DANH_GIA.md` (phải chạy lại phép đo mới có số).

## Kiểm chứng chất lượng của chính bộ mutant (`./mvnw test`)

- `MutantProbesTest` ("oracle suite"): mỗi mutant có 1 *probe* chỉ gọi API từ bên ngoài. Chứng minh (1) bản chuẩn không probe nào
  báo lỗi, (2) bật riêng một mutant thì probe của nó báo lỗi — mutant **quan sát được từ ngoài**, không phải mutant tương đương,
  (3) bật một mutant **không** làm probe của mutant khác báo lỗi — các mutant độc lập. Đây là trần lý thuyết 27/27 của phép đo.
- `RuleSwitchesTest`: với 82 công tắc — (1) bản chuẩn từ chối đúng mọi vi phạm (400, hoặc 401 cho xác thực); (2) bật một công tắc thì vi phạm của
  nó không còn bị từ chối; (3) vi phạm của công tắc khác vẫn bị từ chối (công tắc độc lập).
  (Test `MutantProbesTest` từng lộ M07 chồng lấn với M03 nên đã đổi M07 thành lỗi ngược hướng "từ chối nhầm giá trị đúng biên".)
- `SpecMatchesBehaviourTest`: OpenAPI sinh ra khớp hành vi thật của bản chuẩn (nếu spec nói `minLength 0` mà API từ chối chuỗi rỗng
  thì công cụ sinh test sẽ bị Fail giả oan — lỗi của bộ đo, không phải của công cụ).
