import { useEffect, useMemo, useRef, useState } from "react"
import { Link, useParams } from "react-router-dom"
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Bug, CheckCircle2, ChevronDown, Download, GitCompare, ListChecks, Loader2, Pencil, XCircle } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu"
import { StatusBadge, RESULT_STATUS_STYLES, RESULT_STATUS_LABEL, RESULT_STATUS_ICON } from "@/components/shared/StatusBadge"
import { BugStatusBadge } from "@/components/shared/BugStatusBadge"
import { FailureCauseChip, FailureCauseDetail, FailureCauseSummaryBanner } from "@/components/shared/FailureCause"
import { StabilityNote, UnstableBadge } from "@/components/shared/StabilityNote"
import { SecurityBadge, SecurityDetail } from "@/components/shared/SecurityWarnings"
import { ResponseBodyView } from "@/components/shared/ResponseBodyView"
import { CreateBugReportDialog } from "@/components/bugreports/CreateBugReportDialog"
import { BugReportFormDialog } from "@/components/bugreports/BugReportFormDialog"
import { ApiError } from "@/lib/api"
import { generateBugReports, getBugReports, type BugReport, type GenerateBugReportsResult } from "@/lib/bugReports"
import { cn } from "@/lib/utils"
import { listEndpoints } from "@/lib/endpoints"
import {
  exportExecution,
  FAILURE_CAUSE_LABEL,
  getExecutionPage,
  type ExecutionReportFormat,
  type ExecutionStatus,
  type FailureCauseCode,
  type TestResultStatus,
} from "@/lib/executions"
import { useListFilters } from "@/hooks/useListFilters"
import { ListToolbar, type ListFilterSelect } from "@/components/shared/ListToolbar"
import { Pagination } from "@/components/shared/Pagination"
import { ListEmptyState } from "@/components/shared/ListEmptyState"

// Thứ tự cố định (khớp RESULT_STATUS_LABEL bên dưới) - dùng để vẽ donut theo 1 thứ tự nhất quán,
// không lệ thuộc thứ tự dữ liệu trả về.
const STATUS_ORDER: TestResultStatus[] = ["PASSED", "FAILED", "ERROR", "BLOCKED", "SKIPPED"]

