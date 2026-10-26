# Đáp án trích luật cho `srs-mutant-api.md`

> Đáp án dùng để đo precision/recall trích luật (17q G0.4). KHÔNG đưa file này cho hệ thống/AI trích luật.

- Nguồn luật gốc: `docs/eval/ground-truth-rules.md` (R01–R86).
- SRS nêu **72/86** luật, cố ý bỏ **14** luật.
- Cột "Câu trong SRS" trích nguyên văn (bỏ định dạng in đậm và mã BR ở đầu dòng). Một câu có thể diễn đạt nhiều luật (vd câu chung về đăng nhập, câu "sửa tin theo cùng ràng buộc như đăng tin") — khi chấm, luật được tính là đã trích nếu hệ thống sinh ra luật tương ứng cho đúng endpoint.
- Ghi chú "một phần": SRS chỉ nêu một phần nội dung luật gốc (vd không nói 201, không nói phân biệt hoa thường). Khi chấm recall vẫn tính luật này là có trong SRS.

## 1. Luật ĐÃ nêu trong SRS

| ID | Endpoint | Field | Mã SRS | Câu trong SRS (nguyên văn) |
|---|---|---|---|---|
| R03 | GET/PUT/DELETE /api/jobs/{id}, PUT /api/jobs/{id}/rubric, POST /api/jobs/{jobId}/applications | id, jobId | BR-JOB-25 | Sau khi gỡ, tin được coi như không còn tồn tại: không xem được, không sửa được, không gỡ lần nữa được, không thiết lập tiêu chí được và ứng viên cũng không nộp hồ sơ vào được — tất cả đều nhận thông báo không tìm thấy tin. |
| R04 | POST /auth/login | email | BR-AUTH-01 | Email là thông tin bắt buộc khi đăng nhập và không được để trống. |
| R06 | POST /auth/login | password | BR-AUTH-02 | Mật khẩu là thông tin bắt buộc khi đăng nhập và không được để trống. |
| R08 | POST /auth/login | email, password | BR-AUTH-03 | Nếu email không thuộc tài khoản nào hoặc mật khẩu không đúng, hệ thống từ chối đăng nhập với cùng một thông báo chung, không cho biết sai ở email hay ở mật khẩu, để tránh bị dò xem email nào đã có tài khoản (HTTP 401). |
| R11 | GET /api/users/me | Authorization | BR-AUTH-04 | Tất cả các chức năng còn lại của hệ thống đều yêu cầu người dùng đã đăng nhập; yêu cầu không kèm phiên đăng nhập, hoặc kèm phiên không hợp lệ hay đã hết hạn, đều bị từ chối (HTTP 401). |
| R12 | GET /api/users/me | response | BR-AUTH-05 | Màn hình "Tài khoản của tôi" hiển thị đúng thông tin của chính người đang đăng nhập, gồm mã người dùng, email, họ tên và vai trò. |
| R13 | GET /api/users/me | response | BR-AUTH-06 | Thông tin tài khoản trả về tuyệt đối không bao gồm mật khẩu, kể cả ở dạng đã mã hoá. |
| R15 | POST /api/jobs | Authorization | BR-AUTH-04 | (cùng câu BR-AUTH-04 ở trên) |
| R16 | POST /api/jobs | (vai trò) | BR-JOB-01 | Chỉ nhà tuyển dụng mới được đăng tin; ứng viên thực hiện thao tác này sẽ bị từ chối ngay (HTTP 403), không cần xét nội dung tin có hợp lệ hay không. |
| R17 | POST /api/jobs | title | BR-JOB-02 | Tiêu đề tin (title) là bắt buộc và phải có ít nhất một ký tự. |
| R18 | POST /api/jobs | title | BR-JOB-03 | Tiêu đề chỉ gồm toàn khoảng trắng được xem như để trống và bị từ chối. |
| R19 | POST /api/jobs | title | BR-JOB-04 | Tiêu đề dài tối đa 200 ký tự. |
| R20 | POST /api/jobs | description | BR-JOB-05 | Mô tả công việc là bắt buộc, không giới hạn độ dài. |
| R22 | POST /api/jobs | salaryMin | BR-JOB-06 | Mức lương là thông tin tuỳ chọn; nếu nhập thì lương tối thiểu và lương tối đa đều phải là số không âm (được phép bằng 0). |
| R23 | POST /api/jobs | salaryMax | BR-JOB-06 | (cùng câu BR-JOB-06 ở trên) |
| R24 | POST /api/jobs | salaryMin, salaryMax | BR-JOB-07 | Khi nhập cả lương tối thiểu và lương tối đa thì lương tối thiểu không được lớn hơn lương tối đa; hai giá trị bằng nhau vẫn hợp lệ. |
| R25 | POST /api/jobs | salaryCurrency | BR-JOB-08 | Đơn vị tiền tệ, nếu có, phải ghi theo mã gồm đúng 3 ký tự (ví dụ VND, USD). |
| R26 | POST /api/jobs | jobType | BR-JOB-09 | Loại hình công việc bắt buộc phải chọn một trong ba giá trị: toàn thời gian (FULL_TIME), bán thời gian (PART_TIME) hoặc thực tập (INTERN). (một phần: không nêu phân biệt hoa thường) |
| R27 | POST /api/jobs | deadline | BR-JOB-10 | Hạn nộp hồ sơ là bắt buộc, nhập theo định dạng năm-tháng-ngày (yyyy-MM-dd) và phải là một ngày có thật trên lịch. |
| R28 | POST /api/jobs | deadline | BR-JOB-11 | Hạn nộp hồ sơ không được là ngày trong quá khứ; chọn đúng ngày hôm nay vẫn được chấp nhận. |
| R29 | POST /api/jobs | ownerId, status | BR-JOB-12 | Tin mới luôn thuộc về HR đang đăng nhập và luôn ở trạng thái đang mở (OPEN); người dùng không thể tự chỉ định chủ tin hay trạng thái ban đầu của tin. (mã 201 nêu ở ghi chú: "Đăng tin thành công trả về HTTP 201 cùng thông tin tin vừa tạo.") |
| R30 | GET /api/jobs/{id} | Authorization | BR-AUTH-04, BR-JOB-13 | Mọi người dùng đã đăng nhập, dù là HR hay ứng viên, đều xem được chi tiết một tin tuyển dụng. |
| R31 | GET /api/jobs/{id} | id | BR-JOB-14 | Nếu tin không tồn tại, hệ thống báo không tìm thấy tin tuyển dụng. |
| R32 | PUT /api/jobs/{id} | Authorization | BR-AUTH-04 | (cùng câu BR-AUTH-04 ở trên) |
| R33 | PUT /api/jobs/{id} | id | BR-JOB-15 | Đối với các thao tác sửa tin, gỡ tin và thiết lập bộ tiêu chí, hệ thống kiểm tra tin có tồn tại trước rồi mới kiểm tra quyền; tin không tồn tại thì báo không tìm thấy (HTTP 404) bất kể người thao tác là ai. |
| R34 | PUT /api/jobs/{id} | (vai trò) | BR-JOB-16 | Ứng viên không được sửa tin tuyển dụng. |
| R35 | PUT /api/jobs/{id} | (chủ sở hữu) | BR-JOB-17 | HR chỉ được sửa tin do chính mình đăng; HR khác, dù cũng là nhà tuyển dụng, sẽ bị từ chối. |
| R36 | PUT /api/jobs/{id} | title | BR-JOB-18 | Dữ liệu khi sửa tin phải tuân theo cùng các ràng buộc như khi đăng tin mới đối với tiêu đề (bắt buộc, tối đa 200 ký tự), mô tả (bắt buộc), mức lương (số không âm), loại hình công việc (bắt buộc, một trong ba giá trị) và hạn nộp (bắt buộc, đúng định dạng ngày). |
| R38 | PUT /api/jobs/{id} | title | BR-JOB-18 | (cùng câu BR-JOB-18 — "tối đa 200 ký tự") |
| R39 | PUT /api/jobs/{id} | description | BR-JOB-18 | (cùng câu BR-JOB-18 — "mô tả (bắt buộc)") |
| R41 | PUT /api/jobs/{id} | salaryMin | BR-JOB-18 | (cùng câu BR-JOB-18 — "mức lương (số không âm)") |
| R42 | PUT /api/jobs/{id} | salaryMax | BR-JOB-18 | (cùng câu BR-JOB-18 — "mức lương (số không âm)") |
| R43 | PUT /api/jobs/{id} | salaryMin, salaryMax | BR-JOB-19 | Khi sửa tin, lương tối thiểu vẫn không được vượt quá lương tối đa. |
| R45 | PUT /api/jobs/{id} | jobType | BR-JOB-18 | (cùng câu BR-JOB-18 — "loại hình công việc (bắt buộc, một trong ba giá trị)") |
| R46 | PUT /api/jobs/{id} | deadline | BR-JOB-18 | (cùng câu BR-JOB-18 — "hạn nộp (bắt buộc, đúng định dạng ngày)") |
| R47 | PUT /api/jobs/{id} | deadline | BR-JOB-20 | Hạn nộp sau khi sửa cũng không được lùi về một ngày trong quá khứ. |
| R48 | PUT /api/jobs/{id} | ownerId, status | BR-JOB-21 | Việc sửa tin không làm thay đổi chủ tin hay trạng thái của tin, kể cả khi dữ liệu gửi lên có kèm các thông tin này. |
| R49 | DELETE /api/jobs/{id} | Authorization | BR-AUTH-04 | (cùng câu BR-AUTH-04 ở trên) |
| R50 | DELETE /api/jobs/{id} | id | BR-JOB-15 | (cùng câu BR-JOB-15 ở trên) |
| R51 | DELETE /api/jobs/{id} | (vai trò) | BR-JOB-22 | Ứng viên không có quyền gỡ tin. |
| R52 | DELETE /api/jobs/{id} | (chủ sở hữu) | BR-JOB-23 | HR không được gỡ tin do HR khác đăng. |
| R53 | DELETE /api/jobs/{id} | — | BR-JOB-24 | Gỡ tin là xoá mềm: dữ liệu tin vẫn được lưu lại để đối soát; gỡ thành công thì hệ thống trả về HTTP 204 và không kèm nội dung. |
| R54 | PUT /api/jobs/{id}/rubric | Authorization | BR-AUTH-04 | (cùng câu BR-AUTH-04 ở trên) |
| R55 | PUT /api/jobs/{id}/rubric | id | BR-JOB-15 | (cùng câu BR-JOB-15 ở trên) |
| R56 | PUT /api/jobs/{id}/rubric | (vai trò) | BR-RUB-01 | Ứng viên không được thiết lập bộ tiêu chí. |
| R57 | PUT /api/jobs/{id}/rubric | (chủ sở hữu) | BR-RUB-02 | HR chỉ thiết lập được bộ tiêu chí cho tin do chính mình đăng. |
| R58 | PUT /api/jobs/{id}/rubric | criteria | BR-RUB-03 | Bộ tiêu chí bắt buộc phải được gửi dưới dạng một danh sách. |
| R59 | PUT /api/jobs/{id}/rubric | criteria | BR-RUB-04 | Danh sách tiêu chí không được rỗng, phải có ít nhất một tiêu chí. |
| R60 | PUT /api/jobs/{id}/rubric | criteria | BR-RUB-05 | Mỗi tin có tối đa 20 tiêu chí. |
| R62 | PUT /api/jobs/{id}/rubric | criteria[].name | BR-RUB-06 | Tên tiêu chí là bắt buộc, dài từ 1 đến 200 ký tự. |
| R64 | PUT /api/jobs/{id}/rubric | criteria[].weight | BR-RUB-07 | Trọng số của mỗi tiêu chí là bắt buộc, phải lớn hơn 0 và không vượt quá 100 (nhỏ nhất là 0,01). |
| R65 | PUT /api/jobs/{id}/rubric | criteria[].maxScore | BR-RUB-08 | Điểm tối đa của tiêu chí là bắt buộc, phải là số nguyên từ 1 trở lên. |
| R66 | PUT /api/jobs/{id}/rubric | criteria[].weight | BR-RUB-09 | Tổng trọng số của tất cả tiêu chí phải bằng đúng 100, không làm tròn (ví dụ ba tiêu chí 33,33 cộng lại được 99,99 là không hợp lệ). |
| R67 | POST /api/jobs/{jobId}/applications | Authorization | BR-AUTH-04 | (cùng câu BR-AUTH-04 ở trên) |
| R68 | POST /api/jobs/{jobId}/applications | (vai trò) | BR-APP-01 | Chỉ ứng viên mới được nộp hồ sơ; HR thực hiện thao tác này sẽ bị từ chối ngay, kể cả khi tin không tồn tại, vì hệ thống kiểm tra vai trò trước khi kiểm tra tin. |
| R69 | POST /api/jobs/{jobId}/applications | jobId | BR-APP-02 | Tin được ứng tuyển phải tồn tại; nếu không, hệ thống báo không tìm thấy tin. |
| R70 | POST /api/jobs/{jobId}/applications | coverLetter | BR-APP-03 | Thư giới thiệu không bắt buộc, được phép để trống, dài tối đa 2000 ký tự. |
| R72 | POST /api/jobs/{jobId}/applications | consent | BR-APP-04 | Ứng viên phải đồng ý cho phép hệ thống phân tích hồ sơ; nếu không đồng ý, hồ sơ không được tiếp nhận (mã lỗi CONSENT_REQUIRED). |
| R73 | POST /api/jobs/{jobId}/applications | jobId + người gọi | BR-APP-05 | Mỗi ứng viên chỉ được nộp một hồ sơ cho mỗi tin tuyển dụng; nộp lần thứ hai vào cùng một tin sẽ bị từ chối (HTTP 409). |
| R74 | POST /api/jobs/{jobId}/applications | candidateId, status | BR-APP-06 | Hồ sơ mới nộp thuộc về ứng viên đang đăng nhập và có trạng thái ban đầu là "Chờ xử lý" (PENDING). (một phần: không nêu mã 201) |
| R75 | GET /api/applications/{id} | Authorization | BR-AUTH-04 | (cùng câu BR-AUTH-04 ở trên) |
| R76 | GET /api/applications/{id} | id | BR-APP-07 | Nếu hồ sơ không tồn tại, hệ thống báo không tìm thấy hồ sơ. |
| R77 | GET /api/applications/{id} | (chủ đơn) | BR-APP-08 | Ứng viên chỉ xem được hồ sơ của chính mình. |
| R78 | GET /api/applications/{id} | (chủ tin) | BR-APP-09 | HR chỉ xem được hồ sơ nộp vào tin do mình đăng, kể cả khi tin đó đã bị gỡ; HR khác sẽ bị từ chối. |
| R79 | PATCH /api/applications/{id}/status | Authorization | BR-AUTH-04 | (cùng câu BR-AUTH-04 ở trên) |
| R80 | PATCH /api/applications/{id}/status | id | BR-APP-10 | Hồ sơ cần cập nhật phải tồn tại; hệ thống kiểm tra điều này trước khi kiểm tra quyền. |
| R81 | PATCH /api/applications/{id}/status | (vai trò) | BR-APP-11 | Chỉ HR được cập nhật trạng thái hồ sơ; ứng viên, kể cả chính người nộp hồ sơ, không được tự đổi trạng thái. |
| R82 | PATCH /api/applications/{id}/status | (chủ tin) | BR-APP-12 | HR chỉ cập nhật được hồ sơ nộp vào tin do mình đăng. |
| R83 | PATCH /api/applications/{id}/status | status | BR-APP-13 | Trạng thái mới là thông tin bắt buộc khi cập nhật. |
| R84 | PATCH /api/applications/{id}/status | status | BR-APP-14 | Trạng thái mới chỉ được là Mời phỏng vấn, Trúng tuyển hoặc Từ chối; không thể đưa hồ sơ về "Chờ xử lý". |
| R85 | PATCH /api/applications/{id}/status | status | BR-APP-15 | Chỉ được chuyển từ Chờ xử lý sang Mời phỏng vấn hoặc Từ chối, và từ Mời phỏng vấn sang Trúng tuyển hoặc Từ chối; mọi bước chuyển khác, như nhảy thẳng từ Chờ xử lý sang Trúng tuyển hay cập nhật lại đúng trạng thái hiện tại, đều bị từ chối (HTTP 409). |
| R86 | PATCH /api/applications/{id}/status | status | BR-APP-16 | Trúng tuyển và Từ chối là trạng thái kết thúc; hồ sơ đã ở một trong hai trạng thái này thì không thể chuyển sang trạng thái nào khác. |

