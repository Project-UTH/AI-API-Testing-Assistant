# Đánh giá độ chính xác bằng lỗi cài sẵn (Module 17o)

*Đo ngày 2026-10-21. Mọi con số dưới đây được sinh từ file kết quả JSON trong `docs/eval/` bằng `scripts/eval/make-report-tables.py`,
chạy lại được (xem mục 8). Nếu một con số ở đây khác file JSON thì file JSON là đúng.*

## 1. Câu hỏi và trả lời ngắn

**Câu hỏi:** bộ test mà hệ thống sinh ra bắt được bao nhiêu lỗi có chủ đích trong một API, bao nhiêu test Fail oan, và tốn bao nhiêu?

Trên `mutant-api` (API tuyển dụng thu nhỏ, 10 endpoint, 27 lỗi cài sẵn):

| Cấu hình | Phát hiện / 27 | Fail giả trên bản chuẩn | Token AI mỗi lượt |
|---|---|---|---|
| **A** - chỉ checklist tất định (không AI) | **14** (52%), giống hệt giữa 2 lượt | 4,3% | 0 |
| **B** - hybrid (checklist + AI nghiệp vụ) | **17** (63%), 17/17/17 giữa 3 lượt | 23,2% | ≈ 92.900 |
| **C** - chỉ phần AI nghiệp vụ (xấp xỉ) | 7,3 ± 0,5 (27%) | 48,8% | (lấy từ B) |
| Schemathesis (công cụ có sẵn, không AI) | 4 (15%) | không đo | 0 |

Ba điều rút ra, kèm giới hạn ở mục 7:

1. **Phần tất định (checklist EP/BVA + ma trận phân quyền + dò mass-assignment) làm được hầu hết việc**: bắt toàn bộ 5 lỗi bỏ validate, 3/5 lỗi sai biên, 3/3 lỗi sai mã trạng thái, lỗi phân quyền theo vai, 1/2 mass-assignment. Rẻ (0 token, 2 giây), tất định, hơn rõ rệt công cụ fuzz không có ngữ cảnh.
2. **AI nghiệp vụ chỉ bù đúng chỗ checklist không với tới: luật nghiệp vụ.** Thêm đúng 3 mutant (lương min > max, hạn nộp quá khứ, tổng trọng số ≠ 100), tức 63% so với 52%. Giá: khoảng **31.000 token cho mỗi mutant bắt thêm** (92.900 token ÷ 3), và Fail giả tăng từ 4% lên 23%.
3. **Cả hai cùng bỏ lọt 9/27 lỗi** (quyền sở hữu dữ liệu, một số sai biên trong body lồng nhau, máy trạng thái đơn, nộp đơn trùng, lộ thông tin trong thông báo lỗi). Đây là giới hạn thật của cách làm, không giấu.

Điểm yếu thứ tư ảnh hưởng tới người dùng: **hệ thống thường phát hiện được lỗi nhưng chưa dám kết luận "lỗi của API"**. Trong 17 mutant bị phát hiện chỉ 3–4 có nhãn nguyên nhân đủ chắc; phần còn lại là `UNDETERMINED`/`FLAKY` (người dùng phải tự xét).

## 2. Phương pháp

**API đích `mutant-api`** (`mutant-api/`, xem `mutant-api/README.md`): mô phỏng luật của `ai-recruitment-agent` (tin tuyển dụng có lương min/max và
hạn nộp, rubric tổng trọng số 100%, vòng đời đơn, hai vai HR/Candidate). 10 endpoint, 27 mutant, mỗi mutant là **một cờ rẽ nhánh đúng một chỗ trong code**,
bật/tắt lúc chạy qua endpoint ẩn khỏi OpenAPI. Bảy loại lỗi: bỏ validate, sai biên, sai mã trạng thái, thiếu phân quyền, mass-assignment, lộ dữ liệu, vi phạm luật nghiệp vụ.

**Kiểm chứng chất lượng bộ mutant** (`MutantProbesTest`, "oracle suite"): mỗi mutant có một probe chỉ gọi API từ ngoài. Chứng minh (1) bản chuẩn không probe nào báo lỗi,
(2) bật riêng một mutant thì probe của nó báo lỗi, tức **cả 27 mutant đều quan sát được từ ngoài** (trần lý thuyết 27/27, không có mutant tương đương), (3) bật một mutant không làm
probe khác báo lỗi (độc lập). Bước này từng lộ một cặp mutant chồng lấn (M03/M07) và dẫn tới đổi M07 thành lỗi ngược hướng.
`SpecMatchesBehaviourTest` đảm bảo OpenAPI khớp hành vi thật (xem mục 6).

**Quy trình đo** (`scripts/eval-mutants.py`, gọi hệ thống chỉ qua REST API như người dùng, không đụng mã của hệ thống): mỗi lượt tạo project mới, import OpenAPI **bản chuẩn**, sinh test,
chạy trên bản chuẩn, rồi với từng mutant: bật mutant, chạy lại đúng các test đã Pass, so sánh. Định nghĩa:

- **Phát hiện** = có test Pass trên bản chuẩn rồi `FAILED` khi bật mutant, không do môi trường/token/tài nguyên không tồn tại (`failureCause.notApiFault`), và không phải test nhiễu (xem bên dưới).
  Trong thí nghiệm có kiểm soát chỉ lật đúng một mutant nên Pass→Fail chắc chắn do mutant.
- **Gán đúng lỗi API** = trong số đó, nhãn nguyên nhân của hệ thống không thuộc nhóm mơ hồ (`UNDETERMINED`, `FLAKY`, `EXPECTATION_MISMATCH`, `STATE_CONFLICT`, `TEST_DATA_SUSPECT`, `TOKEN_FORBIDDEN`).
  Đây là chỉ số "người dùng có được chỉ thẳng vào lỗi API không", tách riêng với "có bắt được không".
- **Chỉ có cảnh báo bảo mật**: test vẫn Pass nhưng hệ thống báo `securityFindings` mới (vd lộ `passwordHash`, mức HIGH). Không tính là phát hiện, ghi riêng.
- **Test nhiễu**: chạy lại đúng tập test dùng cho mutant nhưng **không bật mutant nào** (2 lần); test nào tự Fail ở đó bị loại khỏi bằng chứng.
- **Fail giả** = tỉ lệ test Fail trên **bản chuẩn** (API đúng nhưng test báo lỗi).

**Ba cấu hình:**

