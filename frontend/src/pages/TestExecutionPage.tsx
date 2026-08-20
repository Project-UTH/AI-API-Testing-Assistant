import { useEffect, useState } from "react"
import { Link, useParams, useSearchParams } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { ArrowLeft, CheckCircle2, ChevronDown, Loader2, XCircle } from "lucide-react"

import { Button } from "@/components/ui/button"
import { StatusBadge, RESULT_STATUS_STYLES, RESULT_STATUS_LABEL, RESULT_STATUS_ICON } from "@/components/shared/StatusBadge"
import { cn, formatResponseBody } from "@/lib/utils"
import { getExecution, type ExecutionStatus, type TestResult, type TestResultStatus } from "@/lib/executions"

// Thứ tự cố định (khớp RESULT_STATUS_LABEL bên dưới) - dùng để vẽ donut theo 1 thứ tự nhất quán,
// không lệ thuộc thứ tự dữ liệu trả về.
const STATUS_ORDER: TestResultStatus[] = ["PASSED", "FAILED", "ERROR", "BLOCKED", "SKIPPED"]

const EXECUTION_STATUS_LABEL: Record<ExecutionStatus, string> = {
  PENDING: "Đang chờ",
  RUNNING: "Đang chạy",
  COMPLETED: "Đã xong",
  FAILED: "Lỗi hệ thống",
}

const EXECUTION_STATUS_STYLES: Record<ExecutionStatus, string> = {
  PENDING: "bg-muted text-muted-foreground",
  RUNNING: "bg-blue-500/10 text-blue-600 dark:text-blue-400",
  COMPLETED: "bg-green-500/10 text-green-600 dark:text-green-400",
  FAILED: "bg-destructive/10 text-destructive",
}

