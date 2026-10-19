import { useState } from "react"

import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { CopyButton } from "@/components/ci/CopyButton"
import { API_BASE_URL } from "@/lib/api"

/** Ghép URL backend tuyệt đối: nếu base tương đối (vd /api/v1) thì lấy theo origin trang hiện tại. */
function defaultBackendUrl(): string {
  if (API_BASE_URL.startsWith("http")) return API_BASE_URL.replace(/\/$/, "")
  return `${window.location.origin}${API_BASE_URL}`.replace(/\/$/, "")
}

function curlSnippet(base: string, projectId: string): string {
  return `# Chạy toàn bộ test case của project rồi chờ kết quả
BASE="${base}"
RUN=$(curl -sS -X POST "$BASE/ci/projects/${projectId}/runs" \\
  -H "X-Api-Token: $AI_TEST_TOKEN" -H "Content-Type: application/json" \\
  -d '{"scope":"ALL","failOn":"API_FAIL"}')
EXEC_ID=$(echo "$RUN" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['executionId'])")

# Poll đến khi conclusion khác PENDING (tối đa ~10 phút)
for i in $(seq 1 120); do
  RESULT=$(curl -sS "$BASE/ci/projects/${projectId}/executions/$EXEC_ID" -H "X-Api-Token: $AI_TEST_TOKEN")
  CONCLUSION=$(echo "$RESULT" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['conclusion'])")
  [ "$CONCLUSION" != "PENDING" ] && break
  sleep 5
done

# Tải báo cáo JUnit rồi thoát mã lỗi nếu pipeline nên đỏ
curl -sS "$BASE/ci/projects/${projectId}/executions/$EXEC_ID/report?format=junit" \\
  -H "X-Api-Token: $AI_TEST_TOKEN" -o report.xml
[ "$CONCLUSION" = "PASSED" ]`
}

function githubSnippet(base: string, projectId: string): string {
  return `name: api-tests
on: [push]
jobs:
  api-tests:
    runs-on: ubuntu-latest
    steps:
      - name: Chạy test và lấy báo cáo
        env:
          AI_TEST_TOKEN: \${{ secrets.AI_TEST_TOKEN }}   # tạo secret từ token ở trên
        run: |
          BASE="${base}"
          RUN=$(curl -sS -X POST "$BASE/ci/projects/${projectId}/runs" \\
            -H "X-Api-Token: $AI_TEST_TOKEN" -H "Content-Type: application/json" -d '{"scope":"ALL"}')
          EXEC_ID=$(echo "$RUN" | jq -r .data.executionId)
          until [ "$(curl -sS "$BASE/ci/projects/${projectId}/executions/$EXEC_ID" -H "X-Api-Token: $AI_TEST_TOKEN" | jq -r .data.conclusion)" != "PENDING" ]; do sleep 5; done
          curl -sS "$BASE/ci/projects/${projectId}/executions/$EXEC_ID/report?format=junit" -H "X-Api-Token: $AI_TEST_TOKEN" -o report.xml
          test "$(curl -sS "$BASE/ci/projects/${projectId}/executions/$EXEC_ID" -H "X-Api-Token: $AI_TEST_TOKEN" | jq -r .data.conclusion)" = "PASSED"
      - uses: actions/upload-artifact@v4
        if: always()
        with: { name: junit-report, path: report.xml }`
}

/**
 * Module 17 (17d) - ví dụ gọi API CI. Token luôn là BIẾN/SECRET ($AI_TEST_TOKEN), KHÔNG bao giờ nhúng token thật vào đoạn mã (kể cả
 * ngay sau khi vừa tạo) để không bị sao chép nhầm vào repo.
 */
export function CiSnippets({ projectId }: { projectId: string }) {
  const [base, setBase] = useState(defaultBackendUrl)
  const blocks = [
    { title: "curl (bash)", text: curlSnippet(base, projectId) },
    { title: "GitHub Actions", text: githubSnippet(base, projectId) },
  ]
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="ci-backend-url">URL backend mà CI runner truy cập được</Label>
        <Input id="ci-backend-url" value={base} onChange={(e) => setBase(e.target.value)} />
        <p className="text-xs text-muted-foreground">
          Mặc định là URL mà trình duyệt đang dùng (thường là localhost khi dev) - đổi thành địa chỉ công khai/nội bộ của backend để CI runner gọi được.
        </p>
      </div>
      {blocks.map((block) => (
        <div key={block.title} className="rounded-lg border border-border">
          <div className="flex items-center justify-between border-b border-border px-3 py-1.5">
            <span className="text-sm font-medium">{block.title}</span>
            <CopyButton text={block.text} />
          </div>
          <pre className="max-h-72 overflow-auto p-3 text-xs leading-relaxed">{block.text}</pre>
        </div>
      ))}
      <p className="text-xs text-muted-foreground">
        Mã trả về của <code>conclusion</code>: <code>PASSED</code> / <code>FAILED</code> / <code>ERROR</code> / <code>PENDING</code>. Mặc định
        <code> failOn=API_FAIL</code>: Fail do môi trường/token/cấu hình test KHÔNG làm pipeline đỏ (có cảnh báo); dùng <code>ANY_FAIL</code> để nghiêm ngặt.
      </p>
    </div>
  )
}