Tổng: 72 luật.

## 2. Luật CỐ Ý KHÔNG đưa vào SRS (14)

| ID | Endpoint | Field | Lý do bỏ (mô phỏng thiếu sót của tài liệu thật) |
|---|---|---|---|
| R01 | Mọi endpoint có body | body | Quy ước kỹ thuật (body phải là JSON object), BA thường không viết |
| R02 | Mọi endpoint có `{id}`/`{jobId}` | id, jobId | Kiểu tham số đường dẫn, chi tiết kỹ thuật |
| R05 | POST /auth/login | email | Không nói "chỉ khoảng trắng" ở đăng nhập |
| R07 | POST /auth/login | password | Không nói "chỉ khoảng trắng" ở đăng nhập |
| R09 | POST /auth/login | email | Không nói email không phân biệt hoa thường |
| R10 | POST /auth/login | response | Chỉ mô tả token/vai trò/thời điểm hết hạn, KHÔNG nói "chỉ gồm" và không cấm trả mật khẩu ở đăng nhập |
| R14 | GET /api/users/me | response | Không nói cấm lộ dữ liệu tài khoản khác |
| R21 | POST /api/jobs | description | Chỉ nói khoảng trắng cho tiêu đề, không cho mô tả |
| R37 | PUT /api/jobs/{id} | title | Câu "cùng ràng buộc như đăng tin" không liệt kê "chỉ khoảng trắng" |
| R40 | PUT /api/jobs/{id} | description | Như R37 |
| R44 | PUT /api/jobs/{id} | salaryCurrency | Câu "cùng ràng buộc như đăng tin" không nhắc đơn vị tiền tệ |
| R61 | PUT /api/jobs/{id}/rubric | criteria[] | Không nói mỗi phần tử phải là object |
| R63 | PUT /api/jobs/{id}/rubric | criteria[].name | Không nói tên tiêu chí chỉ khoảng trắng bị từ chối |
| R71 | POST /api/jobs/{jobId}/applications | consent | Không nói consent bắt buộc và phải kiểu boolean (chỉ nói phải đồng ý — R72) |

## 3. Ghi chú cho người chấm

- Mục 5 SRS (thứ tự kiểm tra chung, lỗi liệt kê đủ trong `details`, độ dài tính theo ký tự Unicode) là quy ước chung trong phần đầu `ground-truth-rules.md`, không có ID riêng — không tính vào precision/recall.
- Mục 8 "Vấn đề mở" #1 (chặn nộp hồ sơ khi quá hạn) là câu hỏi chưa chốt, **không phải luật**. Nếu hệ thống trích ra luật "không được nộp hồ sơ sau hạn nộp" thì tính là **false positive** (hệ thống chuẩn không có luật này).
- Ghi chú giao diện (mục 7) và yêu cầu phi chức năng (mục 6) không phải luật API; luật trích từ đó (vd "thời gian phản hồi < 1 giây", "checkbox không đánh dấu sẵn") tính là false positive, trừ khi trùng nội dung một luật đã có ở mục 1 (vd tổng trọng số = 100).