export function TestExecutionPage() {
  const { id, executionId } = useParams<{ id: string; executionId: string }>()
  const projectId = id!
  const [searchParams] = useSearchParams()
  const endpointFilter = searchParams.get("endpointId")
  const [expandedId, setExpandedId] = useState<string | null>(null)

  const { data: execution, isLoading, isError } = useQuery({
    queryKey: ["executions", projectId, executionId],
    queryFn: () => getExecution(projectId, executionId!),
    enabled: Boolean(executionId),
    refetchInterval: (query) => {
      const status = query.state.data?.status
      return status === "PENDING" || status === "RUNNING" ? 1500 : false
    },
  })

  const filteredResults =
    execution?.results.filter((result) => !endpointFilter || result.endpointId === endpointFilter) ?? []
  const isFinished = execution && execution.status !== "PENDING" && execution.status !== "RUNNING"

  return (
    <div>
      <Button
        variant="ghost"
        size="sm"
        nativeButton={false}
        render={<Link to={`/projects/${projectId}/test-cases`} />}
      >
        <ArrowLeft className="h-4 w-4" />
        Quay lại Test Case
      </Button>

      <div className="mt-4 flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Kết quả thực thi</h1>
        {execution && (
          <span
            className={cn(
              "flex items-center gap-1.5 rounded-md px-2.5 py-1 text-sm font-medium",
              EXECUTION_STATUS_STYLES[execution.status]
            )}
          >
            {(execution.status === "PENDING" || execution.status === "RUNNING") && (
              <Loader2 className="h-3.5 w-3.5 animate-spin" />
            )}
            {EXECUTION_STATUS_LABEL[execution.status]}
          </span>
        )}
      </div>

      {isLoading && <p className="mt-6 text-muted-foreground">Đang tải...</p>}
      {isError && <p className="mt-6 text-destructive">Không tải được kết quả thực thi.</p>}

      {execution && execution.autoIncludedTestCaseIds.length > 0 && (
        <p className="mt-4 rounded-md border border-border bg-muted/50 p-3 text-sm text-muted-foreground">
          {execution.autoIncludedTestCaseIds.length} test case phụ trợ đã tự động chạy kèm (dữ liệu
          nguồn cho Test Data Chaining).
        </p>
      )}

      {isFinished && filteredResults.length === 0 && (
        <p className="mt-6 text-muted-foreground">Không có kết quả nào.</p>
      )}

      {isFinished && filteredResults.length > 0 && <ExecutionSummaryDashboard results={filteredResults} />}

      <ul className="mt-4 flex flex-col gap-2">
        {filteredResults.map((result) => {
          const isExpanded = expandedId === result.testCaseId
          const formattedBody = formatResponseBody(result.responseBody)
          return (
            <li key={result.testCaseId} className="rounded-md border border-border">
              <button
                type="button"
                onClick={() => setExpandedId(isExpanded ? null : result.testCaseId)}
                className="flex w-full flex-col gap-2 p-3 text-left sm:flex-row sm:items-center"
              >
                <StatusBadge status={result.status} />
                <span className="min-w-0 flex-1 truncate text-sm">{result.testCaseName}</span>
                <span className="shrink-0 text-xs text-muted-foreground">
                  Kỳ vọng {result.expectedStatus} → thực tế {result.responseStatus ?? "—"}
                </span>
                {result.errorMessage && (
                  <span className="shrink-0 truncate text-xs text-destructive sm:max-w-64" title={result.errorMessage}>
                    {result.errorMessage}
                  </span>
                )}
                <ChevronDown
                  className={cn("h-4 w-4 shrink-0 text-muted-foreground transition-transform", isExpanded && "rotate-180")}
                />
              </button>

              {isExpanded && (
                <div className="flex flex-col gap-3 border-t border-border p-3">
                  {result.errorMessage && (
                    <div>
                      <p className="mb-1 text-xs font-medium text-muted-foreground">Lỗi</p>
                      <p className="text-xs whitespace-pre-wrap text-destructive">{result.errorMessage}</p>
                    </div>
                  )}
                  <div>
                    <p className="mb-1 text-xs font-medium text-muted-foreground">Nội dung phản hồi</p>
                    {formattedBody ? (
                      <pre className="max-h-80 overflow-auto rounded-md bg-muted p-2 text-xs whitespace-pre-wrap break-all">
                        {formattedBody}
                      </pre>
                    ) : (
                      <p className="text-xs text-muted-foreground">Không có nội dung phản hồi.</p>
                    )}
                  </div>
                  {result.assertionResults.length > 0 && (
                    <div>
                      <p className="mb-1 text-xs font-medium text-muted-foreground">Assertions</p>
                      <ul className="flex flex-col gap-1.5">
                        {result.assertionResults.map((a, index) => (
                          <li
                            key={`${a.jsonPath}-${a.operator}-${index}`}
                            className={cn(
                              "flex flex-wrap items-center gap-2 rounded-md px-2 py-1 text-xs",
                              a.passed
                                ? "bg-green-500/10 text-green-600 dark:text-green-400"
                                : "bg-destructive/10 text-destructive"
                            )}
                          >
                            {a.passed ? (
                              <CheckCircle2 className="h-3.5 w-3.5 shrink-0" />
                            ) : (
                              <XCircle className="h-3.5 w-3.5 shrink-0" />
                            )}
                            <code className="shrink-0 font-semibold">{a.jsonPath}</code>
                            <span>{a.operator}</span>
                            {a.operator !== "EXISTS" && (
                              <span className="min-w-0 truncate">
                                kỳ vọng "{a.expectedValue}", thực tế "{a.actualValue ?? "—"}"
                              </span>
                            )}
                          </li>
                        ))}
                      </ul>
                    </div>
                  )}
                </div>
              )}
            </li>
          )
        })}
      </ul>
    </div>
  )
}

const RING_SIZE = 172
const RING_RADIUS = 66
const RING_STROKE_WIDTH = 18
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS

