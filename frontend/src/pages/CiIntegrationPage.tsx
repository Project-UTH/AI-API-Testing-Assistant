import { useParams } from "react-router-dom"

import { ApiTokensPanel } from "@/components/ci/ApiTokensPanel"
import { CiSnippets } from "@/components/ci/CiSnippets"

/** Module 17 (17d) - tab "Tích hợp CI": token API + ví dụ gọi từ pipeline. Báo cáo từng lượt xuất ở trang Kết quả thực thi. */
export function CiIntegrationPage() {
  const { id } = useParams<{ id: string }>()
  const projectId = id!
  return (
    <div className="flex flex-col gap-4">
      <div>
        <h2 className="text-lg font-semibold">Tích hợp CI/CD</h2>
        <p className="mt-1 text-sm text-muted-foreground">
          Cho pipeline (GitHub Actions, GitLab CI, Jenkins...) tự chạy bộ test của project, chờ kết quả và nhận báo cáo JUnit/HTML. Chạy tất định,
          không gọi AI, không tốn token.
        </p>
      </div>
      <ApiTokensPanel projectId={projectId} />
      <section className="rounded-lg border border-border bg-card p-4">
        <h3 className="mb-3 font-semibold">Cách gọi từ pipeline</h3>
        <CiSnippets projectId={projectId} />
      </section>
    </div>
  )
}