const FILTER_KEYS = ["status", "failureCause", "endpointId"] as const
const PAGE_SIZE = 50

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
  const lf = useListFilters({ filterKeys: FILTER_KEYS, defaultPageSize: PAGE_SIZE })
  const topRef = useRef<HTMLDivElement>(null)
  const [selectAllError, setSelectAllError] = useState<string | null>(null)
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [selectionMode, setSelectionMode] = useState(false)
  const [selectedResultIds, setSelectedResultIds] = useState<Set<string>>(new Set())
  const [createBugResultId, setCreateBugResultId] = useState<string | null>(null)
  const [editingBugFromPanel, setEditingBugFromPanel] = useState<BugReport | null>(null)
  const [bugPanelOpen, setBugPanelOpen] = useState(false)

  const queryClient = useQueryClient()
  const executionQueryKey = ["executions", projectId, executionId]
  const bugReportsQueryKey = ["bug-reports", projectId]

  // Kết quả theo trang (Module 17 G4). Khoá nằm dưới ["executions", projectId, executionId] nên mọi nơi invalidate theo tiền tố vẫn làm mới được.
  const { data: execution, isLoading, isError, isFetching, isPlaceholderData } = useQuery({
    queryKey: [...executionQueryKey, "page", lf.params],
    queryFn: () => getExecutionPage(projectId, executionId!, lf.params),
    placeholderData: keepPreviousData,
    enabled: Boolean(executionId),
    refetchInterval: (query) => {
      const status = query.state.data?.status
      return status === "PENDING" || status === "RUNNING" ? 1500 : false
    },
  })

  // Danh sách endpoint chỉ để làm các lựa chọn của bộ lọc.
  const { data: endpointsData } = useQuery({
    queryKey: ["endpoints", projectId],
    queryFn: () => listEndpoints(projectId),
  })
  const results = useMemo(() => execution?.results.data ?? [], [execution])
  const summary = execution?.summary

  const { clampPage } = lf
  useEffect(() => {
    if (execution && !isPlaceholderData) clampPage(execution.results.totalPages)
  }, [execution, isPlaceholderData, clampPage])

  // Cùng queryKey với BugReportPage.tsx - dùng chung cache nếu đã ghé qua. Lọc ra đúng bug sinh từ
  // lần chạy này (theo sourceTestResultId) cho ô "Bug Report đã sinh".
  const { data: bugReportsPage } = useQuery({
    queryKey: bugReportsQueryKey,
    queryFn: () => getBugReports(projectId),
  })

  const bugResultIds = summary?.bugResultIds
  const generatedBugs = useMemo<BugReport[]>(() => {
    if (!bugReportsPage || !bugResultIds) return []
    const resultIds = new Set(bugResultIds)
    return bugReportsPage.endpoints
      .flatMap((e) => e.testCases.flatMap((tc) => tc.bugs))
      .filter((bug) => resultIds.has(bug.sourceTestResultId))
  }, [bugReportsPage, bugResultIds])

  // Module 17 (17d) - xuất báo cáo lượt chạy (JUnit XML cho CI / HTML để mở-in).
  const exportMutation = useMutation({
    mutationFn: (format: ExecutionReportFormat) => exportExecution(projectId, executionId!, format),
  })

  const [lastGenerateResult, setLastGenerateResult] = useState<GenerateBugReportsResult | null>(null)

  // "Sinh tất cả" xét toàn bộ kết quả Fail của lần chạy - backend tự bỏ qua dòng không hợp lệ.
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

  const isFinished = execution && execution.status !== "PENDING" && execution.status !== "RUNNING"
  // Tính trên TOÀN lượt chạy (server), không chỉ trang đang xem.
  const hasGeneratableFailedResults = (summary?.failedWithoutBugCount ?? 0) > 0

  const selectableOnPage = results.filter((r) => r.status === "FAILED" && !r.existingBugReportId).map((r) => r.id)
  const selectedOnPage = selectableOnPage.filter((resultId) => selectedResultIds.has(resultId)).length
  const hiddenSelected = selectedResultIds.size - results.filter((r) => selectedResultIds.has(r.id)).length
  const [selectingAllFailed, setSelectingAllFailed] = useState(false)

  function togglePageSelection(checked: boolean) {
    setSelectedResultIds((prev) => {
      const next = new Set(prev)
      for (const resultId of selectableOnPage) {
        if (checked) next.add(resultId)
        else next.delete(resultId)
      }
      return next
    })
  }

  /** Chọn mọi kết quả Fail chưa có Bug Report khớp bộ lọc hiện tại (mọi trang) - bấm rõ ràng, không tự chọn thứ người dùng chưa thấy. */
  async function selectAllFailedMatching() {
    setSelectingAllFailed(true)
    setSelectAllError(null)
    try {
      const collected: string[] = []
      let pageIndex = 0
      let totalPages = 1
      while (pageIndex < totalPages) {
        const next = await getExecutionPage(projectId, executionId!, {
          ...lf.params,
          status: "FAILED",
          page: pageIndex,
          size: 200,
        })
        totalPages = next.results.totalPages
        for (const r of next.results.data) {
          if (!r.existingBugReportId) collected.push(r.id)
        }
        pageIndex += 1
      }
      setSelectedResultIds((prev) => new Set([...prev, ...collected]))
    } catch (error) {
      setSelectAllError(error instanceof ApiError ? error.message : "Không chọn được tất cả kết quả Fail, vui lòng thử lại")
    } finally {
      setSelectingAllFailed(false)
    }
  }

  const failureCauseOptions = Object.entries(summary?.failureCauseCounts ?? {}).map(([code, count]) => ({
    value: code,
    label: `${FAILURE_CAUSE_LABEL[code as FailureCauseCode] ?? code} (${count})`,
  }))
  const selects: ListFilterSelect[] = [
    {
      key: "status",
      label: "Lọc theo kết quả",
      allLabel: "Mọi kết quả",
      value: lf.filters.status,
      className: "sm:w-40",
      options: STATUS_ORDER.map((status) => ({ value: status, label: RESULT_STATUS_LABEL[status] })),
    },
    {
      key: "failureCause",
      label: "Lọc theo nguyên nhân Fail",
      allLabel: "Mọi nguyên nhân",
      value: lf.filters.failureCause,
      className: "sm:w-56",
      options: failureCauseOptions,
    },
    {
      key: "endpointId",
      label: "Lọc theo endpoint",
      allLabel: "Tất cả endpoint",
      value: lf.filters.endpointId,
      className: "sm:w-64",
      options: (endpointsData?.data ?? []).map((endpoint) => ({ value: endpoint.id, label: `${endpoint.method} ${endpoint.path}` })),
    },
  ]

  function changePage(next: number) {
    lf.setPage(next)
    topRef.current?.scrollIntoView({ block: "start" })
  }

  return (
    <div ref={topRef}>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-lg font-semibold">Kết quả thực thi</h2>
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
          {isFinished && (
            <DropdownMenu>
              <DropdownMenuTrigger
                render={
                  <Button variant="outline" size="sm" disabled={exportMutation.isPending}>
                    {exportMutation.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Download className="h-4 w-4" />}
                    Xuất báo cáo
                    <ChevronDown className="h-3.5 w-3.5" />
                  </Button>
                }
              />
              <DropdownMenuContent align="end">
                <DropdownMenuItem onClick={() => exportMutation.mutate("junit")}>JUnit XML (.xml) - cho CI</DropdownMenuItem>
                <DropdownMenuItem onClick={() => exportMutation.mutate("html")}>Báo cáo HTML (.html) - để mở/in</DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          )}
          {isFinished && (
            <Button variant="outline" size="sm" render={<Link to={`/projects/${projectId}/executions/${executionId}/compare`} />}>
              <GitCompare className="h-4 w-4" />
              So sánh với lượt trước
            </Button>
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
          {isFinished && hasGeneratableFailedResults && (
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
      {exportMutation.isError && (
        <p className="mt-2 text-sm text-destructive" role="alert">
          {exportMutation.error instanceof ApiError ? exportMutation.error.message : "Không xuất được báo cáo, vui lòng thử lại"}
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
      {isError && (
        <div className="mt-6 flex flex-col gap-3">
          <p className="text-destructive">Không tải được kết quả thực thi.</p>
          {lf.activeCount > 0 && (
            <div>
              <Button type="button" size="sm" variant="outline" onClick={lf.reset}>
                Xoá bộ lọc ({lf.activeCount}) và thử lại
              </Button>
            </div>
          )}
        </div>
      )}

      {execution && execution.autoIncludedTestCaseIds.length > 0 && (
        <p className="mt-4 rounded-md border border-border bg-muted/50 p-3 text-sm text-muted-foreground">
          {execution.autoIncludedTestCaseIds.length} test case phụ trợ đã tự động chạy kèm (dữ liệu
          nguồn cho Test Data Chaining).
        </p>
      )}

      {execution && !isFinished && (
        <div className="mt-6 flex items-center gap-3 rounded-lg border border-border bg-muted/40 p-4 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 shrink-0 animate-spin" />
          <span>
            Đang chạy các test case, kết quả sẽ hiện khi chạy xong
            {execution.summary.total > 0 ? ` (đã chạy xong ${execution.summary.total} test case)` : ""}.
          </span>
        </div>
      )}

      {isFinished && summary && summary.total > 0 && (
        <ExecutionSummaryDashboard total={summary.total} countByStatus={summary.statusCounts} />
      )}
      {isFinished && summary && summary.total > 0 && (
        <FailureCauseSummaryBanner counts={summary.failureCauseCounts} total={summary.total} projectId={projectId} />
      )}

      {execution && isFinished && summary && summary.total > 0 && (
        <>
          <ListToolbar
            className="mt-4"
            searchValue={lf.searchInput}
            onSearchChange={lf.setSearchInput}
            searchPlaceholder="Tìm theo tên test case hoặc đường dẫn..."
            searchLabel="Tìm kết quả"
            selects={selects}
            onSelectChange={(key, value) => lf.setFilter(key as (typeof FILTER_KEYS)[number], value)}
            activeFilterCount={lf.activeCount}
            onReset={lf.reset}
          />
          <p className="mt-1 text-xs text-muted-foreground">Biểu đồ và số liệu ở trên tính cho toàn bộ lần chạy, không đổi theo bộ lọc.</p>

          {selectionMode && (
            <div className="mt-3 flex flex-wrap items-center gap-3 rounded-md bg-muted px-3 py-2 text-sm" role="status">
              <label className="flex items-center gap-2">
                <Checkbox
                  aria-label="Chọn các kết quả Fail chưa có Bug Report trên trang này"
                  checked={selectableOnPage.length > 0 && selectedOnPage === selectableOnPage.length}
                  indeterminate={selectedOnPage > 0 && selectedOnPage < selectableOnPage.length}
                  disabled={selectableOnPage.length === 0}
                  onCheckedChange={(checked) => togglePageSelection(checked === true)}
                />
                Chọn các kết quả Fail trên trang này
              </label>
              <button
                type="button"
                className="underline disabled:opacity-50"
                disabled={selectingAllFailed || (lf.filters.status !== "" && lf.filters.status !== "FAILED")}
                title={lf.filters.status !== "" && lf.filters.status !== "FAILED" ? "Đang lọc kết quả khác Fail - bỏ lọc trạng thái hoặc chọn Fail để dùng" : undefined}
                onClick={selectAllFailedMatching}
              >
                {selectingAllFailed ? "Đang chọn..." : "Chọn tất cả Fail chưa có Bug Report khớp bộ lọc"}
              </button>
              {selectAllError && <span className="text-destructive">{selectAllError}</span>}
              <span className="text-muted-foreground">
                Đã chọn {selectedResultIds.size}
                {hiddenSelected > 0 ? ` (${hiddenSelected} nằm ngoài trang này)` : ""}
              </span>
            </div>
          )}

          <Pagination
            className="mt-3"
            page={execution.results.page}
            totalPages={execution.results.totalPages}
            totalElements={execution.results.totalElements}
            shownCount={results.length}
            size={execution.results.size}
            isFetching={isFetching}
            itemLabel="kết quả"
            onPageChange={changePage}
          />
        </>
      )}

      {isFinished && summary && summary.total > 0 && results.length === 0 && (
        <div className="mt-4">
          <ListEmptyState
            filtered={lf.activeCount > 0}
            emptyText="Lần chạy này không ghi nhận kết quả nào."
            filteredText="Không có kết quả nào khớp bộ lọc."
            onReset={lf.reset}
          />
        </div>
      )}

      {isFinished && summary && summary.total === 0 && (
        <div className="mt-6 rounded-lg border border-dashed border-border p-6 text-center text-sm text-muted-foreground">
          <p>Lần chạy này không ghi nhận kết quả nào.</p>
          <Link to={`/projects/${projectId}/test-cases`} className="mt-2 inline-block text-primary underline underline-offset-4">
            Về danh sách Test case
          </Link>
        </div>
      )}

      <ul className={cn("mt-4 flex flex-col gap-2", isPlaceholderData && "opacity-60 transition-opacity")} aria-busy={isFetching}>
        {results.map((result) => {
          const isExpanded = expandedId === result.testCaseId
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
                    {result.durationMs !== null && ` · ${result.durationMs} ms`}
                  </span>
                  {result.failureCause && <FailureCauseChip cause={result.failureCause} />}
                  <UnstableBadge result={result} />
                  {result.schemaViolations.length > 0 && (
                    <span
                      className="shrink-0 rounded-full bg-amber-500/10 px-2 py-0.5 text-xs font-medium text-amber-600 dark:text-amber-400"
                      title="Phản hồi lệch schema khai báo trong spec - kết quả Pass/Fail không đổi"
                    >
                      Lệch schema (cảnh báo)
                    </span>
                  )}
                  {(result.specDeviations?.length ?? 0) > 0 && (
                    <span
                      className="shrink-0 rounded-full bg-amber-500/10 px-2 py-0.5 text-xs font-medium text-amber-600 dark:text-amber-400"
                      title={`${result.specDeviations?.join("; ")} - chỉ cảnh báo, kết quả không đổi`}
                    >
                      Lệch spec: mã {result.responseStatus}
                    </span>
                  )}
                  <SecurityBadge findings={result.securityFindings} />
                  {result.errorMessage && (
                    <span className="shrink-0 truncate text-xs text-destructive sm:max-w-64" title={result.errorMessage}>
                      {result.errorMessage}
                    </span>
                  )}
                  <ChevronDown
                    className={cn("h-4 w-4 shrink-0 text-muted-foreground transition-transform", isExpanded && "rotate-180")}
                  />
                </button>
                {/* Sibling của <button>, không lồng bên trong (tránh <a> trong <button>). */}
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
                  <StabilityNote result={result} projectId={projectId} />
                  <FailureCauseDetail result={result} />
                  {(result.specDeviations?.length ?? 0) > 0 && (
                    <div className="rounded-md border border-amber-500/30 bg-amber-500/5 p-3 text-xs">
                      <p className="font-medium text-amber-700 dark:text-amber-400">Mã trạng thái lệch spec (chỉ cảnh báo)</p>
                      <ul className="mt-1.5 list-disc pl-5 text-foreground">
                        {result.specDeviations?.map((deviation) => (
                          <li key={deviation}>{deviation}</li>
                        ))}
                      </ul>
                      <p className="mt-1.5 text-muted-foreground">
                        API trả mã 2xx không có trong spec. Có thể API sai so với spec, hoặc spec khai thiếu.
                      </p>
                    </div>
                  )}
                  {result.schemaViolations.length > 0 && (
                    <div className="rounded-md border border-amber-500/30 bg-amber-500/5 p-3 text-xs">
                      <p className="font-medium text-amber-700 dark:text-amber-400">
                        Phản hồi lệch schema khai báo trong spec (chỉ cảnh báo, kết quả không đổi)
                      </p>
                      <ul className="mt-1.5 list-disc pl-5 text-foreground">
                        {result.schemaViolations.map((violation) => (
                          <li key={violation}>{violation}</li>
                        ))}
                      </ul>
                      <p className="mt-1.5 text-muted-foreground">
                        Chỉ kiểm kiểu dữ liệu và trường bắt buộc. Có thể API sai so với spec, hoặc spec khai báo chưa đúng.
                      </p>
                    </div>
                  )}
                  <SecurityDetail findings={result.securityFindings} />
                  {result.errorMessage && (
                    <div>
                      <p className="mb-1 text-xs font-medium text-muted-foreground">Lỗi</p>
                      <p className="text-xs whitespace-pre-wrap text-destructive">{result.errorMessage}</p>
                    </div>
                  )}
                  <div>
                    <p className="mb-1 text-xs font-medium text-muted-foreground">Nội dung phản hồi</p>
                    <ResponseBodyView body={result.responseBody} />
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
                  {result.status === "PASSED" && (result.specDeviations?.length ?? 0) > 0 && !result.existingBugReportId && (
                    <Button
                      size="xs"
                      variant="outline"
                      className="self-end"
                      title="Tạo Bug Report mức thấp cho chênh lệch mã trạng thái (mỗi endpoint 1 bug đang mở)"
                      onClick={() => setCreateBugResultId(result.id)}
                    >
                      Báo lỗi lệch spec
                    </Button>
                  )}
                </div>
              )}
            </li>
          )
        })}
      </ul>

      {execution && isFinished && results.length > 0 && (
        <Pagination
          className="mt-4"
              announce={false}
          page={execution.results.page}
          totalPages={execution.results.totalPages}
          totalElements={execution.results.totalElements}
          shownCount={results.length}
          size={execution.results.size}
          isFetching={isFetching}
          itemLabel="kết quả"
          onPageChange={changePage}
        />
      )}

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

function ExecutionSummaryDashboard({ total, countByStatus }: { total: number; countByStatus: Partial<Record<TestResultStatus, number>> }) {
  const presentStatuses = STATUS_ORDER.filter((status) => (countByStatus[status] ?? 0) > 0)
  const passCount = countByStatus.PASSED ?? 0
  const passRate = total > 0 ? Math.round((passCount / total) * 100) : 0

  // Vẽ ring "nở ra" 1 lần khi mount - motion-reduce:transition-none ở từng <circle> tắt animation
  // cho prefers-reduced-motion mà không cần nhánh code riêng.
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