- **A**: 10 test gốc do **người viết** (`scripts/eval/baselines.json`, 1 test đúng mỗi endpoint) + job `SYSTEM` (0 token) + ma trận phân quyền do người dùng khai theo đặc tả đúng + phạm vi OWASP + nút "Bù coverage".
- **B**: như A (cùng phần không AI, để chênh lệch A↔B chỉ là đóng góp của AI) cộng job `FULL` có bật "AI bổ sung nghiệp vụ". Test gốc vẫn do người viết vì AI **không tự làm được** test gốc cho `login` và endpoint của ứng viên
  (`NEEDS_HUMAN`: thiếu thông tin đăng nhập/vai, đo ở lần thử đầu). Vì vậy toàn bộ token của B là của bước AI nghiệp vụ. Chạy **3 lượt độc lập** (project mới, AI sinh test mới mỗi lượt).
- **C**: lấy từ chính lần sinh của B, chỉ tính test `AI_BUSINESS` (kèm test gốc làm bối cảnh nhưng không tính bằng chứng). Hệ thống không có đường gọi riêng "chỉ AI nghiệp vụ" nên C là **xấp xỉ**.

Môi trường: model `claude-sonnet-5`, 11 file prompt (hash `92e2dbe9a6970e46`), ngày 2026-10-21, hệ thống chạy bằng Docker từ nhánh `main` tại `7cd347a`. Không sửa mã backend/frontend của hệ thống.

## 3. Kết quả

*(Các bảng dưới đây do `scripts/eval/make-report-tables.py` sinh trực tiếp từ `results-A-run12.json` và `results-B-merged.json`.)*

### Tổng quan theo cấu hình

| Cấu hình | Số lượt | Mutant phát hiện (/27) | Gán đúng lỗi API (/27) | Chỉ có cảnh báo bảo mật | Fail giả trên bản chuẩn | Token sinh test / lượt | Thời gian sinh / lượt |
|---|---|---|---|---|---|---|---|
| A - checklist (0 token) | 2 | 14.0 ± 0.0 | 4.0 ± 0.0 | 1.0 ± 0.0 | 4.3 ± 0.0% | 0 ± 0 | 2 ± 0s |
| B - hybrid (AI sinh + nghiệp vụ) | 3 | 17.0 ± 0.0 | 3.3 ± 0.5 | 1.0 ± 0.0 | 23.2 ± 1.2% | 92917 ± 4430 | 211 ± 5s |
| C - chỉ AI nghiệp vụ (xấp xỉ) | 3 | 7.3 ± 0.5 | 1.0 ± 0.0 | 0.3 ± 0.5 | 48.8 ± 4.7% | 92917 ± 4430 | 211 ± 5s |

*phát hiện = test Pass->Fail khi bật mutant (không do môi trường/nhiễu); gán đúng = nhãn nguyên nhân của hệ thống chắc là lỗi API. Nhiều lượt: trung bình ± độ lệch chuẩn tổng thể (pstdev).*

### Theo loại lỗi - A

| Loại | Số mutant | Phát hiện (mỗi lượt) | Gán đúng lỗi API (mỗi lượt) |
|---|---|---|---|
| AUTHORIZATION | 5 | 2 / 2 | 1 / 1 |
| BOUNDARY | 5 | 3 / 3 | 0 / 0 |
| BUSINESS_RULE | 5 | 0 / 0 | 0 / 0 |
| DATA_EXPOSURE | 2 | 0 / 0 | 0 / 0 |
| MASS_ASSIGNMENT | 2 | 1 / 1 | 1 / 1 |
| STATUS_CODE | 3 | 3 / 3 | 2 / 2 |
| VALIDATION | 5 | 5 / 5 | 0 / 0 |
| TỔNG | 27 | 14 / 14 | 4 / 4 |

### Theo loại lỗi - B

| Loại | Số mutant | Phát hiện (mỗi lượt) | Gán đúng lỗi API (mỗi lượt) |
|---|---|---|---|
| AUTHORIZATION | 5 | 2 / 2 / 2 | 1 / 1 / 1 |
| BOUNDARY | 5 | 3 / 3 / 3 | 0 / 0 / 0 |
| BUSINESS_RULE | 5 | 3 / 3 / 3 | 0 / 0 / 0 |
| DATA_EXPOSURE | 2 | 0 / 0 / 0 | 0 / 0 / 0 |
| MASS_ASSIGNMENT | 2 | 1 / 1 / 1 | 1 / 1 / 1 |
| STATUS_CODE | 3 | 3 / 3 / 3 | 1 / 1 / 2 |
| VALIDATION | 5 | 5 / 5 / 5 | 0 / 0 / 0 |
| TỔNG | 27 | 17 / 17 / 17 | 3 / 3 / 4 |

### Theo loại lỗi - C

| Loại | Số mutant | Phát hiện (mỗi lượt) | Gán đúng lỗi API (mỗi lượt) |
|---|---|---|---|
| AUTHORIZATION | 5 | 0 / 0 / 0 | 0 / 0 / 0 |
| BOUNDARY | 5 | 0 / 0 / 0 | 0 / 0 / 0 |
| BUSINESS_RULE | 5 | 3 / 3 / 3 | 0 / 0 / 0 |
| DATA_EXPOSURE | 2 | 0 / 0 / 0 | 0 / 0 / 0 |
| MASS_ASSIGNMENT | 2 | 0 / 0 / 0 | 0 / 0 / 0 |
| STATUS_CODE | 3 | 3 / 3 / 3 | 1 / 1 / 1 |
| VALIDATION | 5 | 1 / 1 / 2 | 0 / 0 / 0 |
| TỔNG | 27 | 7 / 7 / 8 | 1 / 1 / 1 |

### Từng mutant

