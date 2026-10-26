# Danh sách luật nghiệp vụ / validate của API tuyển dụng (cổng 8083)

Nguồn: rút từ code bản chuẩn của `mutant-api` (src/main/java, openapi.json, README.md), ngày 2026-10-23.
Mục đích: làm đáp án luật cho phép đánh giá trích luật của module 17q.

## Quy ước chung

- Lỗi trả về dạng JSON `{ "code", "message", "details"? }`. Lỗi kiểm tra đầu vào luôn là **400 `VALIDATION_ERROR`**, `details` liệt kê **mọi** lỗi của body cùng lúc.
- Độ dài chuỗi tính theo số ký tự Unicode (code point). Các biên ghi trong bảng là biên **bao gồm** (vd `title` dài đúng 200 ký tự là hợp lệ).
- Thứ tự kiểm tra: xác thực (401) → tồn tại tài nguyên (404, nếu endpoint kiểm tra trước) → vai trò/sở hữu (403) → định dạng body (400 `VALIDATION_ERROR`) → luật nghiệp vụ (400 mã riêng / 409). Riêng `POST /api/jobs/{jobId}/applications` kiểm tra vai trò trước khi kiểm tra tin tồn tại.
- Ở tạo/sửa tin, hai luật nghiệp vụ chỉ chạy khi định dạng đã đúng; nếu vi phạm cả hai, `INVALID_DEADLINE` được trả trước `INVALID_SALARY_RANGE`.
- Tài khoản: 2 HR (`hr1`, `hr2`, khác công ty) và 2 CANDIDATE (`cand1`, `cand2`).

## Bảng luật

