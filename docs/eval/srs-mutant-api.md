# Đặc tả yêu cầu phần mềm (SRS) — Hệ thống Tuyển dụng thu nhỏ

| Thông tin | Giá trị |
|---|---|
| Phiên bản | 1.2 |
| Ngày cập nhật | 23/10/2026 |
| Người soạn | Nhóm Phân tích nghiệp vụ (BA) |
| Đối tượng đọc | Đội phát triển backend/frontend, đội kiểm thử |
| Trạng thái | Đã duyệt nội bộ — còn một số vấn đề mở (mục 8) |

---

## 1. Giới thiệu

### 1.1 Mục đích tài liệu

Tài liệu này mô tả các yêu cầu nghiệp vụ và chức năng của Hệ thống Tuyển dụng thu nhỏ, làm căn cứ chung cho đội phát triển khi xây dựng giao diện lập trình (API) và cho đội kiểm thử khi thiết kế kịch bản kiểm thử. Tài liệu tập trung vào *hệ thống phải làm gì* và *quy tắc nghiệp vụ nào phải tuân thủ*, không quy định cách cài đặt cụ thể.

### 1.2 Bối cảnh

Hiện nay bộ phận nhân sự của các công ty đối tác đang đăng tin tuyển dụng qua email và bảng tính, ứng viên gửi CV rời rạc nên việc theo dõi ai đã nộp, hồ sơ đang ở bước nào, ai phụ trách rất khó khăn. Hệ thống được xây dựng để:

- Cho nhà tuyển dụng đăng và quản lý tin tuyển dụng tập trung.
- Cho ứng viên nộp hồ sơ trực tuyến vào từng tin.
- Cho nhà tuyển dụng thiết lập bộ tiêu chí đánh giá cho từng vị trí để chuẩn bị cho việc chấm điểm hồ sơ một cách nhất quán.
- Theo dõi vòng đời hồ sơ từ lúc nộp đến khi có kết quả cuối cùng.

### 1.3 Phạm vi

Trong phạm vi giai đoạn 1:

- Đăng nhập bằng tài khoản được cấp sẵn, xem thông tin tài khoản.
- Đăng, xem, sửa, gỡ tin tuyển dụng.
- Thiết lập bộ tiêu chí đánh giá cho tin.
- Nộp hồ sơ ứng tuyển, xem hồ sơ.
- Cập nhật trạng thái xử lý hồ sơ.

Ngoài phạm vi giai đoạn 1: đăng ký tài khoản mới, quên mật khẩu, tải lên file CV, tìm kiếm/lọc tin, chấm điểm hồ sơ tự động theo bộ tiêu chí, gửi email thông báo cho ứng viên.

### 1.4 Thuật ngữ

| Thuật ngữ | Giải thích |
|---|---|
| HR / Nhà tuyển dụng | Nhân viên nhân sự của một công ty, người đăng tin và xử lý hồ sơ |
| Ứng viên | Người tìm việc, người nộp hồ sơ vào tin tuyển dụng |
| Tin tuyển dụng (tin) | Một vị trí cần tuyển, gồm tiêu đề, mô tả, mức lương, loại hình, hạn nộp |
| Chủ tin | HR đã đăng tin đó |
| Bộ tiêu chí (rubric) | Danh sách các tiêu chí dùng để đánh giá hồ sơ cho một tin, mỗi tiêu chí có trọng số và điểm tối đa |
| Hồ sơ / Đơn ứng tuyển | Một lần ứng viên ứng tuyển vào một tin |
| Gỡ tin | Thao tác HR xoá tin khỏi hệ thống (xoá mềm, xem mục 4.2.4) |
| Phiên đăng nhập / token | Chuỗi xác thực hệ thống cấp sau khi đăng nhập, gửi kèm mọi yêu cầu sau đó |

### 1.5 Tài liệu tham chiếu

- Đặc tả OpenAPI của hệ thống (bản xuất tự động từ backend).
- Biên bản họp làm rõ yêu cầu với bộ phận nhân sự đối tác.