| Mutant | Loại | Endpoint | Mô tả | Giả thuyết trước đo (checklist bắt?) | A - checklist (0 token) | B - hybrid (AI sinh + nghiệp vụ) | C - chỉ AI nghiệp vụ (xấp xỉ) |
|---|---|---|---|---|---|---|---|
| M01 | Bo validate | `POST /api/jobs` | Thieu title van tao duoc job (201) | có | 2/2 | 3/3 | 0/3 |
| M02 | Bo validate | `POST /api/jobs` | title chi gom khoang trang / rong van duoc nhan | có | 2/2 | 3/3 | 3/3 |
| M03 | Bo validate | `POST /api/jobs` | title dai bat ky (khong gioi han toi da 200) van duoc nhan | có | 2/2 | 3/3 | 0/3 |
| M04 | Bo validate | `POST /api/jobs` | salaryCurrency dai hon 3 ky tu van duoc nhan (bo gioi han toi da) | có | 2/2 | 3/3 | 0/3 |
| M05 | Bo validate | `POST /api/jobs` | jobType ngoai danh sach enum bi bo qua, mac dinh FULL_TIME (khong tra 400) | có | 2/2 | 3/3 | 1/3 |
| M06 | Sai bien | `POST /api/jobs` | salaryMin = -1 van duoc nhan (bien duoi 0 thanh -1) | có | 2/2 | 3/3 | 0/3 |
| M07 | Sai bien | `POST /api/jobs` | title dai dung 200 ky tu (bien tren hop le) bi tu choi nham (gioi han toi da 200 thanh 199) | có | 2/2 | 3/3 | 0/3 |
| M08 | Sai bien | `PUT /api/jobs/{id}/rubric` | weight = 0 van duoc nhan (bien duoi 0.01 thanh 0; tong van phai 100 nen quan sat duoc) | không | 0/2 | 0/3 | 0/3 |
| M09 | Sai bien | `PUT /api/jobs/{id}/rubric` | maxScore = 0 van duoc nhan (bien duoi 1 thanh 0) | không | 0/2 | 0/3 | 0/3 |
| M10 | Sai bien | `POST /api/jobs` | salaryCurrency dai 2 ky tu van duoc nhan (bien duoi 3 thanh 2) | có | 2/2 | 3/3 | 0/3 |
| M11 | Sai ma trang thai | `POST /api/jobs` | Loi validate tra 200 kem than loi thay vi 400 | có | 2/2 (gán đúng 2) | 3/3 (gán đúng 1) | 3/3 |
| M12 | Sai ma trang thai | `GET /api/jobs/{id}` | Job khong ton tai tra 500 thay vi 404 | không | 2/2 | 3/3 | 3/3 |
| M13 | Sai ma trang thai | `PUT /api/jobs/{id}/rubric` | Du lieu sai tra 401 thay vi 400 (nhan dien nham la loi xac thuc) | có | 2/2 (gán đúng 2) | 3/3 (gán đúng 3) | 3/3 (gán đúng 3) |
| M14 | Thieu phan quyen | `POST /api/jobs` | CANDIDATE tao duoc job (thieu kiem tra vai tro HR) | không | 2/2 (gán đúng 2) | 3/3 (gán đúng 3) | 0/3 |
| M15 | Thieu phan quyen | `PUT /api/jobs/{id}` | HR khac sua duoc job cua HR khac (thieu kiem tra chu so huu) | không | 0/2 | 0/3 | 0/3 |
| M16 | Thieu phan quyen | `GET /api/applications/{id}` | CANDIDATE xem duoc don ung tuyen cua nguoi khac | không | 0/2 | 0/3 | 0/3 |
| M17 | Thieu phan quyen | `PATCH /api/applications/{id}/status` | HR khac doi duoc trang thai don cua job khong thuoc minh | không | 0/2 | 0/3 | 0/3 |
| M18 | Thieu phan quyen | `GET /api/jobs/{id}` | Khong can token van doc duoc job (bo kiem tra xac thuc) | có | 2/2 | 3/3 | 0/3 |
| M19 | Mass-assignment | `POST /api/jobs` | Nhan ownerId tu body (gan chu so huu tuy y) | có | 0/2 | 0/3 | 0/3 |
| M20 | Mass-assignment | `PUT /api/jobs/{id}` | Nhan status tu body (tu dong/mo job qua truong khong khai bao) | có | 2/2 (gán đúng 2) | 3/3 (gán đúng 3) | 0/3 |
| M21 | Lo du lieu nhay cam | `GET /api/users/me` | Tra them passwordHash trong response | có | 0/2 (cảnh báo 2) | 0/3 (cảnh báo 3) | 0/3 (cảnh báo 1) |
| M22 | Lo du lieu nhay cam | `POST /api/jobs` | Response loi 400 kem thong tin noi bo (ten class, SQL) | có | 0/2 | 0/3 | 0/3 |
| M23 | Vi pham luat nghiep vu | `POST /api/jobs` | salaryMin > salaryMax van duoc nhan | không | 0/2 | 3/3 | 3/3 |
| M24 | Vi pham luat nghiep vu | `POST /api/jobs` | deadline o qua khu van duoc nhan | không | 0/2 | 3/3 | 3/3 |
| M25 | Vi pham luat nghiep vu | `PUT /api/jobs/{id}/rubric` | Tong weight khac 100 van duoc nhan | không | 0/2 | 3/3 | 3/3 |
| M26 | Vi pham luat nghiep vu | `PATCH /api/applications/{id}/status` | Nhay PENDING -> HIRED (bo qua INTERVIEW_INVITED) van duoc nhan | không | 0/2 | 0/3 | 0/3 |
| M27 | Vi pham luat nghiep vu | `POST /api/jobs/{id}/applications` | Nop don trung (cung ung vien, cung job) van duoc nhan | không | 0/2 | 0/3 | 0/3 |

Giả thuyết trước đo (A - checklist (0 token)): dự đoán checklist bắt 15 mutant; đúng 13/15; dự đoán bắt nhưng bỏ lọt: M19, M22; dự đoán không bắt nhưng vẫn bắt được: M12, M14.

Nguyên nhân Fail giả trên bản chuẩn - A - checklist (0 token) (cộng dồn 2 lượt): STATE_CONFLICT 6, UNDETERMINED 4, TEST_DATA_SUSPECT 4

Nguyên nhân Fail giả trên bản chuẩn - B - hybrid (AI sinh + nghiệp vụ) (cộng dồn 3 lượt): UNDETERMINED 47, BLOCKED 45, STABLE_FAILURE 18, AUTHZ_VIOLATION 10, RESOURCE_NOT_FOUND 9, ENVIRONMENT 8, TEST_DATA_SUSPECT 6, STATE_CONFLICT 6, DATA_STATE 4, API_WRONG_STATUS 3, FLAKY 2

Nguyên nhân Fail giả trên bản chuẩn - C - chỉ AI nghiệp vụ (xấp xỉ) (cộng dồn 3 lượt): UNDETERMINED 39, RESOURCE_NOT_FOUND 19, STABLE_FAILURE 9, ENVIRONMENT 8, AUTHZ_VIOLATION 7, STATE_CONFLICT 5, API_WRONG_STATUS 3, FLAKY 3

Môi trường đo: model `claude-sonnet-5`, 11 file prompt (hash 92e2dbe9a6970e46), commit 7cd347a111 (có thay đổi chưa commit), ngày 2026-10-21.

### So sánh với Schemathesis 4.32.2 (`docs/eval/schemathesis-2026-10-21.json`)

