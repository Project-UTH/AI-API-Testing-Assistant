import { useEffect, useMemo, useState } from "react"
import { Link, useParams, useSearchParams } from "react-router-dom"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { ArrowLeft, Bug, CheckCircle2, ChevronDown, ListChecks, Loader2, Pencil, XCircle } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { DropdownMenu, DropdownMenuContent, DropdownMenuTrigger } from "@/components/ui/dropdown-menu"
import { StatusBadge, RESULT_STATUS_STYLES, RESULT_STATUS_LABEL, RESULT_STATUS_ICON } from "@/components/shared/StatusBadge"
import { BugStatusBadge } from "@/components/shared/BugStatusBadge"
import { CreateBugReportDialog } from "@/components/bugreports/CreateBugReportDialog"
import { BugReportFormDialog } from "@/components/bugreports/BugReportFormDialog"
import { ApiError } from "@/lib/api"
import { generateBugReports, getBugReports, type BugReport, type GenerateBugReportsResult } from "@/lib/bugReports"
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
  const [selectionMode, setSelectionMode] = useState(false)
  const [selectedResultIds, setSelectedResultIds] = useState<Set<string>>(new Set())
  const [createBugResultId, setCreateBugResultId] = useState<string | null>(null)
  const [editingBugFromPanel, setEditingBugFromPanel] = useState<BugReport | null>(null)
  const [bugPanelOpen, setBugPanelOpen] = useState(false)

  const queryClient = useQueryClient()
  const executionQueryKey = ["executions", projectId, executionId]
  const bugReportsQueryKey = ["bug-reports", projectId]

  const { data: execution, isLoading, isError } = useQuery({
    queryKey: executionQueryKey,
    queryFn: () => getExecution(projectId, executionId!),
    enabled: Boolean(executionId),
    refetchInterval: (query) => {
      const status = query.state.data?.status
      return status === "PENDING" || status === "RUNNING" ? 1500 : false
    },
  })

  // Cùng queryKey với BugReportPage.tsx - nếu người dùng đã ghé trang Bug Report trước đó thì dùng
  // luôn cache, không fetch thừa. Lọc ra đúng các bug sinh từ CHÍNH lần chạy này (theo
  // sourceTestResultId) để hiện trong ô "Bug Report đã sinh" - không phụ thuộc bộ lọc ?endpointId=
  // đang xem trên màn hình, vì đây là toàn cảnh của cả execution.
  const { data: bugReportsPage } = useQuery({
    queryKey: bugReportsQueryKey,
    queryFn: () => getBugReports(projectId),
  })

  const generatedBugs = useMemo<BugReport[]>(() => {
    if (!bugReportsPage || !execution) return []
    const resultIds = new Set(execution.results.map((r) => r.id))
    return bugReportsPage.endpoints
      .flatMap((e) => e.testCases.flatMap((tc) => tc.bugs))
      .filter((bug) => resultIds.has(bug.sourceTestResultId))
  }, [bugReportsPage, execution])

  const [lastGenerateResult, setLastGenerateResult] = useState<GenerateBugReportsResult | null>(null)

  // "Sinh tất cả" luôn xét TOÀN BỘ kết quả Fail của lần chạy (không phụ thuộc bộ lọc ?endpointId=
  // đang xem) - backend tự bỏ qua kết quả không phải Fail hoặc đã có Bug Report rồi (idempotent,
  // bấm lại nhiều lần không tạo trùng). Invalidate lại query execution sau khi sinh xong để mỗi dòng
  // cập nhật ngay `existingBugReportId` - không cần người dùng tự F5 mới thấy dòng đã có bug.
  const generateAllMutation = useMutation({
    mutationFn: () => generateBugReports(projectId, executionId!, null),
    onSuccess: (data) => {
      setLastGenerateResult(data)
      queryClient.invalidateQueries({ queryKey: executionQueryKey })
      queryClient.invalidateQueries({ queryKey: bugReportsQueryKey })
    },
  })

  const generateSelectedMutation = useMutation({
    mutationFn: () => generateBugReports(projectId, executionId!, Array.from(selectedResultIds)),
    onSuccess: (data) => {
      setLastGenerateResult(data)
      setSelectionMode(false)
      setSelectedResultIds(new Set())
      queryClient.invalidateQueries({ queryKey: executionQueryKey })
      queryClient.invalidateQueries({ queryKey: bugReportsQueryKey })
    },
  })

  function toggleResultSelection(resultId: string, checked: boolean) {
    setSelectedResultIds((prev) => {
      const next = new Set(prev)
      if (checked) {
        next.add(resultId)
      } else {
        next.delete(resultId)
      }
      return next
    })
  }

  function exitSelectionMode() {
    setSelectionMode(false)
    setSelectedResultIds(new Set())
  }

  const filteredResults =
    execution?.results.filter((result) => !endpointFilter || result.endpointId === endpointFilter) ?? []
  const isFinished = execution && execution.status !== "PENDING" && execution.status !== "RUNNING"
  const hasGeneratableFailedResults = filteredResults.some((r) => r.status === "FAILED" && !r.existingBugReportId)

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

      <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-semibold">Kết quả thực thi</h1>
        <div className="flex flex-wrap items-center gap-2">
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
          {isFinished && generatedBugs.length > 0 && (
            <DropdownMenu open={bugPanelOpen} onOpenChange={setBugPanelOpen}>
              <DropdownMenuTrigger
                render={
                  <Button variant="outline" size="sm">
                    <Bug className="h-4 w-4" />
                    {`Bug Report đã sinh (${generatedBugs.length})`}
                  </Button>
                }
              />
              <DropdownMenuContent align="end" className="w-96">
                <div className="flex flex-col gap-1 p-1">
                  {generatedBugs.map((bug) => (
                    <div key={bug.id} className="flex items-center gap-2 rounded-md p-1.5 text-sm">
                      <code className="shrink-0 text-xs font-semibold text-muted-foreground">{bug.bugId}</code>
                      <BugStatusBadge status={bug.status} className="shrink-0 text-[0.7rem]" />
                      <span className="min-w-0 flex-1 truncate text-xs text-muted-foreground">{bug.summary}</span>
                      <Button
                        size="icon-xs"
                        variant="ghost"
                        title="Chỉnh sửa"
                        onClick={() => {
                          setBugPanelOpen(false)
                          setEditingBugFromPanel(bug)
                        }}
                      >
                        <Pencil className="h-3 w-3" />
                      </Button>
                    </div>
                  ))}
                </div>
              </DropdownMenuContent>
            </DropdownMenu>
          )}
          {isFinished && filteredResults.length > 0 && hasGeneratableFailedResults && (
            selectionMode ? (
              <>
                <Button variant="ghost" size="sm" onClick={exitSelectionMode}>
                  Huỷ
                </Button>
                <Button
                  size="sm"
                  disabled={selectedResultIds.size === 0 || generateSelectedMutation.isPending}
                  onClick={() => generateSelectedMutation.mutate()}
                >
                  <Bug className="h-4 w-4" />
                  {generateSelectedMutation.isPending ? "Đang sinh..." : `Sinh đã chọn (${selectedResultIds.size})`}
                </Button>
              </>
            ) : (
              <>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={generateAllMutation.isPending}
                  onClick={() => generateAllMutation.mutate()}
                >
                  <Bug className="h-4 w-4" />
                  {generateAllMutation.isPending ? "Đang sinh..." : "Sinh tất cả Bug Report"}
                </Button>
                <Button variant="outline" size="sm" onClick={() => setSelectionMode(true)}>
                  <ListChecks className="h-4 w-4" />
                  Sinh theo lựa chọn
                </Button>
              </>
            )
          )}
        </div>
      </div>
      {(generateAllMutation.isError || generateSelectedMutation.isError) && (
        <p className="mt-2 text-sm text-destructive">
          {generateAllMutation.error instanceof ApiError
            ? generateAllMutation.error.message
            : generateSelectedMutation.error instanceof ApiError
              ? generateSelectedMutation.error.message
              : "Không sinh được Bug Report, vui lòng thử lại"}
        </p>
      )}
      {lastGenerateResult && (
        <div className="mt-2 flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          <span>
            Đã tạo {lastGenerateResult.created.length} Bug Report
            {lastGenerateResult.skippedCount > 0 &&
              ` (bỏ qua ${lastGenerateResult.skippedCount} dòng đã có Bug Report hoặc không phải Fail)`}
            {lastGenerateResult.created.length > 0 && ":"}
          </span>
          {/* Mỗi bug vừa sinh là 1 ô bấm được, nhảy thẳng tới đúng bug đó ở trang Bug Report - tái
              dùng cơ chế deep-link ?bugReportId= đã có sẵn (BugReportPage.tsx tự mở đúng dialog sửa
              khi thấy param này), không cần logic mới ở trang đích. */}
          {lastGenerateResult.created.map((bug) => (
            <Link
              key={bug.id}
              to={`/projects/${projectId}/bug-reports?bugReportId=${bug.id}`}
              className="rounded-md border border-border bg-card px-2 py-0.5 font-mono text-xs text-primary hover:underline"
            >
              {bug.bugId}
            </Link>
          ))}
        </div>
      )}

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
            <li key={result.id} className="rounded-md border border-border">
              <div className="flex w-full items-start gap-2 p-3 sm:items-center">
                {selectionMode &&
                  (result.status === "FAILED" && !result.existingBugReportId ? (
                    <Checkbox
                      className="mt-1.5 sm:mt-0"
                      checked={selectedResultIds.has(result.id)}
                      onCheckedChange={(checked) => toggleResultSelection(result.id, checked === true)}
                    />
                  ) : (
                    <div className="size-4 shrink-0" />
                  ))}
                <button
                  type="button"
                  onClick={() => setExpandedId(isExpanded ? null : result.testCaseId)}
                  className="flex min-w-0 flex-1 flex-col gap-2 text-left sm:flex-row sm:items-center"
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
                {/* Sibling của <button>, KHÔNG lồng bên trong (tránh <a> trong <button>, cùng lý do
                    đã né với Checkbox) - báo rõ dòng này đã có Bug Report, bấm vào nhảy thẳng tới đó. */}
                {result.existingBugId && (
                  <Link
                    to={`/projects/${projectId}/bug-reports?bugReportId=${result.existingBugReportId}`}
                    className="shrink-0 self-start rounded-md border border-border bg-card px-2 py-0.5 font-mono text-xs text-primary hover:underline sm:self-center"
                    title="Đã có Bug Report - bấm để xem"
                  >
                    {result.existingBugId}
                  </Link>
                )}
              </div>

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
                  {result.status === "FAILED" && !result.existingBugReportId && (
                    <Button size="xs" variant="outline" className="self-end" onClick={() => setCreateBugResultId(result.id)}>
                      Báo lỗi
                    </Button>
                  )}
                </div>
              )}
            </li>
          )
        })}
      </ul>

      <CreateBugReportDialog
        open={createBugResultId !== null}
        onOpenChange={(open) => !open && setCreateBugResultId(null)}
        projectId={projectId}
        sourceTestResultId={createBugResultId}
        onCreated={() => queryClient.invalidateQueries({ queryKey: executionQueryKey })}
      />
      <BugReportFormDialog
        open={editingBugFromPanel !== null}
        onOpenChange={(open) => !open && setEditingBugFromPanel(null)}
        projectId={projectId}
        bugReport={editingBugFromPanel}
      />
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