---

## 2. Mô tả tổng quan

### 2.1 Các vai trò người dùng

**Nhà tuyển dụng (HR).** Mỗi HR thuộc về một công ty. HR đăng tin cho vị trí công ty mình cần tuyển, thiết lập tiêu chí đánh giá, xem hồ sơ ứng viên nộp vào tin của mình và quyết định mời phỏng vấn, nhận hay loại ứng viên. Một HR không được can thiệp vào tin và hồ sơ thuộc tin của HR khác, kể cả khi hai người cùng là nhà tuyển dụng.

**Ứng viên.** Ứng viên xem các tin tuyển dụng, nộp hồ sơ kèm thư giới thiệu và theo dõi hồ sơ của mình. Ứng viên không có quyền với các chức năng quản lý tin hay xử lý hồ sơ.

Ở giai đoạn 1, hệ thống có sẵn bốn tài khoản dùng thử: hai HR thuộc hai công ty khác nhau (hr1@demo.local, hr2@demo.local) và hai ứng viên (cand1@demo.local, cand2@demo.local).

### 2.2 Giả định và ràng buộc

- Người dùng đã có tài khoản do quản trị cấp; hệ thống chưa hỗ trợ tự đăng ký.
- Mỗi tài khoản chỉ có đúng một vai trò.
- Hệ thống được dùng qua giao diện web; giao diện gọi API theo định dạng JSON.

---

## 3. Quy trình nghiệp vụ

### 3.1 Quy trình tổng thể

```
HR đăng tin ──► HR thiết lập bộ tiêu chí ──► Ứng viên xem tin và nộp hồ sơ
                                                     │
                                                     ▼
                         HR xem hồ sơ ──► Mời phỏng vấn / Từ chối
                                                     │
                                                     ▼
                                       Trúng tuyển / Từ chối (kết thúc)
```

### 3.2 Đăng tin tuyển dụng

1. HR đăng nhập, chọn "Đăng tin mới".
2. HR nhập tiêu đề, mô tả công việc, khoảng lương (nếu muốn công khai), loại hình công việc và hạn nộp hồ sơ.
3. Hệ thống kiểm tra dữ liệu; nếu hợp lệ thì lưu tin ở trạng thái đang mở và hiển thị trang chi tiết tin.
4. HR có thể quay lại sửa tin hoặc gỡ tin khi không còn nhu cầu tuyển.

### 3.3 Thiết lập bộ tiêu chí đánh giá

Với mỗi tin, HR khai báo các tiêu chí sẽ dùng để đánh giá ứng viên, ví dụ "Kinh nghiệm Java" (trọng số 40%, điểm tối đa 10), "Tiếng Anh" (30%, điểm tối đa 5), "Kỹ năng mềm" (30%, điểm tối đa 5). Trọng số thể hiện mức độ quan trọng của tiêu chí trong điểm tổng. Ở giai đoạn sau, người đánh giá sẽ cho điểm từng tiêu chí và hệ thống quy đổi ra điểm tổng theo trọng số; giai đoạn 1 chỉ quản lý bộ tiêu chí.

### 3.4 Ứng tuyển

1. Ứng viên đăng nhập, mở chi tiết tin.
2. Ứng viên viết thư giới thiệu (không bắt buộc) và xác nhận đồng ý cho phép hệ thống phân tích hồ sơ.
3. Hệ thống ghi nhận hồ sơ ở trạng thái "Chờ xử lý".

### 3.5 Xử lý hồ sơ

HR mở hồ sơ, đánh giá và cập nhật trạng thái. Vòng đời hồ sơ như sau:

```
Chờ xử lý ──► Mời phỏng vấn ──► Trúng tuyển
    │               │
    └──► Từ chối ◄──┘
```

---

## 4. Yêu cầu chức năng và quy tắc nghiệp vụ

### 4.1 Đăng nhập và tài khoản

**Mô tả.** Người dùng đăng nhập bằng email và mật khẩu. Khi đăng nhập thành công, hệ thống cấp một phiên đăng nhập (token) kèm vai trò của người dùng và thời điểm phiên hết hạn.