Cùng `mutant-api`, cùng 27 mutant, `--max-examples 25 --seed 1`, 1 token HR. Schemathesis tự báo vài lỗi ngay trên bản chuẩn (15 cặp (thao tác, mã lỗi) trên bản chuẩn, chủ yếu phản hồi không khớp spec) nên mutant chỉ tính là
phát hiện khi có lỗi **mới** (thao tác, mã) so với baseline gồm 2 lượt bản chuẩn (seed 1 và 2, đều 15 lỗi).

| Loại | Schemathesis | Hệ thống - A | Hệ thống - B |
|---|---|---|---|
| Bỏ validate (5) | 0 | 5 | 5 |
| Sai biên (5) | 0 | 3 | 3 |
| Sai mã trạng thái (3) | 2 | 3 | 3 |
| Thiếu phân quyền (5) | 1 | 2 | 2 |
| Mass-assignment (2) | 0 | 1 | 1 |
| Lộ dữ liệu (2) | 0 | 0 (+1 cảnh báo) | 0 (+1 cảnh báo) |
| Luật nghiệp vụ (5) | 1 | 0 | 3 |
| **Tổng** | **4** | **14** | **17** |

Không công bằng tuyệt đối: Schemathesis không có test gốc do người viết, không có khái niệm vai, không dùng AI, chưa tinh chỉnh (không khai báo liên kết giữa các thao tác, 1 token). Đây là so sánh với **cấu hình mặc định**,
không phải kết luận "hệ thống tốt hơn Schemathesis nói chung". Phát hiện của Schemathesis ở M24 là lỗi schema phản hồi xuất hiện gián tiếp, không chắc do chủ đích.

## 4. Phân tích

**Giả thuyết đặt trước khi đo** (cột "Giả thuyết" trong `MutantFlag.java`): checklist bắt 15 mutant. Đúng 13/15. Sai theo hai hướng: dự đoán bắt nhưng bỏ lọt **M19** (mass-assignment `ownerId`: probe của hệ
thống không trúng field này, chưa phân tích sâu lý do) và **M22** (lộ thông tin nội bộ trong lỗi 400: hệ thống không báo gì ở trường hợp này); dự đoán không bắt nhưng vẫn bắt được **M12** (id lớn nhất int64 → 500) và **M14**
(ứng viên tạo được tin, nhờ ma trận phân quyền). Hệ thống tốt hơn dự đoán ở hai chỗ và kém hơn ở hai chỗ.

**Bị bỏ lọt ở cả A và B (9 mutant):**

| Mutant | Vì sao |
|---|---|
| M08, M09 (sai biên trong rubric) | giá trị nằm trong mảng lồng nhau; planner chỉ đọc field cấp 1 (đã ghi ở roadmap P1-3) |
| M15, M16, M17 (quyền sở hữu: HR khác/ứng viên khác) | hệ thống chỉ có khái niệm vai (HR/ứng viên), chưa có "dữ liệu của ai" |
| M19, M22 | xem trên |
| M26 (nhảy trạng thái đơn), M27 (nộp đơn trùng) | cả checklist lẫn AI nghiệp vụ đều không bắt được; chưa phân tích sâu nguyên nhân (đoán: cần dựng đúng chuỗi trạng thái/đơn trước đó) |

M21 (lộ `passwordHash`) được hệ thống **cảnh báo bảo mật mức HIGH** ở mọi lượt nhưng test vẫn Pass nên không tính là "phát hiện"; một người dùng đọc cảnh báo sẽ thấy.

**AI nghiệp vụ**: trung bình 62–67 test mỗi lượt, tốn 88–99 nghìn token (≈ 9.000 token/endpoint). Mỗi lượt đều bắt đúng M23, M24, M25, không lượt nào bắt thêm. Chi phí/hiệu quả: **không rẻ** cho 3 mutant, nhưng đây là loại lỗi
checklist không thể sinh ra từ spec. Phần AI nghiệp vụ cũng là nguồn Fail giả chính (23% so với 4%): nhiều test phụ thuộc lẫn nhau (45 kết quả `BLOCKED` cộng dồn 3 lượt) hoặc dùng id không tồn tại (`RESOURCE_NOT_FOUND`).

**Cấu hình C** cho thấy test AI nghiệp vụ đứng một mình **không đủ**: chỉ phát hiện 7–8/27 và Fail giả 49%, vì chúng cần test gốc và checklist làm nền. Luận điểm "AI hỗ trợ, hệ thống tất định đảm bảo chất lượng" có bằng chứng ở đây.

**Nhãn nguyên nhân**: phần lớn phát hiện mang nhãn `UNDETERMINED` hoặc `FLAKY`. `UNDETERMINED` là hệ quả có chủ đích của nguyên tắc black-box (không có request đối chứng nên không dám khẳng định). `FLAKY` ở một số mutant là **hiện tượng do cách đo**: cùng một test
chạy xen kẽ giữa bản chuẩn và bản có mutant nên bộ phân tích độ ổn định thấy nó đổi Pass↔Fail; không phải khuyết điểm của test.

## 5. Chi phí thật của đợt đo

| Hạng mục | Token | Ghi chú |
|---|---|---|
| Cấu hình B, 3 lượt | 278.751 | 88.272 + 91.599 + 98.880 (dự toán ban đầu ≈ 165.000, thấp hơn thật ≈ 41%) |
| Lần thử ban đầu | 64.593 | **đốt nhầm**: Git Bash trên Windows tự đổi `/auth/login` thành đường dẫn Windows nên `--skip-paths` không khớp và job chạy cả 10 endpoint thay vì 1; job dừng ở 4/10 endpoint vì chạm trần 60.000 (trần mềm, đã vượt ~8%) |
| Cấu hình A, mọi lượt | 0 | |
| **Tổng** | **≈ 343.000** | |

Đã sửa: script từ chối chạy nếu `--skip-paths` không khớp path nào trong spec và luôn in danh sách endpoint sẽ chạy trước khi tiêu token. Thời gian sinh test của AI ≈ 3,5 phút mỗi lượt; chạy thử 27 mutant ≈ 6–8 phút mỗi lượt.

## 6. Sai sót của chính phép đo, được phát hiện và sửa trong lúc làm

Ghi lại vì đây là bằng chứng phép đo đã được kiểm tra chứ không chỉ chạy ra một con số:

