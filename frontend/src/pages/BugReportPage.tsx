import { useEffect, useState } from "react"
import { Link, useParams, useSearchParams } from "react-router-dom"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { ArrowLeft, CheckCircle2, ChevronDown, Download, Pencil, Trash2, XCircle } from "lucide-react"

import { Button } from "@/components/ui/button"
import { StatusBadge } from "@/components/shared/StatusBadge"
import { BUG_PRIORITY_LABEL, BugStatusBadge } from "@/components/shared/BugStatusBadge"
import { BugReportFormDialog } from "@/components/bugreports/BugReportFormDialog"
import { CreateBugReportDialog } from "@/components/bugreports/CreateBugReportDialog"
import { DeleteBugReportDialog } from "@/components/bugreports/DeleteBugReportDialog"
import { ApiError } from "@/lib/api"
import { cn, formatDateTime, formatResponseBody, METHOD_STYLES } from "@/lib/utils"
import {
  confirmCloseBugReport,
  dismissCloseSuggestion,
  exportAllBugReports,
  getBugReports,
  getRunHistory,
  type BugDashboardSummary,
  type BugPriority,
  type BugReport,
  type EndpointBugSummary,
  type TestCaseBugSummary,
} from "@/lib/bugReports"

const PRIORITY_ORDER: BugPriority[] = ["BLOCKER", "CRITICAL", "MAJOR", "MINOR", "TRIVIAL"]

function toggleInSet(set: Set<string>, value: string): Set<string> {
  const next = new Set(set)
  if (next.has(value)) {
    next.delete(value)
  } else {
    next.add(value)
  }
  return next
}