**Quy tắc:**

- **BR-AUTH-01.** Email là thông tin bắt buộc khi đăng nhập và không được để trống.
- **BR-AUTH-02.** Mật khẩu là thông tin bắt buộc khi đăng nhập và không được để trống.
- **BR-AUTH-03.** Nếu email không thuộc tài khoản nào hoặc mật khẩu không đúng, hệ thống từ chối đăng nhập với cùng một thông báo chung, không cho biết sai ở email hay ở mật khẩu, để tránh bị dò xem email nào đã có tài khoản (HTTP 401).
- **BR-AUTH-04.** Tất cả các chức năng còn lại của hệ thống đều yêu cầu người dùng đã đăng nhập; yêu cầu không kèm phiên đăng nhập, hoặc kèm phiên không hợp lệ hay đã hết hạn, đều bị từ chối (HTTP 401).

**Xem thông tin tài khoản của tôi:**

- **BR-AUTH-05.** Màn hình "Tài khoản của tôi" hiển thị đúng thông tin của chính người đang đăng nhập, gồm mã người dùng, email, họ tên và vai trò.
- **BR-AUTH-06.** Thông tin tài khoản trả về tuyệt đối không bao gồm mật khẩu, kể cả ở dạng đã mã hoá.

### 4.2 Quản lý tin tuyển dụng

#### 4.2.1 Đăng tin mới

**Tác nhân:** HR.

**Thông tin của một tin:**

| Thông tin | Bắt buộc | Ghi chú |
|---|---|---|
| Tiêu đề | Có | Tối đa 200 ký tự |
| Mô tả công việc | Có | Văn bản tự do |
| Lương tối thiểu / tối đa | Không | Số tiền |
| Đơn vị tiền tệ | Không | Ví dụ VND, USD |
| Loại hình công việc | Có | Toàn thời gian / Bán thời gian / Thực tập |
| Hạn nộp hồ sơ | Có | Ngày |

**Quy tắc:**

- **BR-JOB-01.** Chỉ nhà tuyển dụng mới được đăng tin; ứng viên thực hiện thao tác này sẽ bị từ chối ngay (HTTP 403), không cần xét nội dung tin có hợp lệ hay không.
- **BR-JOB-02.** Tiêu đề tin (title) là bắt buộc và phải có ít nhất một ký tự.
- **BR-JOB-03.** Tiêu đề chỉ gồm toàn khoảng trắng được xem như để trống và bị từ chối.
- **BR-JOB-04.** Tiêu đề dài tối đa 200 ký tự.
- **BR-JOB-05.** Mô tả công việc là bắt buộc, không giới hạn độ dài.
- **BR-JOB-06.** Mức lương là thông tin tuỳ chọn; nếu nhập thì lương tối thiểu và lương tối đa đều phải là số không âm (được phép bằng 0).
- **BR-JOB-07.** Khi nhập cả lương tối thiểu và lương tối đa thì lương tối thiểu không được lớn hơn lương tối đa; hai giá trị bằng nhau vẫn hợp lệ.
- **BR-JOB-08.** Đơn vị tiền tệ, nếu có, phải ghi theo mã gồm đúng 3 ký tự (ví dụ VND, USD).
- **BR-JOB-09.** Loại hình công việc bắt buộc phải chọn một trong ba giá trị: toàn thời gian (FULL_TIME), bán thời gian (PART_TIME) hoặc thực tập (INTERN).
- **BR-JOB-10.** Hạn nộp hồ sơ là bắt buộc, nhập theo định dạng năm-tháng-ngày (yyyy-MM-dd) và phải là một ngày có thật trên lịch.
- **BR-JOB-11.** Hạn nộp hồ sơ không được là ngày trong quá khứ; chọn đúng ngày hôm nay vẫn được chấp nhận.
- **BR-JOB-12.** Tin mới luôn thuộc về HR đang đăng nhập và luôn ở trạng thái đang mở (OPEN); người dùng không thể tự chỉ định chủ tin hay trạng thái ban đầu của tin.