1. **Spec của API demo ghi sai** (`@Size(max=200)` sinh `minLength: 0` trong khi API từ chối chuỗi rỗng): công cụ sinh test "chuỗi rỗng hợp lệ" rồi Fail oan. Fail giả giảm từ 18 xuống 5 (13,3% → 3,5%) sau khi sửa spec cho khớp hành vi. Từ đó có `SpecMatchesBehaviourTest`.
2. **Nhiễu thổi phồng số bắt được**: bản đo đầu cho mọi mutant (kể cả M08, vốn chỉ ảnh hưởng rubric) cùng một con số "3 bắt, 2 nghi ngờ". Nguyên nhân: tập test dùng cho mutant là tập con nên một số test mất trạng thái do test khác tạo. Đã thêm lượt đối chứng không mutant (mục 2) và dừng giữa chừng một lượt AI ~88.000 token để không thu thập dữ liệu hỏng (lượt đó được **dùng lại**, không sinh lại).
3. **Cấu hình C ban đầu thiếu bối cảnh** (chạy test nghiệp vụ không kèm test gốc nên 76% Fail giả vì thiếu tin/đơn để thao tác); đã chạy kèm test gốc nhưng chỉ tính bằng chứng của test nghiệp vụ.
4. **Lỗi thật của hệ thống do phép đo phát hiện** (không sửa vì ngoài phạm vi, đã ghi vào roadmap): test checklist do hệ thống bù **không thừa kế `authProfileId`** của test gốc (`CoverageGapFillService.toEntity`; chỉ nhánh mass-assignment có copy), nên mọi test
   dẫn xuất của endpoint cần vai khác chạy bằng token mặc định và ra 403 oan (9 trong 18 Fail giả ban đầu). Script mô phỏng bước "gán profile thủ công" mà roadmap hiện vẫn yêu cầu người dùng làm (tắt bằng `--no-assign-profiles`).

## 7. Giới hạn và đe doạ tới tính đúng đắn (threats to validity)

- **Mutant do chính nhóm tự gieo, API do nhóm tự viết và nhỏ** (10 endpoint). Phân bố lỗi có thể không giống lỗi thật ngoài đời; tác giả vừa viết checklist vừa viết mutant nên có nguy cơ thiên lệch. Giảm nhẹ bằng giả thuyết đặt trước (13/15) và oracle suite, không loại bỏ được.
- **Cỡ mẫu nhỏ**: 27 mutant, 3 lượt AI. Độ lệch chuẩn tính theo tổng thể (`pstdev`), chỉ để mô tả, không phải kiểm định thống kê. "17/17/17" cho thấy ổn định trên 3 lượt này, chưa chứng minh ổn định nói chung.
- **Phụ thuộc phiên bản model và prompt** (`claude-sonnet-5`, hash prompt ở trên); đổi model hoặc prompt phải đo lại.
- **Có đầu vào của người**: test gốc và ma trận phân quyền do chính nhóm viết theo đặc tả đúng. Cấu hình "hoàn toàn tự động" không đo được vì AI không tự làm được test gốc cho `login`/endpoint ứng viên (chỉ có số đo một phần ở lần thử đầu: ≈ 10.800 token/endpoint khi làm được).
- **Cấu hình C là xấp xỉ** (lấy từ sinh của B, có bối cảnh test gốc), không phải một đường chạy độc lập.
- **Không mù (not blind)**: người chạy biết danh sách mutant. **Không có nhóm đối chứng người** (tự viết test tay cùng API).
- **Mô phỏng gán profile thủ công** che một lỗi của hệ thống (mục 6.4); số đo "hiện trạng" khi không gán sẽ thấp hơn cho endpoint ứng viên.
- **Schemathesis chỉ chạy cấu hình mặc định**, không tinh chỉnh; so sánh mang tính tham khảo.
- **Chưa đo trên API thật** (`ai-recruitment-agent`, 110 endpoint) để lấy tỉ lệ Fail giả ngoài đời, và chưa đo độ bao phủ theo từng tập con spec lớn. Đã có số thực địa từ trước (roadmap 17h/17n), không phải đo của đợt này.
- **E2** (thời gian người duyệt test AI) và **E4** (quay demo) **chưa làm**, cần người thật: mẫu đo ở `docs/eval/E2-do-thoi-gian-duyet.md`.

## 7A. Có tổng quát hoá sang hệ thống khác không? Đo trên hệ thống thứ hai (2026-10-25)

**Câu hỏi:** checklist tất định (EP/BVA, Security, probe OWASP, 0 token) có cho kết quả chuẩn trên các hệ thống khác nhau không, hay chỉ tốt trên API tuyển dụng nhóm tự viết?

**Cách đo.** Cùng khung đo (`scripts/eval-mutants.py`, cấu hình A, 2 lượt) trên 2 hệ thống: (1) `mutant-api` tuyển dụng (10 endpoint, 42 lỗi cài sẵn M01-M42, bộ phát triển); (2) `mutant-shop` (bản sao của `shop-api-target`: Spring, JWT, 7 endpoint, 15 lỗi cài sẵn S01-S15). Mỗi lỗi lật đúng 1 hành vi và có oracle kiểm riêng (mutant-shop: `mutant-shop/oracle.py`, 15/15). Test gốc do người viết (10 test cho tuyển dụng, 7 cho cửa hàng); phần còn lại do hệ thống tự bù. Giả thuyết "checklist có thể bắt lỗi này không" (`expectChecklist`) được ghi vào danh mục TRƯỚC khi đo.

| | mutant-api (tuyển dụng) | mutant-shop (cửa hàng) |
|---|---|---|
| Số endpoint / số test chạy | 10 / 198 | 7 / 108 |
| Lỗi bắt được và gán đúng nhãn lỗi API | 5/42 = 12% | 1/15 = 7% |
| Lỗi có ít nhất 1 test Pass→Fail (phát hiện, kể cả chưa dám kết luận) | 21/42 = 50% | 9/15 = 60% |
| **Fail giả trên bản chuẩn** | **1,5%** | **24,1%** (26/108) |
| Token AI | 0 | 0 |

**Kết luận có căn cứ.**
- Khả năng bắt lỗi theo loại **nhất quán giữa 2 hệ thống**: validation và status code thường được phát hiện (100% và 56-100%), lỗi nghiệp vụ và lỗi trạng thái nhiều bước không bao giờ bị bắt bởi checklist (0/6 + 0/3 + 0/1). Đây là phân chia mà thiết kế dự kiến, và nó lặp lại ở hệ thống thứ hai.
- Giả thuyết đặt trước đúng 13/15 trên mutant-shop: 4/4 lỗi dự đoán "checklist không bắt được" đều lọt; 9/11 lỗi dự đoán "bắt được" được bắt. 2 lỗi dự đoán sai là S12 (GET không cần token) và S13 (đăng nhập nhận mọi mật khẩu).
- Probe độ bền request lỗi định dạng (G4) bắt được S15 (JSON hỏng trả 500 thay vì 400), có gán đúng nhãn `HTTP_ROBUSTNESS`; đây là lỗi duy nhất trên hệ thống này được gán đúng.