export function BugReportPage() {
  const { id } = useParams<{ id: string }>()
  const projectId = id!
  const [searchParams, setSearchParams] = useSearchParams()
  const [expandedEndpointIds, setExpandedEndpointIds] = useState<Set<string>>(new Set())
  const [expandedTestCaseIds, setExpandedTestCaseIds] = useState<Set<string>>(new Set())
  const [editingBug, setEditingBug] = useState<BugReport | null>(null)
  const [deletingBug, setDeletingBug] = useState<BugReport | null>(null)
  const [createBugResultId, setCreateBugResultId] = useState<string | null>(null)
  const [deepLinkNotFound, setDeepLinkNotFound] = useState(false)

  const { data, isLoading, isError } = useQuery({
    queryKey: ["bug-reports", projectId],
    queryFn: () => getBugReports(projectId),
  })

  const exportAllMutation = useMutation({
    mutationFn: () => exportAllBugReports(projectId, "BugReports.xlsx"),
  })

  // Đến từ "Xem chi tiết" ở Lịch sử tổng (?bugReportId=...) - tự mở thẳng dialog sửa đúng bug đó
  // thay vì chỉ đưa về trang danh sách rồi bắt người dùng tự tìm. Xoá param khỏi URL ngay sau khi xử
  // lý (dù tìm thấy hay không) để refresh/đóng dialog không lặp lại hành vi này.
  useEffect(() => {
    const bugReportId = searchParams.get("bugReportId")
    if (!bugReportId || !data) return

    for (const endpoint of data.endpoints) {
      for (const testCase of endpoint.testCases) {
        const found = testCase.bugs.find((b) => b.id === bugReportId)
        if (found) {
          setEditingBug(found)
          setExpandedEndpointIds((prev) => new Set(prev).add(endpoint.endpointId))
          setExpandedTestCaseIds((prev) => new Set(prev).add(testCase.testCaseId))
          setSearchParams((prev) => {
            const next = new URLSearchParams(prev)
            next.delete("bugReportId")
            return next
          }, { replace: true })
          return
        }
      }
    }
    setDeepLinkNotFound(true)
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      next.delete("bugReportId")
      return next
    }, { replace: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data])

  return (
    <div>
      <Button variant="ghost" size="sm" nativeButton={false} render={<Link to={`/projects/${projectId}`} />}>
        <ArrowLeft className="h-4 w-4" />
        Quay lại Project
      </Button>

      <div className="mt-4 flex items-center justify-between gap-4">
        <h1 className="text-2xl font-semibold">Bug Report</h1>
        <Button
          variant="outline"
          size="sm"
          disabled={exportAllMutation.isPending || !data || data.summary.totalCount === 0}
          onClick={() => exportAllMutation.mutate()}
        >
          <Download className="h-4 w-4" />
          {exportAllMutation.isPending ? "Đang xuất..." : "Xuất tất cả (Excel)"}
        </Button>
      </div>
      {exportAllMutation.isError && (
        <p className="mt-2 text-sm text-destructive">
          {exportAllMutation.error instanceof ApiError ? exportAllMutation.error.message : "Không xuất được file, vui lòng thử lại"}
        </p>
      )}
      {deepLinkNotFound && (
        <p className="mt-2 text-sm text-destructive">Không tìm thấy bug report này — có thể đã bị xoá.</p>
      )}

      {isLoading && <p className="mt-6 text-muted-foreground">Đang tải...</p>}
      {isError && <p className="mt-6 text-destructive">Không tải được Bug Report, vui lòng thử lại.</p>}

      {data && (
        <>
          <BugDashboard summary={data.summary} />

          {data.endpoints.length === 0 ? (
            <p className="mt-6 text-muted-foreground">
              Chưa có test case nào trong project này.
            </p>
          ) : (
            <div className="mt-6 flex flex-col gap-4">
              {data.endpoints.map((endpoint) => (
                <EndpointRow
                  key={endpoint.endpointId}
                  endpoint={endpoint}
                  projectId={projectId}
                  isExpanded={expandedEndpointIds.has(endpoint.endpointId)}
                  onToggle={() => setExpandedEndpointIds((prev) => toggleInSet(prev, endpoint.endpointId))}
                  expandedTestCaseIds={expandedTestCaseIds}
                  onToggleTestCase={(testCaseId) =>
                    setExpandedTestCaseIds((prev) => toggleInSet(prev, testCaseId))
                  }
                  onEditBug={setEditingBug}
                  onDeleteBug={setDeletingBug}
                  onCreateBug={setCreateBugResultId}
                />
              ))}
            </div>
          )}
        </>
      )}

      <BugReportFormDialog
        open={editingBug !== null}
        onOpenChange={(open) => !open && setEditingBug(null)}
        projectId={projectId}
        bugReport={editingBug}
      />
      <DeleteBugReportDialog
        open={deletingBug !== null}
        onOpenChange={(open) => !open && setDeletingBug(null)}
        projectId={projectId}
        bugReport={deletingBug}
      />
      <CreateBugReportDialog
        open={createBugResultId !== null}
        onOpenChange={(open) => !open && setCreateBugResultId(null)}
        projectId={projectId}
        sourceTestResultId={createBugResultId}
      />
    </div>
  )
}

function StatCard({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-lg border border-border p-4">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="mt-1 text-2xl font-semibold">{value}</p>
    </div>
  )
}