*Ghi chú:* nếu một tin vừa có hạn nộp ở quá khứ vừa có khoảng lương sai, hệ thống báo lỗi hạn nộp trước. Đăng tin thành công trả về HTTP 201 cùng thông tin tin vừa tạo.

#### 4.2.2 Xem chi tiết tin

**Tác nhân:** HR, Ứng viên.

- **BR-JOB-13.** Mọi người dùng đã đăng nhập, dù là HR hay ứng viên, đều xem được chi tiết một tin tuyển dụng.
- **BR-JOB-14.** Nếu tin không tồn tại, hệ thống báo không tìm thấy tin tuyển dụng.

#### 4.2.3 Sửa tin

**Tác nhân:** HR (chủ tin).

- **BR-JOB-15.** Đối với các thao tác sửa tin, gỡ tin và thiết lập bộ tiêu chí, hệ thống kiểm tra tin có tồn tại trước rồi mới kiểm tra quyền; tin không tồn tại thì báo không tìm thấy (HTTP 404) bất kể người thao tác là ai.
- **BR-JOB-16.** Ứng viên không được sửa tin tuyển dụng.
- **BR-JOB-17.** HR chỉ được sửa tin do chính mình đăng; HR khác, dù cũng là nhà tuyển dụng, sẽ bị từ chối.
- **BR-JOB-18.** Dữ liệu khi sửa tin phải tuân theo cùng các ràng buộc như khi đăng tin mới đối với tiêu đề (bắt buộc, tối đa 200 ký tự), mô tả (bắt buộc), mức lương (số không âm), loại hình công việc (bắt buộc, một trong ba giá trị) và hạn nộp (bắt buộc, đúng định dạng ngày).
- **BR-JOB-19.** Khi sửa tin, lương tối thiểu vẫn không được vượt quá lương tối đa.
- **BR-JOB-20.** Hạn nộp sau khi sửa cũng không được lùi về một ngày trong quá khứ.
- **BR-JOB-21.** Việc sửa tin không làm thay đổi chủ tin hay trạng thái của tin, kể cả khi dữ liệu gửi lên có kèm các thông tin này.

#### 4.2.4 Gỡ tin

**Tác nhân:** HR (chủ tin).

- **BR-JOB-22.** Ứng viên không có quyền gỡ tin.
- **BR-JOB-23.** HR không được gỡ tin do HR khác đăng.
- **BR-JOB-24.** Gỡ tin là xoá mềm: dữ liệu tin vẫn được lưu lại để đối soát; gỡ thành công thì hệ thống trả về HTTP 204 và không kèm nội dung.
- **BR-JOB-25.** Sau khi gỡ, tin được coi như không còn tồn tại: không xem được, không sửa được, không gỡ lần nữa được, không thiết lập tiêu chí được và ứng viên cũng không nộp hồ sơ vào được — tất cả đều nhận thông báo không tìm thấy tin.

### 4.3 Bộ tiêu chí đánh giá

**Tác nhân:** HR (chủ tin).

**Mô tả.** HR gửi toàn bộ danh sách tiêu chí (criteria) cho một tin. Mỗi tiêu chí gồm tên, trọng số (phần trăm) và điểm tối đa. Hệ thống trả lại bộ tiêu chí đã lưu kèm tổng trọng số.

**Quy tắc:**

- **BR-RUB-01.** Ứng viên không được thiết lập bộ tiêu chí.
- **BR-RUB-02.** HR chỉ thiết lập được bộ tiêu chí cho tin do chính mình đăng.
- **BR-RUB-03.** Bộ tiêu chí bắt buộc phải được gửi dưới dạng một danh sách.
- **BR-RUB-04.** Danh sách tiêu chí không được rỗng, phải có ít nhất một tiêu chí.
- **BR-RUB-05.** Mỗi tin có tối đa 20 tiêu chí.
- **BR-RUB-06.** Tên tiêu chí là bắt buộc, dài từ 1 đến 200 ký tự.
- **BR-RUB-07.** Trọng số của mỗi tiêu chí là bắt buộc, phải lớn hơn 0 và không vượt quá 100 (nhỏ nhất là 0,01).
- **BR-RUB-08.** Điểm tối đa của tiêu chí là bắt buộc, phải là số nguyên từ 1 trở lên.
- **BR-RUB-09.** Tổng trọng số của tất cả tiêu chí phải bằng đúng 100, không làm tròn (ví dụ ba tiêu chí 33,33 cộng lại được 99,99 là không hợp lệ).

