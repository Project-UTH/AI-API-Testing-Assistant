import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { Link, useLocation, useNavigate, useParams } from "react-router-dom"
import { GeneratedResultCard } from "@/components/rules/GeneratedResultCard"
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query"
import { History, Loader2, Play, Plus } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { cn, METHOD_STYLES } from "@/lib/utils"
import { ApiError } from "@/lib/api"
import { listEndpoints } from "@/lib/endpoints"
import {
  GENERATION_ORIGIN_DISPLAY,
  listAllMatchingTestCaseIds,
  listTestCasesPage,
  setTestCaseLocked,
  TEST_CASE_SOURCE_LABEL,
  type TestCase,
} from "@/lib/testcases"
import { triggerExecution } from "@/lib/executions"
import { useListFilters } from "@/hooks/useListFilters"
import { ListToolbar, type ListFilterSelect } from "@/components/shared/ListToolbar"
import { Pagination } from "@/components/shared/Pagination"
import { ListEmptyState } from "@/components/shared/ListEmptyState"
import { TestCaseRow } from "@/components/testcases/TestCaseRow"
import { TestCaseFormDialog } from "@/components/testcases/TestCaseFormDialog"
import { DeleteTestCaseDialog } from "@/components/testcases/DeleteTestCaseDialog"
import { TestCaseDetailDialog } from "@/components/testcases/TestCaseDetailDialog"

const FILTER_KEYS = ["endpointId", "method", "source", "generationOrigin", "locked", "reliability"] as const
const PAGE_SIZE = 50
const METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE"]

interface FormState {
  open: boolean
  endpointId: string
  endpointPath: string
  testCase: TestCase | null
}

interface EndpointGroup {
  endpointId: string
  method: string
  path: string
  cases: TestCase[]
}

/** Server trả các dòng đã sắp theo endpoint nên các dòng cùng endpoint nằm liền nhau: gom lại để hiện tiêu đề nhóm. */
function groupConsecutive(items: TestCase[]): EndpointGroup[] {
  const groups: EndpointGroup[] = []
  for (const testCase of items) {
    const last = groups[groups.length - 1]
    if (last && last.endpointId === testCase.endpointId) {
      last.cases.push(testCase)
    } else {
      groups.push({ endpointId: testCase.endpointId, method: testCase.endpointMethod, path: testCase.endpointPath, cases: [testCase] })
    }
  }
  return groups
}

export function TestCasesPage() {
  const { id } = useParams<{ id: string }>()
  // key theo project: đổi project thì xoá sạch vùng chọn và trạng thái hộp thoại.
  return <TestCasesPageContent key={id} projectId={id!} />
}