| ID | Endpoint | Field | Luật | Khi vi phạm | Loại | Có trong spec? |
|---|---|---|---|---|---|---|
| R01 | Mọi endpoint có body (POST/PUT/PATCH) | body | Body phải là JSON hợp lệ và là một object (không phải mảng, chuỗi, số) | 400 `INVALID_BODY` | khác | không |
| R02 | Mọi endpoint có tham số đường dẫn `{id}`/`{jobId}` | id, jobId | Tham số đường dẫn phải là số nguyên | 400 `TYPE_MISMATCH` | VALIDATION đơn field | có |
| R03 | Mọi endpoint (GET, PUT, DELETE, rubric, nộp đơn) trên tin tuyển dụng | id, jobId | Tin đã bị xoá (xoá mềm) được coi như không tồn tại: xem, sửa, xoá lần nữa, đặt rubric, nộp đơn đều trả không tìm thấy | 404 `JOB_NOT_FOUND` | trạng thái | không |
| R04 | POST /auth/login | email | Bắt buộc, kiểu chuỗi, không được rỗng | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R05 | POST /auth/login | email | Không được chỉ gồm khoảng trắng | 400 `VALIDATION_ERROR` | VALIDATION đơn field | không |
| R06 | POST /auth/login | password | Bắt buộc, kiểu chuỗi, không được rỗng | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R07 | POST /auth/login | password | Không được chỉ gồm khoảng trắng | 400 `VALIDATION_ERROR` | VALIDATION đơn field | không |
| R08 | POST /auth/login | email, password | Email phải thuộc một tài khoản và mật khẩu phải đúng; email không tồn tại và sai mật khẩu trả cùng một lỗi (không lộ email nào tồn tại) | 401 `INVALID_CREDENTIALS` | AUTH | có |
| R09 | POST /auth/login | email | Email so khớp không phân biệt hoa thường | (đăng nhập vẫn thành công) | khác | không |
| R10 | POST /auth/login | response | Phản hồi chỉ gồm `token`, `role`, `expiresAt`; không trả mật khẩu/hash mật khẩu | — | khác | không |
| R11 | GET /api/users/me | Authorization | Cần Bearer token hợp lệ (đúng chữ ký, chưa hết hạn) | 401 `UNAUTHORIZED` | AUTH | có |
| R12 | GET /api/users/me | response | Trả đúng thông tin của chính người gọi (`id`, `email`, `fullName`, `role`) | — | sở hữu dữ liệu | có |
| R13 | GET /api/users/me | response | Không trả mật khẩu/hash mật khẩu | — | khác | có |
| R14 | GET /api/users/me | response | Không trả dữ liệu của tài khoản khác (vd danh sách email người dùng) | — | khác | không |
| R15 | POST /api/jobs | Authorization | Cần Bearer token hợp lệ | 401 `UNAUTHORIZED` | AUTH | có |
| R16 | POST /api/jobs | (vai trò) | Chỉ HR được tạo tin; CANDIDATE bị từ chối kể cả khi body sai | 403 `FORBIDDEN` | AUTHZ theo vai | có |
| R17 | POST /api/jobs | title | Bắt buộc, kiểu chuỗi, ít nhất 1 ký tự | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R18 | POST /api/jobs | title | Không được chỉ gồm khoảng trắng | 400 `VALIDATION_ERROR` | VALIDATION đơn field | không |
| R19 | POST /api/jobs | title | Tối đa 200 ký tự (200 hợp lệ, 201 bị từ chối) | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R20 | POST /api/jobs | description | Bắt buộc, kiểu chuỗi, ít nhất 1 ký tự (không giới hạn độ dài tối đa) | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R21 | POST /api/jobs | description | Không được chỉ gồm khoảng trắng | 400 `VALIDATION_ERROR` | VALIDATION đơn field | không |
| R22 | POST /api/jobs | salaryMin | Tuỳ chọn; nếu có phải là số và ≥ 0 (0 hợp lệ, -1 bị từ chối) | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R23 | POST /api/jobs | salaryMax | Tuỳ chọn; nếu có phải là số và ≥ 0 | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R24 | POST /api/jobs | salaryMin, salaryMax | Khi có cả hai thì `salaryMin ≤ salaryMax` (bằng nhau hợp lệ) | 400 `INVALID_SALARY_RANGE` | phụ thuộc giữa field | có |
| R25 | POST /api/jobs | salaryCurrency | Tuỳ chọn; nếu có phải là chuỗi dài đúng 3 ký tự (2 hoặc 4 bị từ chối) | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R26 | POST /api/jobs | jobType | Bắt buộc, kiểu chuỗi, thuộc `FULL_TIME`, `PART_TIME`, `INTERN` (phân biệt hoa thường) | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R27 | POST /api/jobs | deadline | Bắt buộc, chuỗi ngày hợp lệ dạng `yyyy-MM-dd` (ngày không tồn tại như 2026-02-30 bị từ chối) | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R28 | POST /api/jobs | deadline | Không được ở quá khứ so với ngày hiện tại (hôm nay hợp lệ) | 400 `INVALID_DEADLINE` | thời gian | có |
| R29 | POST /api/jobs | ownerId, status | Chủ sở hữu tin luôn là người gọi; `ownerId`/`status` gửi trong body bị bỏ qua; tin mới có trạng thái `OPEN`; tạo thành công trả 201 | — | sở hữu dữ liệu | không |
| R30 | GET /api/jobs/{id} | Authorization | Cần Bearer token hợp lệ (mọi vai trò đã đăng nhập đều xem được) | 401 `UNAUTHORIZED` | AUTH | có |
| R31 | GET /api/jobs/{id} | id | Tin phải tồn tại | 404 `JOB_NOT_FOUND` | tham chiếu tồn tại | có |
| R32 | PUT /api/jobs/{id} | Authorization | Cần Bearer token hợp lệ | 401 `UNAUTHORIZED` | AUTH | có |
| R33 | PUT /api/jobs/{id} | id | Tin phải tồn tại (kiểm tra trước quyền) | 404 `JOB_NOT_FOUND` | tham chiếu tồn tại | có |
| R34 | PUT /api/jobs/{id} | (vai trò) | Chỉ HR được sửa tin | 403 `FORBIDDEN` | AUTHZ theo vai | có |
| R35 | PUT /api/jobs/{id} | (chủ sở hữu) | Chỉ HR tạo ra tin được sửa; HR khác bị từ chối | 403 `FORBIDDEN` | sở hữu dữ liệu | có |
| R36 | PUT /api/jobs/{id} | title | Bắt buộc, kiểu chuỗi, ít nhất 1 ký tự | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R37 | PUT /api/jobs/{id} | title | Không được chỉ gồm khoảng trắng | 400 `VALIDATION_ERROR` | VALIDATION đơn field | không |
| R38 | PUT /api/jobs/{id} | title | Tối đa 200 ký tự | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R39 | PUT /api/jobs/{id} | description | Bắt buộc, kiểu chuỗi, ít nhất 1 ký tự | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R40 | PUT /api/jobs/{id} | description | Không được chỉ gồm khoảng trắng | 400 `VALIDATION_ERROR` | VALIDATION đơn field | không |
| R41 | PUT /api/jobs/{id} | salaryMin | Tuỳ chọn; nếu có phải là số và ≥ 0 | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R42 | PUT /api/jobs/{id} | salaryMax | Tuỳ chọn; nếu có phải là số và ≥ 0 | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R43 | PUT /api/jobs/{id} | salaryMin, salaryMax | Khi có cả hai thì `salaryMin ≤ salaryMax` | 400 `INVALID_SALARY_RANGE` | phụ thuộc giữa field | có |
| R44 | PUT /api/jobs/{id} | salaryCurrency | Tuỳ chọn; nếu có phải là chuỗi dài đúng 3 ký tự | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R45 | PUT /api/jobs/{id} | jobType | Bắt buộc, thuộc `FULL_TIME`, `PART_TIME`, `INTERN` | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R46 | PUT /api/jobs/{id} | deadline | Bắt buộc, chuỗi ngày hợp lệ dạng `yyyy-MM-dd` | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R47 | PUT /api/jobs/{id} | deadline | Không được ở quá khứ (hôm nay hợp lệ) | 400 `INVALID_DEADLINE` | thời gian | có |
| R48 | PUT /api/jobs/{id} | ownerId, status | Không đổi được chủ sở hữu hay trạng thái tin qua body; các trường này bị bỏ qua | — | sở hữu dữ liệu | không |
| R49 | DELETE /api/jobs/{id} | Authorization | Cần Bearer token hợp lệ | 401 `UNAUTHORIZED` | AUTH | có |
| R50 | DELETE /api/jobs/{id} | id | Tin phải tồn tại (kiểm tra trước quyền) | 404 `JOB_NOT_FOUND` | tham chiếu tồn tại | có |
| R51 | DELETE /api/jobs/{id} | (vai trò) | Chỉ HR được xoá tin | 403 `FORBIDDEN` | AUTHZ theo vai | có |
| R52 | DELETE /api/jobs/{id} | (chủ sở hữu) | Chỉ HR tạo ra tin được xoá; HR khác bị từ chối | 403 `FORBIDDEN` | sở hữu dữ liệu | có |
| R53 | DELETE /api/jobs/{id} | — | Xoá là xoá mềm, thành công trả 204 không có body | (trả 200 hoặc có body là sai) | trạng thái | có |
| R54 | PUT /api/jobs/{id}/rubric | Authorization | Cần Bearer token hợp lệ | 401 `UNAUTHORIZED` | AUTH | có |
| R55 | PUT /api/jobs/{id}/rubric | id | Tin phải tồn tại (kiểm tra trước quyền) | 404 `JOB_NOT_FOUND` | tham chiếu tồn tại | có |
| R56 | PUT /api/jobs/{id}/rubric | (vai trò) | Chỉ HR được đặt rubric | 403 `FORBIDDEN` | AUTHZ theo vai | có |
| R57 | PUT /api/jobs/{id}/rubric | (chủ sở hữu) | Chỉ HR tạo ra tin được đặt rubric | 403 `FORBIDDEN` | sở hữu dữ liệu | có |
| R58 | PUT /api/jobs/{id}/rubric | criteria | Bắt buộc, phải là mảng | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R59 | PUT /api/jobs/{id}/rubric | criteria | Mảng phải có ít nhất 1 tiêu chí (mảng rỗng bị từ chối) | 400 `VALIDATION_ERROR` | tổng hợp mảng | không |
| R60 | PUT /api/jobs/{id}/rubric | criteria | Tối đa 20 tiêu chí (21 bị từ chối) | 400 `VALIDATION_ERROR` | tổng hợp mảng | có |
| R61 | PUT /api/jobs/{id}/rubric | criteria[] | Mỗi phần tử phải là object | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R62 | PUT /api/jobs/{id}/rubric | criteria[].name | Bắt buộc, kiểu chuỗi, 1–200 ký tự | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R63 | PUT /api/jobs/{id}/rubric | criteria[].name | Không được chỉ gồm khoảng trắng | 400 `VALIDATION_ERROR` | VALIDATION đơn field | không |
| R64 | PUT /api/jobs/{id}/rubric | criteria[].weight | Bắt buộc, là số, trong khoảng 0,01–100 (0 bị từ chối, 100,01 bị từ chối) | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R65 | PUT /api/jobs/{id}/rubric | criteria[].maxScore | Bắt buộc, là số nguyên (1.5 bị từ chối), ≥ 1 | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R66 | PUT /api/jobs/{id}/rubric | criteria[].weight | Tổng `weight` của mọi tiêu chí phải bằng đúng 100 (so sánh thập phân chính xác) | 400 `RUBRIC_WEIGHT_SUM_INVALID` | tổng hợp mảng | có |
| R67 | POST /api/jobs/{jobId}/applications | Authorization | Cần Bearer token hợp lệ | 401 `UNAUTHORIZED` | AUTH | có |
| R68 | POST /api/jobs/{jobId}/applications | (vai trò) | Chỉ CANDIDATE được nộp đơn; HR bị từ chối (kiểm tra trước cả việc tin có tồn tại) | 403 `FORBIDDEN` | AUTHZ theo vai | có |
| R69 | POST /api/jobs/{jobId}/applications | jobId | Tin phải tồn tại | 404 `JOB_NOT_FOUND` | tham chiếu tồn tại | có |
| R70 | POST /api/jobs/{jobId}/applications | coverLetter | Tuỳ chọn; nếu có phải là chuỗi, tối đa 2000 ký tự (chuỗi rỗng hợp lệ) | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R71 | POST /api/jobs/{jobId}/applications | consent | Bắt buộc, kiểu boolean | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R72 | POST /api/jobs/{jobId}/applications | consent | Phải bằng `true` (ứng viên đồng ý cho phân tích hồ sơ) | 400 `CONSENT_REQUIRED` | khác | có |
| R73 | POST /api/jobs/{jobId}/applications | jobId + người gọi | Mỗi ứng viên chỉ nộp 1 đơn cho mỗi tin | 409 `ALREADY_APPLIED` | trùng lặp | có |
| R74 | POST /api/jobs/{jobId}/applications | candidateId, status | Đơn mới thuộc về người gọi, trạng thái khởi tạo `PENDING`; thành công trả 201 | — | sở hữu dữ liệu | không |
| R75 | GET /api/applications/{id} | Authorization | Cần Bearer token hợp lệ | 401 `UNAUTHORIZED` | AUTH | có |
| R76 | GET /api/applications/{id} | id | Đơn phải tồn tại | 404 `APPLICATION_NOT_FOUND` | tham chiếu tồn tại | có |
| R77 | GET /api/applications/{id} | (chủ đơn) | Ứng viên chỉ xem được đơn của chính mình | 403 `FORBIDDEN` | sở hữu dữ liệu | có |
| R78 | GET /api/applications/{id} | (chủ tin) | HR chỉ xem được đơn nộp vào tin do mình sở hữu (kể cả tin đã xoá); HR khác bị từ chối | 403 `FORBIDDEN` | sở hữu dữ liệu | có |
| R79 | PATCH /api/applications/{id}/status | Authorization | Cần Bearer token hợp lệ | 401 `UNAUTHORIZED` | AUTH | có |
| R80 | PATCH /api/applications/{id}/status | id | Đơn phải tồn tại (kiểm tra trước quyền) | 404 `APPLICATION_NOT_FOUND` | tham chiếu tồn tại | có |
| R81 | PATCH /api/applications/{id}/status | (vai trò) | Chỉ HR được đổi trạng thái; ứng viên (kể cả chủ đơn) bị từ chối | 403 `FORBIDDEN` | AUTHZ theo vai | có |
| R82 | PATCH /api/applications/{id}/status | (chủ tin) | HR phải sở hữu tin mà đơn nộp vào | 403 `FORBIDDEN` | sở hữu dữ liệu | có |
| R83 | PATCH /api/applications/{id}/status | status | Bắt buộc, kiểu chuỗi | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R84 | PATCH /api/applications/{id}/status | status | Phải thuộc `INTERVIEW_INVITED`, `HIRED`, `REJECTED` (`PENDING` hay giá trị khác bị từ chối) | 400 `VALIDATION_ERROR` | VALIDATION đơn field | có |
| R85 | PATCH /api/applications/{id}/status | status | Chỉ cho chuyển `PENDING → INTERVIEW_INVITED \| REJECTED` và `INTERVIEW_INVITED → HIRED \| REJECTED`; chuyển khác (vd `PENDING → HIRED`, chuyển về cùng trạng thái) bị từ chối | 409 `INVALID_STATE_TRANSITION` | trạng thái | có |
| R86 | PATCH /api/applications/{id}/status | status | `HIRED` và `REJECTED` là trạng thái cuối, không chuyển tiếp được nữa | 409 `INVALID_STATE_TRANSITION` | trạng thái | có |