### 4.4 Nộp hồ sơ ứng tuyển

**Tác nhân:** Ứng viên.

**Thông tin hồ sơ:** thư giới thiệu (coverLetter) và xác nhận đồng ý cho phép phân tích hồ sơ.

**Quy tắc:**

- **BR-APP-01.** Chỉ ứng viên mới được nộp hồ sơ; HR thực hiện thao tác này sẽ bị từ chối ngay, kể cả khi tin không tồn tại, vì hệ thống kiểm tra vai trò trước khi kiểm tra tin.
- **BR-APP-02.** Tin được ứng tuyển phải tồn tại; nếu không, hệ thống báo không tìm thấy tin.
- **BR-APP-03.** Thư giới thiệu không bắt buộc, được phép để trống, dài tối đa 2000 ký tự.
- **BR-APP-04.** Ứng viên phải đồng ý cho phép hệ thống phân tích hồ sơ; nếu không đồng ý, hồ sơ không được tiếp nhận (mã lỗi CONSENT_REQUIRED).
- **BR-APP-05.** Mỗi ứng viên chỉ được nộp một hồ sơ cho mỗi tin tuyển dụng; nộp lần thứ hai vào cùng một tin sẽ bị từ chối (HTTP 409).
- **BR-APP-06.** Hồ sơ mới nộp thuộc về ứng viên đang đăng nhập và có trạng thái ban đầu là "Chờ xử lý" (PENDING).

### 4.5 Xem hồ sơ ứng tuyển

**Tác nhân:** Ứng viên (chủ hồ sơ), HR (chủ tin).

- **BR-APP-07.** Nếu hồ sơ không tồn tại, hệ thống báo không tìm thấy hồ sơ.
- **BR-APP-08.** Ứng viên chỉ xem được hồ sơ của chính mình.
- **BR-APP-09.** HR chỉ xem được hồ sơ nộp vào tin do mình đăng, kể cả khi tin đó đã bị gỡ; HR khác sẽ bị từ chối.

### 4.6 Xử lý hồ sơ (cập nhật trạng thái)

**Tác nhân:** HR (chủ tin).

| Trạng thái | Ý nghĩa |
|---|---|
| Chờ xử lý (PENDING) | Hồ sơ vừa nộp, chưa được xem xét |
| Mời phỏng vấn (INTERVIEW_INVITED) | HR đã mời ứng viên phỏng vấn |
| Trúng tuyển (HIRED) | Ứng viên được nhận |
| Từ chối (REJECTED) | Ứng viên bị loại |

**Quy tắc:**

- **BR-APP-10.** Hồ sơ cần cập nhật phải tồn tại; hệ thống kiểm tra điều này trước khi kiểm tra quyền.
- **BR-APP-11.** Chỉ HR được cập nhật trạng thái hồ sơ; ứng viên, kể cả chính người nộp hồ sơ, không được tự đổi trạng thái.
- **BR-APP-12.** HR chỉ cập nhật được hồ sơ nộp vào tin do mình đăng.
- **BR-APP-13.** Trạng thái mới là thông tin bắt buộc khi cập nhật.
- **BR-APP-14.** Trạng thái mới chỉ được là Mời phỏng vấn, Trúng tuyển hoặc Từ chối; không thể đưa hồ sơ về "Chờ xử lý".
- **BR-APP-15.** Chỉ được chuyển từ Chờ xử lý sang Mời phỏng vấn hoặc Từ chối, và từ Mời phỏng vấn sang Trúng tuyển hoặc Từ chối; mọi bước chuyển khác, như nhảy thẳng từ Chờ xử lý sang Trúng tuyển hay cập nhật lại đúng trạng thái hiện tại, đều bị từ chối (HTTP 409).
- **BR-APP-16.** Trúng tuyển và Từ chối là trạng thái kết thúc; hồ sơ đã ở một trong hai trạng thái này thì không thể chuyển sang trạng thái nào khác.

