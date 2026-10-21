import { memo, useMemo } from "react"
import { ChevronDown, ChevronRight } from "lucide-react"

import { cn, METHOD_STYLES } from "@/lib/utils"
import { coverageCounts, scopeLabel, type EndpointCoverage } from "@/lib/coverage"
import { CoverageGapReport } from "@/components/coverage/CoverageGapReport"

interface CoverageEndpointRowProps {
  projectId: string
  endpoint: EndpointCoverage
  expanded: boolean
  onToggle: (endpointId: string) => void
  /** Thẻ được ghim ở đầu danh sách (mở từ liên kết ?endpoint=<id>): thêm nhãn và không dùng id trùng với dòng trong trang. */
  pinned?: boolean
}

/** 1 endpoint của trang Coverage. memo: mở/đóng 1 dòng hay gõ tìm kiếm không làm vẽ lại các dòng khác. */
export const CoverageEndpointRow = memo(function CoverageEndpointRow({ projectId, endpoint, expanded, onToggle, pinned }: CoverageEndpointRowProps) {
  const counts = useMemo(() => coverageCounts(endpoint), [endpoint])
  return (
    <div
      id={`coverage-endpoint-${endpoint.endpointId}`}
      className={cn("scroll-mt-4 rounded-lg border bg-card", pinned ? "border-primary/50" : "border-border")}
    >
      {pinned && <p className="px-4 pt-3 text-xs font-medium text-primary">Endpoint được chọn từ liên kết</p>}
      <button
        type="button"
        className="flex w-full flex-wrap items-center gap-x-3 gap-y-1.5 p-4 text-left"
        onClick={() => onToggle(endpoint.endpointId)}
        aria-expanded={expanded}
      >
        {expanded ? (
          <ChevronDown className="h-4 w-4 shrink-0 text-muted-foreground" />
        ) : (
          <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" />
        )}
        <span className={cn("shrink-0 rounded-md px-2 py-0.5 text-xs font-semibold", METHOD_STYLES[endpoint.method] ?? "bg-muted text-muted-foreground")}>
          {endpoint.method}
        </span>
        <span className="min-w-0 flex-1 truncate font-mono text-sm">{endpoint.path}</span>
        {endpoint.testCaseCount === 0 && (
          <span className="shrink-0 rounded-full bg-destructive/10 px-2 py-0.5 text-xs font-medium text-destructive">Chưa có test case nào</span>
        )}
        {scopeLabel(endpoint.scope) && (
          <span
            className="shrink-0 rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground"
            title="Chỉ các loại này được tính vào số mục cần kiểm. Mở endpoint để đổi phạm vi."
          >
            Phạm vi: {scopeLabel(endpoint.scope)}
          </span>
        )}
        <span className="shrink-0 text-xs text-muted-foreground">
          <span className="font-medium text-foreground">
            {counts.covered}/{counts.relevant} mục
          </span>{" "}
          · {counts.executed} đã chạy
          {counts.missing > 0 && <span className="text-amber-600 dark:text-amber-400"> · thiếu {counts.missing}</span>}
        </span>
      </button>

      {expanded && (
        <div className="border-t border-border p-4">
          {!endpoint.schemaReadable && (
            <p className="mb-3 rounded-md bg-destructive/10 p-2 text-xs text-destructive">
              Không đọc được định nghĩa OpenAPI (schema) của endpoint này - danh sách mục cần kiểm bên dưới KHÔNG đầy đủ, không nên tin số % hiện tại.
            </p>
          )}
          <CoverageGapReport projectId={projectId} endpoint={endpoint} />
        </div>
      )}
    </div>
  )
})