function ExecutionSummaryDashboard({ results }: { results: TestResult[] }) {
  const total = results.length
  const countByStatus = results.reduce<Partial<Record<TestResultStatus, number>>>((acc, result) => {
    acc[result.status] = (acc[result.status] ?? 0) + 1
    return acc
  }, {})
  const presentStatuses = STATUS_ORDER.filter((status) => (countByStatus[status] ?? 0) > 0)
  const passCount = countByStatus.PASSED ?? 0
  const passRate = total > 0 ? Math.round((passCount / total) * 100) : 0

  // Vẽ ring "nở ra" 1 lần khi mount (fromZero -> giá trị thật) - tôn trọng prefers-reduced-motion
  // bằng motion-reduce:transition-none ở className của từng <circle> nên chỉ tắt animation, kết
  // quả cuối vẫn đúng ngay lập tức, không cần nhánh code riêng.
  const [grown, setGrown] = useState(false)
  useEffect(() => {
    const frame = requestAnimationFrame(() => setGrown(true))
    return () => cancelAnimationFrame(frame)
  }, [])

  let cumulativeOffset = 0
  const segments = presentStatuses.map((status) => {
    const count = countByStatus[status] ?? 0
    const fraction = count / total
    const dash = fraction * RING_CIRCUMFERENCE
    const segment = { status, count, dash, offset: cumulativeOffset }
    cumulativeOffset += dash
    return segment
  })

  return (
    <div className="mt-4 flex animate-in flex-col gap-5 rounded-xl bg-card p-5 shadow-sm ring-1 ring-foreground/10 duration-500 fade-in-0 slide-in-from-bottom-2 motion-reduce:animate-none sm:flex-row sm:items-center sm:gap-8">
      <p className="text-xs font-medium tracking-wide text-muted-foreground uppercase sm:hidden">
        Tổng quan kết quả
      </p>

      <div className="relative shrink-0 self-center" style={{ width: RING_SIZE, height: RING_SIZE }}>
        <svg width={RING_SIZE} height={RING_SIZE} viewBox={`0 0 ${RING_SIZE} ${RING_SIZE}`}>
          <circle
            cx={RING_SIZE / 2}
            cy={RING_SIZE / 2}
            r={RING_RADIUS}
            fill="none"
            strokeWidth={RING_STROKE_WIDTH}
            className="text-border stroke-current"
          />
          <g className="drop-shadow-sm" transform={`rotate(-90 ${RING_SIZE / 2} ${RING_SIZE / 2})`}>
            {segments.map(({ status, count, dash, offset }) => (
              <circle
                key={status}
                cx={RING_SIZE / 2}
                cy={RING_SIZE / 2}
                r={RING_RADIUS}
                fill="none"
                strokeWidth={RING_STROKE_WIDTH}
                strokeLinecap="round"
                strokeDasharray={grown ? `${dash} ${RING_CIRCUMFERENCE - dash}` : `0 ${RING_CIRCUMFERENCE}`}
                strokeDashoffset={-offset}
                className={cn(
                  "stroke-current transition-[stroke-dasharray] duration-700 ease-out motion-reduce:transition-none",
                  RESULT_STATUS_STYLES[status]
                )}
              >
                <title>
                  {RESULT_STATUS_LABEL[status]}: {count}
                </title>
              </circle>
            ))}
          </g>
        </svg>
        <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
          <span className="text-4xl font-semibold">{passRate}%</span>
          <span className="text-xs text-muted-foreground">hoàn thành</span>
        </div>
      </div>

      <div className="flex flex-1 flex-col gap-4">
        <p className="hidden text-xs font-medium tracking-wide text-muted-foreground uppercase sm:block">
          Tổng quan kết quả
        </p>

        <div className="grid grid-cols-2 gap-x-6 gap-y-4">
          <div>
            <p className="text-xs text-muted-foreground">Tổng test case</p>
            <p className="text-2xl font-semibold">{total}</p>
          </div>
          <div>
            <p className="text-xs text-muted-foreground">Tiến độ hoàn thành</p>
            <p className="text-2xl font-semibold">{passRate}%</p>
          </div>
        </div>

        <div className="flex flex-wrap gap-2 border-t border-border pt-4">
          {presentStatuses.map((status) => {
            const Icon = RESULT_STATUS_ICON[status]
            const count = countByStatus[status] ?? 0
            return (
              <span
                key={status}
                className={cn(
                  "flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium",
                  RESULT_STATUS_STYLES[status]
                )}
              >
                <Icon className="h-3.5 w-3.5" />
                {RESULT_STATUS_LABEL[status]}
                <span>{count}</span>
                <span className="text-[10px] opacity-70">({Math.round((count / total) * 100)}%)</span>
              </span>
            )
          })}
        </div>
      </div>
    </div>
  )
}