---

## 5. Quy định chung về phản hồi lỗi

- Mọi lỗi trả về theo cùng một cấu trúc gồm mã lỗi (code), thông điệp mô tả (message) và, nếu là lỗi dữ liệu nhập, danh sách chi tiết (details).
- Khi dữ liệu nhập có nhiều chỗ sai, hệ thống liệt kê tất cả lỗi trong một lần phản hồi để người dùng sửa một lượt, không báo từng lỗi một.
- Độ dài văn bản được tính theo số ký tự người đọc nhìn thấy (ký tự Unicode), không tính theo số byte; tiếng Việt có dấu được tính như ký tự thường.
- Thứ tự kiểm tra chung: xác thực đăng nhập → tồn tại của đối tượng (với các chức năng có nêu ở trên) → quyền → định dạng dữ liệu → quy tắc nghiệp vụ.

---

## 6. Yêu cầu phi chức năng

| Mã | Nhóm | Yêu cầu |
|---|---|---|
| NFR-01 | Bảo mật | Mật khẩu được lưu ở dạng băm, không bao giờ xuất hiện trong phản hồi hay nhật ký hệ thống. |
| NFR-02 | Bảo mật | Phiên đăng nhập có thời hạn; hết hạn người dùng phải đăng nhập lại. |
| NFR-03 | Hiệu năng | Các thao tác xem/đăng/sửa đơn lẻ phản hồi dưới 1 giây trong điều kiện tải bình thường (dưới 50 người dùng đồng thời). |
| NFR-04 | Toàn vẹn dữ liệu | Không xoá vật lý tin tuyển dụng để giữ lịch sử hồ sơ đã nộp. |
| NFR-05 | Khả năng tích hợp | API được mô tả bằng tài liệu OpenAPI, cập nhật mỗi khi thay đổi. |
| NFR-06 | Ngôn ngữ | Giao diện và thông báo lỗi hiển thị cho người dùng bằng tiếng Việt. |

---

## 7. Ghi chú giao diện

- Ô "Hạn nộp hồ sơ" dùng bộ chọn ngày, mặc định làm mờ các ngày đã qua.
- Ô "Loại hình công việc" là danh sách thả xuống ba lựa chọn, không cho nhập tự do.
- Ở màn hình thiết lập tiêu chí, hiển thị tổng trọng số hiện tại ngay dưới bảng tiêu chí và tô đỏ khi tổng khác 100.
- Ô thư giới thiệu có bộ đếm ký tự còn lại.
- Ô đồng ý phân tích hồ sơ là checkbox, không được đánh dấu sẵn.
- Nút cập nhật trạng thái hồ sơ chỉ hiển thị các bước chuyển hợp lệ từ trạng thái hiện tại; hồ sơ ở trạng thái kết thúc thì ẩn nút.
- Thông báo lỗi hiển thị ngay dưới ô nhập tương ứng, dựa trên danh sách chi tiết lỗi mà API trả về.

---

## 8. Vấn đề mở

| # | Nội dung | Người phụ trách |
|---|---|---|
| 1 | Có cần chặn nộp hồ sơ vào tin đã quá hạn nộp hay không — bộ phận nhân sự chưa thống nhất. | BA + đối tác |
| 2 | Thiết lập bộ tiêu chí sau khi đã có hồ sơ được chấm điểm thì xử lý điểm cũ thế nào (để dành cho giai đoạn chấm điểm). | BA |
| 3 | Có hiển thị danh sách hồ sơ theo tin cho HR ở giai đoạn 1 hay không. | PO |