function BugDashboard({ summary }: { summary: BugDashboardSummary }) {
  const maxPriorityCount = Math.max(1, ...PRIORITY_ORDER.map((p) => summary.countByPriority[p] ?? 0))

  return (
    <div className="mt-4 flex flex-col gap-4">
      <div className="grid grid-cols-3 gap-4">
        <StatCard label="Đang mở" value={summary.openCount} />
        <StatCard label="Đã đóng" value={summary.closedCount} />
        <StatCard label="Tổng số" value={summary.totalCount} />
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <div className="rounded-lg border border-border p-4">
          <p className="text-sm font-medium">Theo Priority</p>
          {summary.totalCount === 0 ? (
            <p className="mt-3 text-xs text-muted-foreground">Chưa có dữ liệu.</p>
          ) : (
            <div className="mt-3 flex flex-col gap-2">
              {PRIORITY_ORDER.map((priority) => {
                const count = summary.countByPriority[priority] ?? 0
                return (
                  <div key={priority} className="flex items-center gap-2">
                    <span className="w-28 shrink-0 text-xs text-muted-foreground">{BUG_PRIORITY_LABEL[priority]}</span>
                    <div className="h-2 flex-1 overflow-hidden rounded-full bg-muted">
                      <div
                        className="h-full rounded-full bg-primary"
                        style={{ width: `${(count / maxPriorityCount) * 100}%` }}
                      />
                    </div>
                    <span className="w-6 shrink-0 text-right text-xs font-medium">{count}</span>
                  </div>
                )
              })}
            </div>
          )}
        </div>

        <div className="rounded-lg border border-border p-4">
          <p className="text-sm font-medium">Theo Component</p>
          {summary.countByComponent.length === 0 ? (
            <p className="mt-3 text-xs text-muted-foreground">Chưa có dữ liệu.</p>
          ) : (
            <ul className="mt-3 flex flex-col gap-2">
              {summary.countByComponent.map((c) => (
                <li key={c.endpointId} className="flex items-center gap-2 text-xs">
                  <span
                    className={cn(
                      "w-14 shrink-0 rounded-md px-1.5 py-0.5 text-center font-semibold",
                      METHOD_STYLES[c.endpointMethod] ?? "bg-muted text-muted-foreground"
                    )}
                  >
                    {c.endpointMethod}
                  </span>
                  <span className="min-w-0 flex-1 truncate font-mono">{c.endpointPath}</span>
                  <span className="shrink-0 font-medium">{c.count} bug</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </div>
  )
}

function EndpointRow({
  endpoint,
  projectId,
  isExpanded,
  onToggle,
  expandedTestCaseIds,
  onToggleTestCase,
  onEditBug,
  onDeleteBug,
  onCreateBug,
}: {
  endpoint: EndpointBugSummary
  projectId: string
  isExpanded: boolean
  onToggle: () => void
  expandedTestCaseIds: Set<string>
  onToggleTestCase: (testCaseId: string) => void
  onEditBug: (bug: BugReport) => void
  onDeleteBug: (bug: BugReport) => void
  onCreateBug: (testResultId: string) => void
}) {
  return (
    <div className="rounded-lg border border-border p-4">
      <button type="button" onClick={onToggle} className="flex w-full items-center gap-3 text-left">
        <span
          className={cn(
            "w-16 shrink-0 rounded-md px-2 py-0.5 text-center text-xs font-semibold",
            METHOD_STYLES[endpoint.endpointMethod] ?? "bg-muted text-muted-foreground"
          )}
        >
          {endpoint.endpointMethod}
        </span>
        <span className="min-w-0 flex-1 truncate font-mono text-sm">{endpoint.endpointPath}</span>
        <span className="shrink-0 text-xs text-muted-foreground">{endpoint.testCases.length} test case</span>
        <ChevronDown className={cn("h-4 w-4 shrink-0 text-muted-foreground transition-transform", isExpanded && "rotate-180")} />
      </button>

      {isExpanded && (
        <div className="mt-4 ml-1 flex flex-col gap-3 border-l border-border pl-5">
          {endpoint.testCases.map((testCase) => (
            <TestCaseRow
              key={testCase.testCaseId}
              testCase={testCase}
              projectId={projectId}
              isExpanded={expandedTestCaseIds.has(testCase.testCaseId)}
              onToggle={() => onToggleTestCase(testCase.testCaseId)}
              onEditBug={onEditBug}
              onDeleteBug={onDeleteBug}
              onCreateBug={onCreateBug}
            />
          ))}
        </div>
      )}
    </div>
  )
}

function TestCaseRow({
  testCase,
  projectId,
  isExpanded,
  onToggle,
  onEditBug,
  onDeleteBug,
  onCreateBug,
}: {
  testCase: TestCaseBugSummary
  projectId: string
  isExpanded: boolean
  onToggle: () => void
  onEditBug: (bug: BugReport) => void
  onDeleteBug: (bug: BugReport) => void
  onCreateBug: (testResultId: string) => void
}) {
  const pendingBugs = testCase.bugs.filter((b) => b.pendingCloseSuggestion)

  return (
    <div className="rounded-md border border-border p-3">
      <p className="text-base font-semibold text-foreground">{testCase.testCaseName}</p>

      {testCase.bugs.length > 0 && (
        <div className="mt-2 flex flex-col gap-2">
          {testCase.bugs.map((bug) => (
            // Nền vàng nhạt (không tô đậm như bản trước - tránh rối mắt khi nhiều bug xếp chồng)
            // + vạch màu bên trái để phân biệt đây là 1 bug report thật, không lẫn với phần còn lại
            // của dòng test case. Chỉ hiện badge trạng thái (nổi bật nhất, hay đổi nhất) -
            // severity/priority/frequency vẫn sửa được qua nút bút, không hiện ở đây cho gọn.
            // sourceOccurredAt là thời điểm LẦN CHẠY sinh ra bug này (không phải bug.createdAt) -
            // giúp đối chiếu đúng thanh nào ở "Các test đã chạy" bên dưới.
            <div
              key={bug.id}
              className="flex flex-wrap items-center gap-1.5 rounded-md border border-amber-200 border-l-4 border-l-amber-500 bg-amber-50 p-2 dark:border-amber-900/40 dark:border-l-amber-500 dark:bg-amber-500/10"
            >
              <code className="text-xs font-semibold text-muted-foreground">{bug.bugId}</code>
              <BugStatusBadge status={bug.status} className="text-[0.8rem]" />
              <span className="text-xs text-muted-foreground">
                Từ lần chạy lúc {formatDateTime(bug.sourceOccurredAt)}
              </span>
              <div className="ml-auto flex items-center gap-0.5">
                <Button size="icon-xs" variant="ghost" onClick={() => onEditBug(bug)}>
                  <Pencil className="h-3 w-3" />
                </Button>
                <Button size="icon-xs" variant="ghost" onClick={() => onDeleteBug(bug)}>
                  <Trash2 className="h-3 w-3" />
                </Button>
              </div>
            </div>
          ))}
        </div>
      )}

      {pendingBugs.length > 0 && <CloseSuggestionBanners projectId={projectId} bugs={pendingBugs} />}

      <Button size="sm" variant="ghost" className="mt-2" onClick={onToggle}>
        Các test đã chạy
        <ChevronDown className={cn("h-4 w-4 transition-transform", isExpanded && "rotate-180")} />
      </Button>

      {isExpanded && (
        <RunHistoryList
          projectId={projectId}
          testCaseId={testCase.testCaseId}
          bugs={testCase.bugs}
          onCreateBug={onCreateBug}
        />
      )}
    </div>
  )
}

function CloseSuggestionBanners({ projectId, bugs }: { projectId: string; bugs: BugReport[] }) {
  const queryClient = useQueryClient()
  const confirmMutation = useMutation({
    mutationFn: (bugReportId: string) => confirmCloseBugReport(projectId, bugReportId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["bug-reports", projectId] }),
  })
  const dismissMutation = useMutation({
    mutationFn: (bugReportId: string) => dismissCloseSuggestion(projectId, bugReportId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["bug-reports", projectId] }),
  })

  return (
    <div className="mt-2 flex flex-col gap-2">
      {bugs.map((bug) => (
        <div
          key={bug.id}
          className="flex flex-wrap items-center gap-2 rounded-md bg-amber-500/10 p-2 text-xs text-amber-700 dark:text-amber-400"
        >
          <span className="min-w-0 flex-1">{bug.bugId}: có lần chạy Pass gần đây - đóng bug này?</span>
          <Button
            size="xs"
            variant="outline"
            onClick={() => confirmMutation.mutate(bug.id)}
            disabled={confirmMutation.isPending}
          >
            Xác nhận Đóng
          </Button>
          <Button
            size="xs"
            variant="ghost"
            onClick={() => dismissMutation.mutate(bug.id)}
            disabled={dismissMutation.isPending}
          >
            Bỏ qua
          </Button>
        </div>
      ))}
    </div>
  )
}

function RunHistoryList({
  projectId,
  testCaseId,
  bugs,
  onCreateBug,
}: {
  projectId: string
  testCaseId: string
  bugs: BugReport[]
  onCreateBug: (testResultId: string) => void
}) {
  const [expandedRunId, setExpandedRunId] = useState<string | null>(null)
  const bugBySourceResultId = new Map(bugs.map((bug) => [bug.sourceTestResultId, bug]))

  const { data: runs, isLoading, isError } = useQuery({
    queryKey: ["bug-report-run-history", projectId, testCaseId],
    queryFn: () => getRunHistory(projectId, testCaseId),
  })

  if (isLoading) {
    return <p className="mt-3 text-xs text-muted-foreground">Đang tải lịch sử chạy...</p>
  }
  if (isError) {
    return <p className="mt-3 text-xs text-destructive">Không tải được lịch sử chạy.</p>
  }
  if (!runs || runs.length === 0) {
    return <p className="mt-3 text-xs text-muted-foreground">Chưa có lần chạy nào.</p>
  }

  return (
    <ul className="mt-3 ml-1 flex flex-col gap-2 border-l border-border pl-4">
      {runs.map((run) => {
        const isExpanded = expandedRunId === run.testResultId
        const sourceOfBug = bugBySourceResultId.get(run.testResultId)
        return (
          <li key={run.testResultId} className="relative">
            <span
              className={cn(
                "absolute top-1.5 -left-[21px] h-2 w-2 rounded-full border-2 border-background",
                sourceOfBug ? "bg-amber-500" : "bg-muted-foreground/40"
              )}
            />
            {/* Nền trắng/border thường - KHÔNG tô cả hàng, chỉ badge Fail/Pass và nhãn "Nguồn của"
                mang màu để mắt bắt đúng chỗ cần chú ý, tránh mọi hàng đều vàng gây rối mắt. */}
            <button
              type="button"
              onClick={() => setExpandedRunId(isExpanded ? null : run.testResultId)}
              className="flex w-full items-center gap-2 rounded-md border border-border bg-card p-2 text-left text-xs"
            >
              <span className="text-muted-foreground">{formatDateTime(run.occurredAt)}</span>
              <StatusBadge status={run.status} compact />
              {sourceOfBug && (
                <span className="rounded-md bg-amber-500/15 px-1.5 py-0.5 text-[0.7rem] font-semibold text-amber-700 dark:text-amber-400">
                  Nguồn của {sourceOfBug.bugId}
                </span>
              )}
              <ChevronDown
                className={cn(
                  "ml-auto h-3.5 w-3.5 shrink-0 text-muted-foreground transition-transform",
                  isExpanded && "rotate-180"
                )}
              />
            </button>
            {isExpanded && (
              <div className="mt-1 flex flex-col gap-2 rounded-md border border-border bg-muted/30 p-2 text-xs">
                <p>
                  Kỳ vọng {run.expectedStatus} → thực tế {run.responseStatus ?? "—"}
                </p>
                {run.errorMessage && <p className="text-destructive">{run.errorMessage}</p>}
                <div>
                  <p className="mb-1 font-medium text-muted-foreground">Nội dung phản hồi</p>
                  {formatResponseBody(run.responseBody) ? (
                    <pre className="max-h-80 overflow-auto rounded-md bg-muted p-2 whitespace-pre-wrap break-all">
                      {formatResponseBody(run.responseBody)}
                    </pre>
                  ) : (
                    <p className="text-muted-foreground">Không có nội dung phản hồi.</p>
                  )}
                </div>
                {run.assertionResults.length > 0 && (
                  <div>
                    <p className="mb-1 font-medium text-muted-foreground">Assertions</p>
                    <ul className="flex flex-col gap-1.5">
                      {run.assertionResults.map((a, index) => (
                        <li
                          key={`${a.jsonPath}-${a.operator}-${index}`}
                          className={cn(
                            "flex flex-wrap items-center gap-2 rounded-md px-2 py-1",
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
                {run.status === "FAILED" && (
                  <Button size="xs" variant="outline" className="self-end" onClick={() => onCreateBug(run.testResultId)}>
                    Báo lỗi
                  </Button>
                )}
              </div>
            )}
          </li>
        )
      })}
    </ul>
  )
}
