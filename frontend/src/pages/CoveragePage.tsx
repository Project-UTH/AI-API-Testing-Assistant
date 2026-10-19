import { useEffect, useState } from "react"
import { useParams, useSearchParams } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { ChevronDown, ChevronRight } from "lucide-react"

import { cn, METHOD_STYLES } from "@/lib/utils"
import { coverageCounts, getProjectCoverage, scopeLabel } from "@/lib/coverage"
import { CoverageSummary } from "@/components/shared/CoverageMatrixChart"
import { CoverageGapReport } from "@/components/coverage/CoverageGapReport"

/**
 * Module 15c - Coverage Matrix. Toàn bộ số liệu tính tất định từ OpenAPI spec +
 * test case hiện có (không gọi AI) - xem EndpointCoverageService (backend).
 * Module 15j - Coverage Gap Analysis: mỗi endpoint chỉ rõ còn thiếu mục nào, vì sao cần, bằng chứng, và
 * bổ sung ngay (hệ thống tự tạo, 0 token).
 */
export function CoveragePage() {
  const { id } = useParams<{ id: string }>()
  // ?endpoint=<id> (link từ trang Project) - mở sẵn đúng endpoint đó và cuộn tới.
  const focusEndpointId = useSearchParams()[0].get("endpoint")
  const [expandedEndpointIds, setExpandedEndpointIds] = useState<Set<string>>(
    new Set(focusEndpointId ? [focusEndpointId] : [])
  )

  const { data, isLoading, isError } = useQuery({
    queryKey: ["project-coverage", id],
    queryFn: () => getProjectCoverage(id!),
    enabled: Boolean(id),
  })

  const dataLoaded = Boolean(data)
  useEffect(() => {
    if (focusEndpointId && dataLoaded) {
      document.getElementById(`coverage-endpoint-${focusEndpointId}`)?.scrollIntoView({ block: "start" })
    }
  }, [focusEndpointId, dataLoaded])

  function toggleExpanded(endpointId: string) {
    setExpandedEndpointIds((prev) => {
      const next = new Set(prev)
      if (next.has(endpointId)) {
        next.delete(endpointId)
      } else {
        next.add(endpointId)
      }
      return next
    })
  }

  return (
    <div>
      <h2 className="text-lg font-semibold">Coverage Matrix</h2>
      <p className="mt-1 text-sm text-muted-foreground">
        Các tình huống cần kiểm tra được suy ra từ spec OpenAPI, đối chiếu với test case hiện có. Tính tất định, không dùng AI.
      </p>

      {isLoading && <p className="mt-6 text-muted-foreground">Đang tính coverage...</p>}
      {isError && <p className="mt-6 text-destructive">Không tải được dữ liệu coverage, vui lòng thử lại.</p>}

      {data && (
        <div className="mt-6 flex flex-col gap-4">
          <CoverageSummary
            coveragePercent={data.overallCoveragePercent}
            executedPercent={data.overallExecutedPercent}
          />

          {data.endpoints.length === 0 && (
            <div className="rounded-lg border border-dashed border-border p-8 text-center text-sm text-muted-foreground">
              Project chưa có endpoint nào.
            </div>
          )}

          <div className="flex flex-col gap-2">
            {data.endpoints.map((endpoint) => {
              const isExpanded = expandedEndpointIds.has(endpoint.endpointId)
              const counts = coverageCounts(endpoint)
              return (
                <div
                  key={endpoint.endpointId}
                  id={`coverage-endpoint-${endpoint.endpointId}`}
                  className="scroll-mt-4 rounded-lg border border-border bg-card"
                >
                  <button
                    type="button"
                    className="flex w-full flex-wrap items-center gap-x-3 gap-y-1.5 p-4 text-left"
                    onClick={() => toggleExpanded(endpoint.endpointId)}
                    aria-expanded={isExpanded}
                  >
                    {isExpanded ? (
                      <ChevronDown className="h-4 w-4 shrink-0 text-muted-foreground" />
                    ) : (
                      <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" />
                    )}
                    <span
                      className={cn(
                        "shrink-0 rounded-md px-2 py-0.5 text-xs font-semibold",
                        METHOD_STYLES[endpoint.method] ?? "bg-muted text-muted-foreground"
                      )}
                    >
                      {endpoint.method}
                    </span>
                    <span className="min-w-0 flex-1 truncate font-mono text-sm">{endpoint.path}</span>
                    {endpoint.testCaseCount === 0 && (
                      <span className="shrink-0 rounded-full bg-destructive/10 px-2 py-0.5 text-xs font-medium text-destructive">
                        Chưa có test case nào
                      </span>
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
                      {counts.missing > 0 && (
                        <span className="text-amber-600 dark:text-amber-400"> · thiếu {counts.missing}</span>
                      )}
                    </span>
                  </button>

                  {isExpanded && (
                    <div className="border-t border-border p-4">
                      {!endpoint.schemaReadable && (
                        <p className="mb-3 rounded-md bg-destructive/10 p-2 text-xs text-destructive">
                          Không đọc được định nghĩa OpenAPI (schema) của endpoint này - danh sách mục cần kiểm bên dưới
                          KHÔNG đầy đủ, không nên tin số % hiện tại.
                        </p>
                      )}
                      <CoverageGapReport projectId={id!} endpoint={endpoint} />
                    </div>
                  )}
                </div>
              )
            })}
          </div>
        </div>
      )}
    </div>
  )
}
