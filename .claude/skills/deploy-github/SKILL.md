---
name: deploy-github
description: Quy trình branch, commit và deploy cho AI API Testing Assistant (chưa dùng Docker). Dùng khi tạo branch, viết commit message, mở PR, hoặc release.
disable-model-invocation: true
---

# Git Workflow

## Nhánh
- `main` — luôn deploy-ready, không push trực tiếp
- `develop` — nhánh tích hợp, các feature merge vào đây trước
- `feature/<ten-chuc-nang>` — vd: feature/swagger-import, feature/ai-test-generation
- `fix/<ten-loi>` — vd: fix/token-encryption-bug

## Commit convention (Conventional Commits)
Format: `<type>: <mô tả ngắn>`

Type dùng:
- feat: thêm chức năng mới — vd: feat: thêm import OpenAPI từ URL
- fix: sửa lỗi — vd: fix: sửa lỗi decode token sai định dạng
- refactor: tái cấu trúc code, không đổi hành vi
- test: thêm/sửa test
- docs: cập nhật tài liệu
- chore: việc linh tinh (deps, config, ci)

## Quy trình
1. Tạo branch từ `develop`: `git checkout -b feature/ten-chuc-nang`
2. Commit theo convention trên, mỗi commit là 1 thay đổi rõ ràng
3. Push và mở PR vào `develop`, yêu cầu ít nhất 1 review trong nhóm
4. Merge `develop` → `main` khi release (không cần Docker, deploy trực tiếp qua build jar/npm build lên server)
5. Backend: `./mvnw clean package` → chạy jar; Frontend: `npm run build` → serve static

## GitHub Actions (build & test only, không build image)
Trigger: push vào `develop` hoặc PR vào `main`
- Job backend: `mvn test`
- Job frontend: `npm ci && npm run build && npm test`