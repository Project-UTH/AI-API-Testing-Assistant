---
name: spring-boot-workflow
description: Quy trình từng bước khi phát triển 1 chức năng backend mới cho AI API Testing Assistant (từ phân tích yêu cầu đến khi test xong). Dùng khi bắt đầu code 1 endpoint/chức năng mới, hoặc khi cần checklist trước khi coi 1 task backend là hoàn thành.
---

# Workflow phát triển Backend

Áp dụng quy trình 6 bước dưới đây cho **mọi chức năng backend mới**, không bỏ bước dù chức năng nhỏ.

## 1. Analyze — Phân tích yêu cầu
- Xác định input/output của chức năng
- Xác định chức năng này thuộc domain nào (xem skill `springboot-architecture`)
- Kiểm tra đã có entity/DTO liên quan chưa, tránh tạo trùng

## 2. Design — Thiết kế trước khi code
- Định nghĩa endpoint: method, path, request DTO, response DTO
- **Đối chiếu với skill `api-contract`** để đảm bảo format response khớp chuẩn chung với frontend
- Nếu đổi format response của endpoint đã có → phải xác nhận với người phụ trách frontend trước khi code, vì frontend có thể đang dùng

## 3. Implement — Code theo layer
- Entity/Repository → Service (business logic) → Controller (mỏng nhất có thể)
- Với chức năng gọi AI (Spring AI): implement trong `service/ai/`, luôn có timeout + xử lý lỗi khi LLM trả về không đúng định dạng JSON mong đợi
- Với chức năng lưu credential (API Key/Token): áp dụng mã hoá AES ngay từ bước viết Service, không để plain-text đi qua bất kỳ lớp nào kể cả tạm thời

## 4. Secure — Rà bảo mật
- Endpoint có cần xác thực JWT không? Có cần phân quyền theo project (chỉ owner mới xem được) không?
- Input từ người dùng (đặc biệt là URL Swagger import, hoặc dữ liệu test case) có được validate/sanitize chưa — tránh SSRF khi fetch URL do người dùng nhập

## 5. Test
- Viết unit test cho Service (mock Repository)
- Với endpoint gọi AI: test case nên mock response của LLM, không gọi API thật trong unit test (tốn tiền + không ổn định)
- Chạy `./mvnw test` xác nhận pass trước khi coi là xong

## 6. Verify — Xác nhận trước khi hoàn thành
- Response thực tế có đúng format trong `api-contract` không (test bằng Postman/curl)
- Log không lộ thông tin nhạy cảm (token, password)
- Commit theo convention đã thống nhất (`feat:`, `fix:`, ...) và push vào đúng branch `feature/<ten-chuc-nang>`

## Khi debug lỗi
- Ưu tiên tìm root cause thay vì fix triệu chứng — đọc kỹ log, xác định lỗi ở layer nào (Controller nhận sai input? Service logic sai? Repository query sai? hay do AI trả JSON sai schema?)
- Nếu lỗi liên quan tới AI sinh sai dữ liệu, kiểm tra prompt trước (xem `docs/ai/test-generation-strategy.md`) trước khi sửa code xử lý response