**Điều đo được là KHÔNG chuẩn trên mọi hệ thống: tỉ lệ Fail giả.** Trên hệ thống thứ hai, 26/108 test Fail ngay trên bản chuẩn (24,1% so với 1,5%). Nguyên nhân (đếm trên 1 lượt):

| Nguyên nhân | Số test | Bản chất |
|---|---|---|
| Security "thiếu token / token sai" kỳ vọng 401, API trả **403** | 12 | quy ước của API khác giả định mặc định của checklist |
| Mục biên ngầm (name rỗng, price -1, stock -1, int32 nhỏ nhất) kỳ vọng thành công, API từ chối 400 | 8 | spec không khai ràng buộc, checklist đoán là hợp lệ |
| Security trên endpoint đăng nhập (công khai) kỳ vọng 401, API trả 200 | 2 | endpoint đăng nhập không cần token |
| Probe Content-Type sai kỳ vọng 415, API trả 403 | 2 | API này trả 403 cho mọi lỗi đi qua `/error` (đã ghi trong mã nguồn của nó) |
| Thiếu tham số query bắt buộc kỳ vọng 400, API trả 403 | 2 | cùng quy ước 403 |

**Hệ quả quan trọng:** các test Fail trên bản chuẩn bị loại khỏi bằng chứng (chỉ test Pass trên bản chuẩn mới được tính). Vì vậy test Security "thiếu token" của `GET /api/products/{id}` bị loại và lỗi S12 lọt. Nói cách khác, **sự khác biệt về quy ước 401/403 làm mất luôn khả năng phát hiện lỗi xác thực** trên hệ thống thứ hai. Với S13 (đăng nhập nhận mọi mật khẩu), trong các test Pass trên bản chuẩn không có test sai-mật-khẩu nào; chưa điều tra sâu nguyên nhân (nghi ngờ nhận diện endpoint đăng nhập theo tên field, hệ thống này dùng `username` thay vì `email`).

**Phạm vi đo được (nói rõ để không suy rộng):**
- Đo trên 2 hệ thống chạy được tại chỗ. Các hệ thống đã thử trước đó (thư viện sách Basic Auth/form, Swagger 2.0 XML, xác thực query string) chỉ có kết quả "import và chạy được, phân loại Fail hợp lý" trong roadmap 17h, chưa có số đo Fail giả hay phát hiện lỗi, vì không còn ứng dụng đích chạy lại được trong repo.
- Cả 2 hệ thống đều là Spring Boot do nhóm tự viết hoặc chọn; chưa có API của bên thứ ba (Node, .NET, quy ước lỗi khác).
- Cỡ mẫu nhỏ: 15 và 42 lỗi, 2 lượt; số liệu A tất định nên 2 lượt cho kết quả giống hệt, không phải bằng chứng thống kê.
- Lỗi trên mutant-shop do chính tác giả viết sau khi biết checklist hoạt động thế nào; giả thuyết đặt trước giảm nhưng không loại bỏ thiên lệch.

**Số token dùng để ra các số liệu trên: 0.** Kiểm bằng sổ CSDL của hệ thống chính (mọi người dùng) trước và sau từng lần đo: tổng `test_generation_events.total_tokens` = 654.968 và tổng `generation_jobs.tokens_used` = 225.406 ở cả hai thời điểm; số dòng sổ 71 và 34 không đổi. Cấu hình A không gọi AI (job loại SYSTEM). Kết quả gốc: `docs/eval/results-A-2026-10-25.json`, `docs/eval/results-shop-A-2026-10-25.json` (trường `tokenAccounting`).

**Đã sửa sau phát hiện này (2026-10-25): quy ước API theo project.** Người dùng khai "API này trả 403 khi thiếu/sai token" (Cài đặt xác thực của project, hoặc `PUT /projects/{id}/api-conventions`). Quy ước chỉ áp dụng cho test cố ý bỏ hoặc làm sai token và kỳ vọng 401; test khác (vd sai mật khẩu đăng nhập) và mã khác 403 vẫn so khớp như thường, và API không chặn (200) vẫn Fail. Đo lại trên mutant-shop với quy ước 403, 2 lượt, 0 token:

| mutant-shop | Chưa khai quy ước | Khai 403 |
|---|---|---|
| Fail giả trên bản chuẩn | 26/108 = 24,1% | 14/108 = 13,0% |
| Lỗi được phát hiện | 9/15 = 60% | 10/15 = 67% |
| Lỗi xác thực (S12 GET không cần token) | không phát hiện | **phát hiện** |

14 Fail còn lại: 8 là mục biên ngầm (spec không khai ràng buộc nhưng API từ chối giá trị âm/rỗng). Chúng không hẳn là Fail giả: nội dung mục đã ghi "API trả 400 nghĩa là spec khai thiếu ràng buộc hoặc API có lỗi", tức là bất đồng thật giữa spec và API. 6 còn lại do quy ước "403 cho mọi lỗi framework" của API này và endpoint đăng nhập công khai; chưa có cấu hình cho 2 nhóm này. Lỗi S13 (đăng nhập nhận mọi mật khẩu) vẫn lọt. Token dùng cho lần đo lại: 0 (sổ CSDL không đổi: 654.968 / 225.406). Kết quả gốc: `docs/eval/results-shop-A-403-2026-10-25.json`.

**Lỗi tổng quát hoá thứ hai tìm ra và đã sửa (S13).** Spec của shop khai `security` toàn cục và không ghi `security: []` cho `/auth/login`, nên khi import endpoint đăng nhập bị coi là cần token. Hệ quả: (1) bỏ qua ca "sai mật khẩu → 401" (nhận diện đăng nhập yêu cầu endpoint không cần token) nên lỗi S13 lọt; (2) sinh 2 ca "thiếu token / token sai" cho chính endpoint đăng nhập, Fail giả trên bản chuẩn. Sửa: khi import, endpoint POST mang tên đăng nhập (không phải đăng ký, đăng xuất, làm mới, đặt lại mật khẩu) chỉ kế thừa security toàn cục thì coi là công khai; người dùng vẫn đổi tay được. Đo lại (2 lượt, 0 token, sổ CSDL không đổi 654.968 / 225.406):

