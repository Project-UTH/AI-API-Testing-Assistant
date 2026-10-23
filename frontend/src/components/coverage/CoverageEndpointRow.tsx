import { memo, useMemo } from "react"
import { Link } from "react-router-dom"
import { ChevronDown, ChevronRight } from "lucide-react"

import { cn, METHOD_STYLES } from "@/lib/utils"
import { coverageCounts, scopeLabel, type EndpointCoverage } from "@/lib/coverage"
import { CoverageGapReport } from "@/components/coverage/CoverageGapReport"
import { CoverageCountsText, RuleCoverageText, TestStateChip } from "@/components/coverage/TestStateChip"

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
        <TestStateChip state={endpoint.testState ?? (endpoint.testCaseCount === 0 ? "NOT_GENERATED" : null)} />
        {scopeLabel(endpoint.scope) && (
          <span
            className="shrink-0 rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground"
            title="Chỉ các loại này được tính vào số mục cần kiểm. Mở endpoint để đổi phạm vi."
          >
            Phạm vi: {scopeLabel(endpoint.scope)}
          </span>
        )}
        {endpoint.testState !== "NOT_GENERATED" && endpoint.testCaseCount > 0 && (
          <CoverageCountsText
            relevant={counts.relevant}
            covered={counts.covered}
            executed={counts.executed}
            certain={counts.certain}
            doubtful={counts.doubtful}
            missing={counts.missing}
            specGap={counts.specGap}
            baselineVerified={endpoint.baselineVerified}
          />
        )}
      </button>
      <p className="-mt-2 px-4 pb-3 pl-11">
        <RuleCoverageText rules={endpoint.rules} projectId={projectId} endpointId={endpoint.endpointId} />
      </p>
      {(endpoint.testState === "NOT_GENERATED" || (!endpoint.testState && endpoint.testCaseCount === 0)) && (
        <p className="-mt-2 px-4 pb-3 pl-11 text-xs text-muted-foreground">
          Endpoint chưa có test.{" "}
          <Link to={`/projects/${projectId}`} className="text-primary underline-offset-4 hover:underline">
            Sinh test case ở tab Endpoint →
          </Link>
        </p>
      )}

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
