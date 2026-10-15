import { useState } from "react"
import { Link, useParams } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { ArrowLeft, ChevronDown, ChevronRight } from "lucide-react"

import { Button } from "@/components/ui/button"
import { cn, METHOD_STYLES } from "@/lib/utils"
import { getProjectCoverage } from "@/lib/coverage"
import { CoverageDimensionGrid, CoverageSummary, RiskBadge } from "@/components/shared/CoverageMatrixChart"

/**
 * Module 15c - Coverage Matrix + Risk Engine. Toàn bộ số liệu tính tất định từ OpenAPI spec +
 * test case hiện có (không gọi AI) - xem EndpointCoverageService (backend).
 */
export function CoveragePage() {
  const { id } = useParams<{ id: string }>()
  const [expandedEndpointIds, setExpandedEndpointIds] = useState<Set<string>>(new Set())

  const { data, isLoading, isError } = useQuery({
    queryKey: ["project-coverage", id],
    queryFn: () => getProjectCoverage(id!),
    enabled: Boolean(id),
  })

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
      <Button variant="ghost" size="sm" nativeButton={false} render={<Link to={`/projects/${id}`} />}>
        <ArrowLeft className="h-4 w-4" />
        Quay lại Project
      </Button>

      <h1 className="mt-4 text-2xl font-semibold">Coverage Matrix</h1>
      <p className="mt-1 text-sm text-muted-foreground">
        Đo độ bao phủ theo đúng đặc tả OpenAPI (field bắt buộc, giá trị biên/enum, mã trạng thái, xác thực) đối chiếu với
        test case hiện có - tính tất định, không dùng AI để suy coverage.
      </p>

      {isLoading && <p className="mt-6 text-muted-foreground">Đang tính coverage...</p>}
      {isError && <p className="mt-6 text-destructive">Không tải được dữ liệu coverage, vui lòng thử lại.</p>}

      {data && (
        <div className="mt-6 flex flex-col gap-4">
          <CoverageSummary
            coveragePercent={data.overallCoveragePercent}
            riskWeightedCoveragePercent={data.overallRiskWeightedCoveragePercent}
          />

          {data.endpoints.length === 0 && (
            <div className="rounded-lg border border-dashed border-border p-8 text-center text-sm text-muted-foreground">
              Project chưa có endpoint nào.
            </div>
          )}

          <div className="flex flex-col gap-2">
            {data.endpoints.map((endpoint) => {
              const isExpanded = expandedEndpointIds.has(endpoint.endpointId)
              return (
                <div key={endpoint.endpointId} className="rounded-lg border border-border bg-card">
                  <button
                    type="button"
                    className="flex w-full items-center gap-3 p-4 text-left"
                    onClick={() => toggleExpanded(endpoint.endpointId)}
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
                    <RiskBadge level={endpoint.riskLevel} reasons={endpoint.riskReasons} />
                    <span className="shrink-0 text-xs font-medium text-muted-foreground">
                      {endpoint.coveragePercent}% coverage
                    </span>
                  </button>

                  {isExpanded && (
                    <div className="border-t border-border p-4">
                      {!endpoint.schemaReadable && (
                        <p className="mb-3 rounded-md bg-destructive/10 p-2 text-xs text-destructive">
                          Không đọc được định nghĩa OpenAPI (schema) của endpoint này - số liệu coverage/dimension bên dưới
                          KHÔNG đầy đủ, không nên tin số % hiện tại.
                        </p>
                      )}
                      {endpoint.riskReasons.length > 0 && (
                        <div className="mb-3 text-xs text-muted-foreground">
                          <span className="font-medium">Vì sao risk {endpoint.riskScore}/100: </span>
                          {endpoint.riskReasons.join("; ")}
                        </div>
                      )}
                      <CoverageDimensionGrid dimensions={endpoint.dimensions} />
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