| | Chưa khai quy ước | Khai 403 | Khai 403 + sửa nhận diện đăng nhập |
|---|---|---|---|
| mutant-shop: Fail giả trên bản chuẩn | 26/108 = 24,1% | 14/108 = 13,0% | 13/114 = 11,4% |
| mutant-shop: lỗi được phát hiện | 9/15 | 10/15 | **11/15 = 73%** |
| mutant-shop: lỗi xác thực (S12, S13) | 0/2 | 1/2 | **2/2** |
| mutant-api: phát hiện / Fail giả | 21/42, 1,5% | | 21/42, 1,5% (không đổi, không hồi quy) |

Kết quả gốc: `docs/eval/results-shop-A-403-login-2026-10-25.json`, `docs/eval/results-A-login-2026-10-25.json`. Trong 13 Fail còn lại của shop, 8 là bất đồng spec-API có thật (mục biên ngầm), 5 do quy ước "403 cho lỗi framework" chưa có cấu hình.

## 7B. Đo G5 (2026-10-26): trích luật bằng AI, cấu hình C và bộ held-out

**Ba cấu hình so sánh, cùng API tuyển dụng (10 endpoint), cùng 10 test gốc do người viết:**
- **A** = checklist tất định (0 token).
- **B** = A + AI nghiệp vụ đoán thêm test (66 test/lượt, 3 lượt).
- **C-luật** = A + test sinh tất định từ luật mà AI trích từ tài liệu SRS và người duyệt (9 test/lượt, 3 lượt). Bằng chứng chỉ tính test sinh từ luật, test gốc chỉ là bối cảnh.

**Số token chính xác** (đối chiếu sổ CSDL `test_generation_events` trước/sau từng bước, trùng với tổng token các job):

| Bước | Token | Dự đoán trước | Ghi chú |
|---|---|---|---|
| AI trích luật, 3 lượt | **42.564** (14.066 + 13.876 + 14.622) | 41.355 | +2,9%; trần cứng 25.000/lượt, không lượt nào chạm |
| Cấu hình B, 3 lượt | **296.568** (98.593 + 98.850 + 99.125) | 278.751 | +6,4%; trần cứng 120.000/lượt |
| **Tổng AI của G5** | **339.132** | 320.106 | +5,9%; trần cứng cộng lại 435.000 |
| A trên dev + held-out, C, các phép đo hệ thống thứ hai | **0** | 0 | sổ không đổi |

**Kết quả khả năng bắt lỗi** (số lỗi bị bắt; "đúng" = test Fail được gán đúng nhãn lỗi API, "phát hiện" = có test Pass→Fail kể cả nhãn chưa chắc; kết quả giống hệt giữa các lượt):

| | A (0 token) | B (+296.568 token) | C-luật (+42.564 token) |
|---|---|---|---|
| Bộ phát triển M01-M42 (in-sample) | 5 đúng / 21 phát hiện | 5 / 25 | 3 / 3 |
| Held-out chồng bộ M (H02, H06, H10, H13, H14) | 1 / 1 | 1 / 2 | 0 / 0 |
| **Held-out MỚI** (10 lỗi) | 0 / 2 | 0 / 4 | **1 / 2** |
| Held-out tất cả (15) | 1 / 3 | 1 / 6 | 1 / 2 |
| Fail giả trên bản chuẩn | 1,5% | 19,8% | **0%** |
| Test thêm mỗi lượt | 0 | 66 | 9 |

**Hợp A + luật** (cùng bằng chứng, không tính B): dev 8 đúng / 24 phát hiện trên 42; held-out 2 / 5 trên 15. Chi phí thêm so với A: ≈ 14.188 token/lượt trích luật (trung bình), bằng khoảng **1/7** chi phí của B (98.856 token/lượt).

**Trích luật bằng AI (3 lượt):** mỗi lượt giữ **5 luật có trích dẫn nguyên văn**, 0 luật GIẢ ĐỊNH. Cả 15 luật đều đúng so với đáp án (**precision 15/15 = 100%** sau kiểm chứng). Trên bộ đáp án 7 luật mà 9 mẫu luật biểu diễn được (R24, R28, R43, R47, R59, R60, R66) tìm được 5, thiếu R59 và R60 (số phần tử của mảng tiêu chí): **recall 5/7 = 71%**, giống nhau ở cả 3 lượt. R73 (không nộp đơn trùng) không tính vì mẫu UNIQUE chưa được đưa vào prompt. Mỗi lượt AI đề xuất thêm 3-6 luật bị bộ kiểm chứng tất định loại vì sai tham số (tổng đề xuất 8, 8 và 11); **chưa biết lý do cụ thể từng luật bị loại** vì hệ thống chỉ ghi số lượng, và chưa rõ R59/R60 có nằm trong số đó không. Nên nói chính xác: "luật người dùng nhìn thấy đều đúng", chứ không phải "AI đề xuất đúng 100%": tỉ lệ đề xuất qua được kiểm chứng là 15/27 = 56%.

**Đọc kết quả:**
1. **AI nghiệp vụ cũ (B) đắt và nhiễu**: ≈ 99 nghìn token/lượt cho 66 test, 19,8% Fail giả (đúng như kết quả cũ 23%), và các lỗi nghiệp vụ nó phát hiện chỉ ở mức "nghi ngờ" (nhãn nguyên nhân mơ hồ), chưa lần nào được gán đúng.
2. **Luật có người duyệt (C) gọn và chắc hơn**: 9 test, 0 Fail giả, bắt **đúng nhãn** M23, M24, M25 (dev) và H08 (held-out mới, lỗi "quên kiểm tra salaryMin ≤ salaryMax ở PUT"), cộng H09 mức nghi ngờ. Đây là lỗi mà checklist A bỏ lọt hoàn toàn.
3. **Nhưng C chỉ bắt được lỗi vi phạm một luật có trong tài liệu và có trong mẫu**: chỉ 3/15 held-out (H08, H09, H10) vi phạm một trong 5 luật đã trích; H11 (không nộp đơn trùng, mẫu UNIQUE chưa đưa vào prompt) và H12 (máy trạng thái) nằm ngoài khả năng. H10 (tổng trọng số dùng `> 100` thay vì `!= 100`) bị **lọt** dù luật R66 đã được trích đúng, nghi do test sinh ra chỉ thử tổng lớn hơn 100; chưa điều tra (không sửa hệ thống sau khi thấy held-out).
4. **Ba cấu hình bổ sung cho nhau chứ không thay nhau**: B không thêm lỗi nào được gán đúng so với A; C thêm đúng nhãn 4 lỗi nghiệp vụ mà A không bắt được.