function TestCasesPageContent({ projectId }: { projectId: string }) {
  const navigate = useNavigate()
  // Vừa sinh test từ màn duyệt checklist: hiện thẻ "Bước 4 hoàn tất" (chạy ngay các test vừa sinh) ở đầu trang.
  const justGenerated = (useLocation().state as { justGenerated?: { ids: string[]; systemCount: number; aiCount: number; endpointId: string; notGenerated: { ruleId: string; sentence: string; reason: string }[] } } | null)?.justGenerated
  const queryClient = useQueryClient()
  const topRef = useRef<HTMLDivElement>(null)
  const lf = useListFilters({ filterKeys: FILTER_KEYS, defaultPageSize: PAGE_SIZE })
  const [lockPendingIds, setLockPendingIds] = useState<Set<string>>(new Set())

  const { data: endpointsData } = useQuery({
    queryKey: ["endpoints", projectId],
    queryFn: () => listEndpoints(projectId),
  })
  const { data: page, isLoading, isError, isFetching, isPlaceholderData } = useQuery({
    queryKey: ["test-cases", projectId, "page", lf.params],
    // 17q G2 - bộ lọc chỉ còn "Kỳ vọng cần xem"; link cũ ?reliability=RELIABLE bị bỏ qua thay vì lọc ngầm.
    queryFn: () =>
      listTestCasesPage(projectId, {
        ...lf.params,
        reliability: lf.params.reliability === "NEEDS_REVIEW" ? "NEEDS_REVIEW" : undefined,
      }),
    placeholderData: keepPreviousData,
  })

  const endpoints = endpointsData?.data ?? []
  const items = useMemo(() => page?.data ?? [], [page])
  const groups = useMemo(() => groupConsecutive(items), [items])

  const { clampPage } = lf
  useEffect(() => {
    // Lọc làm ít kết quả đi hoặc xoá dòng cuối của trang cuối: lùi về trang cuối thay vì hiện trang trống.
    if (page && !isPlaceholderData) clampPage(page.totalPages)
  }, [page, isPlaceholderData, clampPage])

  const [formState, setFormState] = useState<FormState>({ open: false, endpointId: "", endpointPath: "", testCase: null })
  const [deleteTarget, setDeleteTarget] = useState<TestCase | null>(null)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [detailTestCase, setDetailTestCase] = useState<TestCase | null>(null)
  const [detailOpen, setDetailOpen] = useState(false)
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  // "Chọn tất cả khớp bộ lọc" đã bấm (null = chưa): chỉ còn đúng khi bộ lọc VÀ tổng số khớp đều giữ nguyên như lúc bấm.
  const [matchingSelected, setMatchingSelected] = useState<{ key: string; total: number } | null>(null)
  const [selectingMatching, setSelectingMatching] = useState(false)
  const [isRunning, setIsRunning] = useState(false)
  const [confirmRunOpen, setConfirmRunOpen] = useState(false)
  const [cleanupCreated, setCleanupCreated] = useState(false)
  const [runError, setRunError] = useState<string | null>(null)

  const matchKey = useMemo(() => JSON.stringify([lf.q, lf.filters]), [lf.q, lf.filters])

  const openCreate = useCallback((endpointId: string, endpointPath: string) => {
    setFormState({ open: true, endpointId, endpointPath, testCase: null })
  }, [])

  const openEdit = useCallback((testCase: TestCase) => {
    setFormState({ open: true, endpointId: testCase.endpointId, endpointPath: testCase.endpointPath, testCase })
  }, [])

  const openDelete = useCallback((testCase: TestCase) => {
    setDeleteTarget(testCase)
    setDeleteOpen(true)
  }, [])

  const openDetail = useCallback((testCase: TestCase) => {
    setDetailTestCase(testCase)
    setDetailOpen(true)
  }, [])

  const handleToggleLock = useCallback(
    async (testCase: TestCase) => {
      setLockPendingIds((prev) => new Set(prev).add(testCase.id))
      try {
        await setTestCaseLocked(projectId, testCase.endpointId, testCase.id, !testCase.locked)
        queryClient.invalidateQueries({ queryKey: ["test-cases", projectId] })
      } catch {
        // Bỏ qua - trạng thái locked không đổi, người dùng thấy nút vẫn ở icon cũ và có thể bấm lại
      } finally {
        setLockPendingIds((prev) => {
          const next = new Set(prev)
          next.delete(testCase.id)
          return next
        })
      }
    },
    [projectId, queryClient]
  )

  const toggleSelect = useCallback((testCaseId: string, checked: boolean) => {
    setMatchingSelected(null)
    setSelectedIds((prev) => {
      const next = new Set(prev)
      if (checked) next.add(testCaseId)
      else next.delete(testCaseId)
      return next
    })
  }, [])

  const handleDeleted = useCallback((testCaseId: string) => {
    setMatchingSelected(null)
    setSelectedIds((prev) => {
      if (!prev.has(testCaseId)) return prev
      const next = new Set(prev)
      next.delete(testCaseId)
      return next
    })
  }, [])

  const pageIds = useMemo(() => items.map((testCase) => testCase.id), [items])
  const selectedOnPage = pageIds.filter((testCaseId) => selectedIds.has(testCaseId)).length
  const allOnPageSelected = pageIds.length > 0 && selectedOnPage === pageIds.length
  const hiddenSelected = selectedIds.size - selectedOnPage

  function togglePage(checked: boolean) {
    setMatchingSelected(null)
    setSelectedIds((prev) => {
      const next = new Set(prev)
      for (const testCaseId of pageIds) {
        if (checked) next.add(testCaseId)
        else next.delete(testCaseId)
      }
      return next
    })
  }

  /** Chọn / bỏ chọn tất cả test case của 1 nhóm endpoint đang hiển thị (ô tick ở đầu nhóm). */
  function toggleGroup(groupCases: TestCase[], checked: boolean) {
    setMatchingSelected(null)
    setSelectedIds((prev) => {
      const next = new Set(prev)
      for (const testCase of groupCases) {
        if (checked) next.add(testCase.id)
        else next.delete(testCase.id)
      }
      return next
    })
  }

  async function selectAllMatching() {
    setSelectingMatching(true)
    setRunError(null)
    try {
      const ids = await listAllMatchingTestCaseIds(projectId, { q: lf.q, ...lf.filters })
      setSelectedIds((prev) => new Set([...prev, ...ids]))
      setMatchingSelected({ key: matchKey, total: ids.length })
    } catch (error) {
      setRunError(error instanceof ApiError ? error.message : "Không chọn được tất cả test case khớp bộ lọc, vui lòng thử lại")
    } finally {
      setSelectingMatching(false)
    }
  }

  function clearSelection() {
    setSelectedIds(new Set())
    setMatchingSelected(null)
    setRunError(null)
  }

  async function runSelected() {
    setConfirmRunOpen(false)
    setRunError(null)
    setIsRunning(true)
    try {
      const execution = await triggerExecution(projectId, Array.from(selectedIds), cleanupCreated)
      navigate(`/projects/${projectId}/executions/${execution.id}`)
    } catch (error) {
      setRunError(error instanceof ApiError ? error.message : "Đã xảy ra lỗi, vui lòng thử lại")
    } finally {
      setIsRunning(false)
    }
  }

  function handleRunClick() {
    // Có test case đang chọn mà người dùng không nhìn thấy (ở trang khác / ngoài bộ lọc): hỏi lại trước khi gửi request thật.
    if (hiddenSelected > 0) setConfirmRunOpen(true)
    else void runSelected()
  }

  function changePage(next: number) {
    lf.setPage(next)
    topRef.current?.scrollIntoView({ block: "start" })
  }

  const selects: ListFilterSelect[] = [
    {
      key: "endpointId",
      label: "Lọc theo endpoint",
      allLabel: "Tất cả endpoint",
      value: lf.filters.endpointId,
      className: "sm:w-64",
      options: endpoints.map((endpoint) => ({ value: endpoint.id, label: `${endpoint.method} ${endpoint.path}` })),
    },
    {
      key: "method",
      label: "Lọc theo method",
      allLabel: "Mọi method",
      value: lf.filters.method,
      className: "sm:w-32",
      options: METHODS.map((method) => ({ value: method, label: method })),
    },
    {
      key: "source",
      label: "Lọc theo nguồn",
      allLabel: "Mọi nguồn",
      value: lf.filters.source,
      options: Object.entries(TEST_CASE_SOURCE_LABEL).map(([value, label]) => ({
        value,
        // Case hệ thống bổ sung vẫn lưu nguồn AI nên nằm trong nhóm này (hiện nhãn "Hệ thống" ở từng dòng).
        label: value === "AI_GENERATED" ? "AI (gồm hệ thống bổ sung)" : label,
      })),
    },
    {
      key: "generationOrigin",
      label: "Lọc theo cách tạo",
      allLabel: "Mọi cách tạo",
      value: lf.filters.generationOrigin,
      options: Object.entries(GENERATION_ORIGIN_DISPLAY).map(([value, display]) => ({ value, label: display.label })),
    },
    {
      key: "locked",
      label: "Lọc theo khoá",
      allLabel: "Khoá và chưa khoá",
      value: lf.filters.locked,
      options: [
        { value: "true", label: "Đã khoá" },
        { value: "false", label: "Chưa khoá" },
      ],
    },
    {
      key: "reliability",
      label: "Lọc theo kỳ vọng cần xem",
      allLabel: "Mọi test case",
      value: lf.filters.reliability === "NEEDS_REVIEW" ? "NEEDS_REVIEW" : "",
      options: [{ value: "NEEDS_REVIEW", label: "Kỳ vọng cần xem" }],
    },
  ]

  const selectedEndpoint = endpoints.find((endpoint) => endpoint.id === lf.filters.endpointId)
  const allMatchingSelected = matchingSelected !== null && matchingSelected.key === matchKey && matchingSelected.total === page?.totalElements
  const canOfferSelectAllMatching = allOnPageSelected && !!page && page.totalElements > pageIds.length && !allMatchingSelected

  return (
    <div ref={topRef} className={cn(selectedIds.size > 0 && "pb-20")}>
      {justGenerated && (
        <GeneratedResultCard
          projectId={projectId}
          endpointId={justGenerated.endpointId}
          testCaseIds={justGenerated.ids}
          systemCount={justGenerated.systemCount}
          aiCount={justGenerated.aiCount}
          notGenerated={justGenerated.notGenerated}
          hideTestsLink
        />
      )}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <h2 className="text-lg font-semibold">Test Case</h2>
        <div className="flex flex-wrap items-center gap-2">
          <Button
            size="sm"
            variant="outline"
            nativeButton={false}
            render={
              <Link
                to={`/projects/${projectId}/history?onlyExecution=1${lf.filters.endpointId ? `&endpointId=${lf.filters.endpointId}` : ""}`}
              />
            }
          >
            <History className="h-4 w-4" />
            Lịch sử chạy test
          </Button>
          <Button
            size="sm"
            variant="outline"
            disabled={!selectedEndpoint}
            title={selectedEndpoint ? undefined : "Chọn 1 endpoint ở bộ lọc để thêm test case cho endpoint đó"}
            onClick={() => selectedEndpoint && openCreate(selectedEndpoint.id, selectedEndpoint.path)}
          >
            <Plus className="h-4 w-4" />
            Thêm test case
          </Button>
        </div>
      </div>

      <ListToolbar
        className="mt-4"
        searchValue={lf.searchInput}
        onSearchChange={lf.setSearchInput}
        searchPlaceholder="Tìm theo tên test case, đường dẫn hoặc mục checklist..."
        searchLabel="Tìm test case"
        selects={selects}
        onSelectChange={(key, value) => lf.setFilter(key as (typeof FILTER_KEYS)[number], value)}
        activeFilterCount={lf.activeCount}
        onReset={lf.reset}
      />

      {runError && (
        <p className="mt-2 text-sm text-destructive">
          {runError}{" "}
          {selectedIds.size > 0 && (
            <button type="button" className="underline" onClick={clearSelection}>
              Bỏ chọn tất cả
            </button>
          )}
        </p>
      )}

      {isLoading && <p className="mt-6 text-muted-foreground">Đang tải...</p>}

      {isError && <p className="mt-6 text-destructive">Không tải được danh sách test case, vui lòng thử lại.</p>}

      {page && (
        <>
          <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
            <label className="flex items-center gap-2 text-sm">
              <Checkbox
                aria-label="Chọn các test case trên trang này"
                checked={allOnPageSelected}
                indeterminate={selectedOnPage > 0 && !allOnPageSelected}
                disabled={pageIds.length === 0 || isPlaceholderData}
                onCheckedChange={(checked) => togglePage(checked === true)}
              />
              Chọn các test case trên trang này
            </label>
            <label
              className="flex items-center gap-1.5 text-xs text-muted-foreground"
              title="Sau khi chạy, xoá các bản ghi cha do CHÍNH lần chạy này tạo cho test con (cần API có endpoint DELETE tương ứng). Không xoá dữ liệu nào khác. Lỗi khi xoá không đổi kết quả test."
            >
              <Checkbox checked={cleanupCreated} onCheckedChange={(c) => setCleanupCreated(c === true)} disabled={isRunning} />
              Dọn dữ liệu đã tạo
            </label>
          </div>

          {(canOfferSelectAllMatching || allMatchingSelected) && (
            <p className="mt-2 rounded-md bg-muted px-3 py-2 text-sm" role="status">
              {allMatchingSelected ? (
                <>
                  Đã chọn tất cả <b>{page.totalElements}</b> test case khớp bộ lọc.{" "}
                  <button type="button" className="underline" onClick={clearSelection}>
                    Bỏ chọn
                  </button>
                </>
              ) : (
                <>
                  Đã chọn {pageIds.length} test case trên trang này.{" "}
                  <button type="button" className="underline disabled:opacity-50" disabled={selectingMatching} onClick={selectAllMatching}>
                    {selectingMatching ? "Đang chọn..." : `Chọn tất cả ${page.totalElements} test case khớp bộ lọc`}
                  </button>
                </>
              )}
            </p>
          )}

          <Pagination
            className="mt-3"
            page={page.page}
            totalPages={page.totalPages}
            totalElements={page.totalElements}
            shownCount={items.length}
            size={page.size}
            isFetching={isFetching}
            itemLabel="test case"
            onPageChange={changePage}
          />

          {page.totalElements === 0 ? (
            <div className="mt-4">
              <ListEmptyState
                filtered={lf.activeCount > 0}
                emptyText="Chưa có test case nào. Vào tab Endpoint để sinh test case, hoặc chọn 1 endpoint ở bộ lọc rồi bấm Thêm test case."
                filteredText="Không có test case nào khớp bộ lọc."
                onReset={lf.reset}
                action={
                  selectedEndpoint ? (
                    <Button size="sm" variant="outline" onClick={() => openCreate(selectedEndpoint.id, selectedEndpoint.path)}>
                      <Plus className="h-4 w-4" />
                      Thêm test case cho {selectedEndpoint.path}
                    </Button>
                  ) : undefined
                }
              />
            </div>
          ) : (
            <div className="mt-4 flex flex-col gap-4" aria-busy={isFetching}>
              {groups.map((group, index) => (
                <section
                  key={`${group.endpointId}-${index}`}
                  className={cn("rounded-lg border border-border p-4", isPlaceholderData && "opacity-60 transition-opacity")}
                >
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <div className="flex min-w-0 items-center gap-3">
                      <Checkbox
                        aria-label={`Chọn tất cả test case của ${group.method} ${group.path} trên trang này`}
                        title="Chọn tất cả test case của endpoint này (trên trang hiện tại)"
                        checked={group.cases.every((testCase) => selectedIds.has(testCase.id))}
                        indeterminate={
                          group.cases.some((testCase) => selectedIds.has(testCase.id)) &&
                          !group.cases.every((testCase) => selectedIds.has(testCase.id))
                        }
                        disabled={isPlaceholderData}
                        onCheckedChange={(checked) => toggleGroup(group.cases, checked === true)}
                      />
                      <span
                        className={cn(
                          "w-16 shrink-0 rounded-md px-2 py-0.5 text-center text-xs font-semibold",
                          METHOD_STYLES[group.method] ?? "bg-muted text-muted-foreground"
                        )}
                      >
                        {group.method}
                      </span>
                      <h3 className="truncate font-mono text-sm font-normal">{group.path}</h3>
                      <span className="shrink-0 text-xs text-muted-foreground">
                        {group.cases.length} test case
                        {index === 0 && page.page > 0 ? " · có thể tiếp tục từ trang trước" : ""}
                        {index === groups.length - 1 && page.page + 1 < page.totalPages ? " · có thể còn ở trang sau" : ""}
                      </span>
                    </div>
                    <Button size="sm" variant="outline" onClick={() => openCreate(group.endpointId, group.path)}>
                      <Plus className="h-4 w-4" />
                      Thêm test case
                    </Button>
                  </div>
                  <ul className="mt-3 flex flex-col gap-2">
                    {group.cases.map((testCase) => (
                      <TestCaseRow
                        key={testCase.id}
                        projectId={projectId}
                        testCase={testCase}
                        selected={selectedIds.has(testCase.id)}
                        lockPending={lockPendingIds.has(testCase.id)}
                        disabled={isPlaceholderData}
                        onToggleSelect={toggleSelect}
                        onToggleLock={handleToggleLock}
                        onOpenDetail={openDetail}
                        onEdit={openEdit}
                        onDelete={openDelete}
                      />
                    ))}
                  </ul>
                </section>
              ))}
            </div>
          )}

          {page.totalElements > 0 && (
            <Pagination
              className="mt-4"
              announce={false}
              page={page.page}
              totalPages={page.totalPages}
              totalElements={page.totalElements}
              shownCount={items.length}
              size={page.size}
              isFetching={isFetching}
              itemLabel="test case"
              onPageChange={changePage}
            />
          )}
        </>
      )}

      {selectedIds.size > 0 && (
        <div
          role="region"
          aria-label="Thao tác với test case đã chọn"
          className="fixed inset-x-0 bottom-0 z-20 border-t bg-background/95 px-6 py-3 shadow-lg backdrop-blur"
        >
          <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-3">
            <p className="text-sm" aria-live="polite">
              Đã chọn <b>{selectedIds.size}</b> test case
              {hiddenSelected > 0 && <span className="text-muted-foreground"> ({hiddenSelected} không nằm trong trang/bộ lọc hiện tại)</span>}
            </p>
            <div className="flex items-center gap-2">
              <Button size="sm" variant="ghost" onClick={clearSelection} disabled={isRunning}>
                Bỏ chọn tất cả
              </Button>
              <Button size="sm" disabled={isRunning} onClick={handleRunClick}>
                {isRunning ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}
                Chạy Test ({selectedIds.size})
              </Button>
            </div>
          </div>
        </div>
      )}

      <AlertDialog open={confirmRunOpen} onOpenChange={setConfirmRunOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Chạy {selectedIds.size} test case?</AlertDialogTitle>
            <AlertDialogDescription>
              Trong đó có {hiddenSelected} test case không nằm trong trang hoặc bộ lọc đang hiển thị. Các test case này gửi request thật tới
              API đích (kể cả POST/PUT/DELETE). Bạn có chắc muốn chạy tất cả?
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Huỷ</AlertDialogCancel>
            <AlertDialogAction onClick={() => void runSelected()}>Chạy {selectedIds.size} test case</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <TestCaseFormDialog
        open={formState.open}
        onOpenChange={(open) => setFormState((prev) => ({ ...prev, open }))}
        projectId={projectId}
        endpointId={formState.endpointId}
        endpointPath={formState.endpointPath}
        testCase={formState.testCase}
      />

      <DeleteTestCaseDialog
        open={deleteOpen}
        onOpenChange={setDeleteOpen}
        projectId={projectId}
        testCase={deleteTarget}
        onDeleted={handleDeleted}
      />

      <TestCaseDetailDialog open={detailOpen} onOpenChange={setDetailOpen} testCase={detailTestCase} projectId={projectId} />
    </div>
  )
}