**Hạn chế riêng của đợt đo này (thêm vào mục 7):**
- **Người gán nhãn luật là người phát triển hệ thống** và biết đáp án; 15 luật đều trùng đáp án nên khó thiên lệch nhưng không loại bỏ được. Không có người duyệt thứ hai.
- **Mô phỏng người duyệt**: coi như người duyệt cẩn thận (duyệt đúng các luật đúng). Người thật có thể duyệt sai; E2 chưa đo.
- **Held-out không hoàn toàn độc lập**: do AI cùng họ viết, rút từ cùng 86 luật chuẩn với tài liệu SRS, và 5/15 chồng bộ M. Phép đo trích luật chỉ trên một tài liệu và một hệ thống (API tuyển dụng); tài liệu do AI viết nên có thể dễ trích hơn tài liệu thật.
- **Cỡ mẫu nhỏ** (15 lỗi held-out, 3 lượt giống hệt nhau), không có kiểm định thống kê.
- **Cấu hình B đo lại cho kết quả gần như cũ** (Fail giả 19,8% so với 23,2%) nên số liệu B cũ vẫn đúng; thay đổi nhỏ: M09 từ bỏ lọt thành nghi ngờ, M11 từ nghi ngờ thành đúng.

**Điều tra H10 (2026-10-26): vì sao test sinh từ luật R66 bỏ lọt lỗi.** Luật "tổng trọng số = 100" được trích đúng và cho đúng 1 test: đổi tổng thành 101 (`RuleTestGenerator.arrayAggregate`, nhánh `EQ`/`LTE` chỉ tạo `giá trị + 1`). Mutant H10 viết `total > 100` thay vì `total != 100`: tổng 101 vẫn bị từ chối nên test Pass, còn tổng nhỏ hơn 100 lại lọt. Tái hiện trực tiếp trên API (0 token): bản chuẩn trả 200 / 400 / 400 và H10 trả 200 / 400 / **200** cho tổng 100 / 101 / 99. Kết luận: **lỗi nằm ở bộ sinh test, không phải ở AI hay ở luật**: với luật "bằng" chỉ thử một phía vi phạm. Cùng kiểu thiếu sót có thể có ở `COUNT` (số phần tử) và ở biên "đúng bằng giá trị giới hạn" của các phép ≤ / ≥. Cách sửa dự kiến: với `EQ` sinh cả hai phía (giá trị − 1 và + 1), với `LTE`/`GTE` thêm test "đúng bằng giới hạn phải được chấp nhận".
**Đã sửa sau đó (cùng ngày), ghi rõ là cải tiến SAU KHI thấy kết quả held-out.** `RuleTestGenerator.arrayAggregate` nay thử cả hai phía của giới hạn: luật "bằng" sinh cả tổng − 1 và + 1; luật ≤ / ≥ / < / > thêm test "sát giới hạn phía hợp lệ phải được chấp nhận" (tổng và số phần tử). Có 3 test đơn vị mới (944 test backend pass). Đo lại cấu hình C bằng đúng 5 luật AI đã trích (dựng lại dưới dạng luật nhập tay, vì bộ sinh chỉ phụ thuộc mẫu + tham số), 0 token, `docs/eval/results-C-replay-2026-10-26.json`:

| C-luật (1 lượt đại diện, 3 lượt trích ra cùng 5 luật) | Trước khi sửa | Sau khi sửa |
|---|---|---|
| Test sinh từ luật | 9 | 10 |
| Fail giả trên bản chuẩn | 0% | 0% |
| Bộ dev: đúng / phát hiện | 3 / 3 (M23, M24, M25) | 3 / 3 (không đổi, không hồi quy; M25 nay do 2 test bắt thay vì 1) |
| Held-out: đúng / phát hiện | 1 / 2 (H08) | 2 / 3 (H08, **H10**) |

**Cách đọc cho đúng:** (1) bộ dev không có lỗi nào mới được bắt, nên cải tiến này chưa có bằng chứng ngoài tập; chỉ có bằng chứng là không làm hỏng và chặt hơn ở M25. (2) H10 giờ được bắt, nhưng H10 là lỗi đã dùng để phát hiện thiếu sót nên **không còn là mẫu độc lập**: không đưa vào số "held-out mới" ở bảng 7B (các bảng đó giữ nguyên số đo TRƯỚC khi sửa). (3) Chỉ 1 trong 15 lỗi held-out chịu ảnh hưởng.

Dữ liệu gốc: `docs/eval/results-A-heldout-2026-10-26.json`, `results-B-heldout-2026-10-26.json`, `rules-extract-2026-10-26.json` (luật AI đề xuất), `rules-labels-2026-10-26.json` (nhãn), `results-C-rules-2026-10-26.log` (log gốc phép đo C; file JSON không ghi được do lỗi script, đã sửa).

## 8. Chạy lại

```bash
# 1. API đích có lỗi cài sẵn (cổng 8083); hệ thống chính chạy bằng docker compose như bình thường
cd mutant-api && MUTANT_CONTROL=true ./mvnw spring-boot:run

# 2. Kiểm chứng bộ mutant (không cần hệ thống chính)
cd mutant-api && ./mvnw test                                   # oracle suite 27/27 + spec khớp hành vi

# 3. Cấu hình A (0 token, ~6 phút/lượt, kết quả giống hệt giữa các lượt)
python scripts/eval-mutants.py --config A --runs 2 --out docs/eval/results-A-run12.json

# 4. Cấu hình B/C (TỐN TOKEN THẬT, ≈ 90.000 token/lượt; luôn xem dự toán trước)
MSYS_NO_PATHCONV=1 python scripts/eval-mutants.py --config B --dry-run --seed-baselines --runs 3
MSYS_NO_PATHCONV=1 python scripts/eval-mutants.py --config B --runs 3 --seed-baselines --yes-spend --token-cap 120000 --out docs/eval/results-B.json

# 5. Gộp nhiều file kết quả, sinh bảng cho báo cáo
python scripts/eval-mutants.py --merge docs/eval/results-B-run1.json docs/eval/results-B-run23.json --out docs/eval/results-B-merged.json
python scripts/eval/make-report-tables.py docs/eval/results-A-run12.json docs/eval/results-B-merged.json

# 6. Schemathesis (cài trong venv riêng)
python scripts/eval/run-schemathesis.py --st <đường dẫn st> --out docs/eval/schemathesis-2026-10-21.json
```

File kết quả: `results-A-run12.json` (A, 2 lượt), `results-B-run1.json` + `results-B-run23.json` → `results-B-merged.json` (B và C, 3 lượt; lượt 1 được đánh giá lại bằng phương pháp đã sửa trên
project đã sinh, 88.272 token đã tiêu trước đó), `schemathesis-2026-10-21.json`, `pilot-B-2026-10-21.json` (lần thử đốt nhầm token, giữ làm bằng chứng cho mục 5).
