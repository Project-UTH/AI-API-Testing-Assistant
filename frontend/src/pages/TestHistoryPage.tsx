import { useEffect, useMemo, useState } from "react"
import { Link, useParams, useSearchParams } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { ChevronDown, Play, Sparkles, X } from "lucide-react"

import { Button } from "@/components/ui/button"
import { StatusBadge } from "@/components/shared/StatusBadge"
import { cn, formatDateTime, METHOD_STYLES } from "@/lib/utils"
import { getProjectHistory, type EndpointHistory, type HistoryEvent } from "@/lib/history"
import { useListFilters } from "@/hooks/useListFilters"
import { ListToolbar, type ListFilterSelect } from "@/components/shared/ListToolbar"
import { Pagination } from "@/components/shared/Pagination"
import { ListEmptyState } from "@/components/shared/ListEmptyState"

const FILTER_KEYS = ["endpointId", "type"] as const
// Mỗi trang 10 endpoint; mỗi endpoint hiện tối đa 20 sự kiện mới nhất, bấm "Hiện thêm" để xem tiếp.
const PAGE_SIZE = 10
const EVENTS_STEP = 20

export function TestHistoryPage() {
  const { id } = useParams<{ id: string }>()
  const projectId = id!
  const [searchParams] = useSearchParams()
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set())
  const lf = useListFilters({ filterKeys: FILTER_KEYS, defaultPageSize: PAGE_SIZE })
  const { q, filters, page: currentPage, clampPage, applyParams } = lf

  // Đến từ nút "Lịch sử chạy test" ở TestCasesPage (?endpointId=...&onlyExecution=1): đổi MỘT lần thành bộ lọc loại sự kiện
  // "Chạy test" (type=EXECUTION) rồi bỏ tham số cũ - sau đó mọi thứ chỉ còn 1 cơ chế lọc.
  const legacyOnlyExecution = searchParams.get("onlyExecution") === "1"
  useEffect(() => {
    if (!legacyOnlyExecution) return
    applyParams(
      (params) => {
        params.delete("onlyExecution")
        if (!params.get("type")) params.set("type", "EXECUTION")
      },
      { replace: true }
    )
  }, [legacyOnlyExecution, applyParams])

  const typeFilter = filters.type
  const onlyExecution = typeFilter === "EXECUTION"
  const isFiltered = lf.activeCount > 0

  const { data: rawHistory, isLoading, isError } = useQuery({
    queryKey: ["history", projectId],
    queryFn: () => getProjectHistory(projectId),
  })

  const history = useMemo<EndpointHistory[]>(() => {
    const needle = q.toLowerCase()
    return (rawHistory ?? [])
      .filter((endpointHistory) => !filters.endpointId || endpointHistory.endpointId === filters.endpointId)
      .filter(
        (endpointHistory) =>
          !needle || `${endpointHistory.endpointMethod} ${endpointHistory.endpointPath}`.toLowerCase().includes(needle)
      )
      .map((endpointHistory) => ({
        ...endpointHistory,
        events: typeFilter ? endpointHistory.events.filter((event) => event.type === typeFilter) : endpointHistory.events,
      }))
      // Endpoint không còn sự kiện nào khớp loại đang lọc thì ẩn hẳn thay vì hiện 1 khối trống.
      .filter((endpointHistory) => !typeFilter || endpointHistory.events.length > 0)
  }, [rawHistory, q, filters.endpointId, typeFilter])

  const totalPages = Math.ceil(history.length / PAGE_SIZE)
  const safePage = Math.min(currentPage, Math.max(totalPages - 1, 0))
  const pageItems = useMemo(() => history.slice(safePage * PAGE_SIZE, (safePage + 1) * PAGE_SIZE), [history, safePage])
  useEffect(() => {
    clampPage(totalPages)
  }, [totalPages, clampPage])

  function toggleExpanded(eventId: string) {
    setExpandedIds((prev) => {
      const next = new Set(prev)
      if (next.has(eventId)) {
        next.delete(eventId)
      } else {
        next.add(eventId)
      }
      return next
    })
  }

  const selects: ListFilterSelect[] = [
    {
      key: "type",
      label: "Lọc theo loại sự kiện",
      allLabel: "Mọi sự kiện",
      value: typeFilter,
      className: "sm:w-44",
      options: [
        { value: "EXECUTION", label: "Chạy test" },
        { value: "GENERATION", label: "Sinh test case" },
      ],
    },
    {
      key: "endpointId",
      label: "Lọc theo endpoint",
      allLabel: "Tất cả endpoint",
      value: filters.endpointId,
      className: "sm:w-64",
      options: (rawHistory ?? []).map((endpointHistory) => ({
        value: endpointHistory.endpointId,
        label: `${endpointHistory.endpointMethod} ${endpointHistory.endpointPath}`,
      })),
    },
  ]

  return (
    <div>
      <div className="flex flex-wrap items-center gap-3">
        <h2 className="text-lg font-semibold">Lịch sử kiểm thử</h2>
        {isFiltered && (
          <Button size="xs" variant="outline" onClick={lf.reset}>
            <X className="h-3 w-3" />
            Bỏ lọc, xem tất cả lịch sử
          </Button>
        )}
      </div>
      {onlyExecution && (
        <p className="mt-1 text-xs text-muted-foreground">
          Đang lọc: chỉ hiện lần chạy test{filters.endpointId ? " của endpoint đang chọn" : ""} — đã ẩn sự kiện "Sinh test case".
        </p>
      )}

      {isLoading && <p className="mt-6 text-muted-foreground">Đang tải...</p>}
      {isError && <p className="mt-6 text-destructive">Không tải được lịch sử kiểm thử, vui lòng thử lại.</p>}

      {rawHistory && rawHistory.length > 0 && (
        <>
          <ListToolbar
            className="mt-4"
            searchValue={lf.searchInput}
            onSearchChange={lf.setSearchInput}
            searchPlaceholder="Tìm endpoint theo đường dẫn hoặc method..."
            searchLabel="Tìm endpoint trong lịch sử"
            selects={selects}
            onSelectChange={(key, value) => lf.setFilter(key as (typeof FILTER_KEYS)[number], value)}
            activeFilterCount={lf.activeCount}
            onReset={lf.reset}
          />
          <Pagination
            className="mt-3"
            page={safePage}
            totalPages={totalPages}
            totalElements={history.length}
            shownCount={pageItems.length}
            size={PAGE_SIZE}
            itemLabel="endpoint"
            onPageChange={lf.setPage}
          />
        </>
      )}

      {!isLoading && !isError && history.length === 0 && (
        <div className="mt-4">
          <ListEmptyState
            filtered={isFiltered}
            emptyText="Chưa có lịch sử kiểm thử nào."
            filteredText="Không có lịch sử nào khớp bộ lọc."
            onReset={lf.reset}
          />
        </div>
      )}

      <div className="mt-4 flex flex-col gap-4">
        {pageItems.map((endpointHistory) => (
          <EndpointHistoryCard
            key={`${endpointHistory.endpointId}:${typeFilter}`}
            endpointHistory={endpointHistory}
            projectId={projectId}
            expandedIds={expandedIds}
            onToggleExpanded={toggleExpanded}
          />
        ))}
      </div>

      {history.length > 0 && (
        <Pagination
          className="mt-4"
              announce={false}
          page={currentPage}
          totalPages={totalPages}
          totalElements={history.length}
          shownCount={pageItems.length}
          size={PAGE_SIZE}
          itemLabel="endpoint"
          onPageChange={lf.setPage}
        />
      )}
    </div>
  )
}

function EndpointHistoryCard({
  endpointHistory,
  projectId,
  expandedIds,
  onToggleExpanded,
}: {
  endpointHistory: EndpointHistory
  projectId: string
  expandedIds: Set<string>
  onToggleExpanded: (eventId: string) => void
}) {
  const [visibleEvents, setVisibleEvents] = useState(EVENTS_STEP)
  // Backend trả sự kiện cũ nhất trước; đảo lại để mới nhất lên đầu thì "20 sự kiện đầu" mới là 20 sự kiện mới nhất.
  const newestFirst = useMemo(() => [...endpointHistory.events].reverse(), [endpointHistory.events])
  const shown = newestFirst.slice(0, visibleEvents)
  return (
    <div className="rounded-lg border border-border p-4">
      <div className="flex flex-wrap items-center gap-3">
        <span
          className={cn(
            "w-16 shrink-0 rounded-md px-2 py-0.5 text-center text-xs font-semibold",
            METHOD_STYLES[endpointHistory.endpointMethod] ?? "bg-muted text-muted-foreground"
          )}
        >
          {endpointHistory.endpointMethod}
        </span>
        <span className="truncate font-mono text-sm">{endpointHistory.endpointPath}</span>
        <span className="text-xs text-muted-foreground">
          {endpointHistory.currentTestCaseCount} test case · {endpointHistory.totalRuns} lần chạy
        </span>
      </div>

      <ul className="mt-4 ml-1 flex flex-col gap-3 border-l border-border pl-5">
        {shown.map((event) => (
          <li key={event.id} className="relative">
            <span className="absolute top-1.5 -left-[25px] h-2.5 w-2.5 rounded-full border-2 border-background bg-primary" />
            {event.type === "GENERATION" ? (
              <GenerationEventRow event={event} isExpanded={expandedIds.has(event.id)} onToggle={() => onToggleExpanded(event.id)} />
            ) : (
              <ExecutionEventRow
                event={event}
                projectId={projectId}
                endpointId={endpointHistory.endpointId}
                currentTestCaseCount={endpointHistory.currentTestCaseCount}
              />
            )}
          </li>
        ))}
      </ul>
      {endpointHistory.events.length > visibleEvents && (
        <button
          type="button"
          className="mt-3 text-xs text-primary underline underline-offset-4"
          onClick={() => setVisibleEvents((count) => count + EVENTS_STEP)}
        >
          Hiện thêm {EVENTS_STEP} sự kiện (đang hiện {visibleEvents}/{endpointHistory.events.length})
        </button>
      )}
    </div>
  )
}

function GenerationEventRow({
  event,
  isExpanded,
  onToggle,
}: {
  event: HistoryEvent
  isExpanded: boolean
  onToggle: () => void
}) {
  return (
    <div className="rounded-md border border-border p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <Sparkles className="h-4 w-4 shrink-0 text-violet-600 dark:text-violet-400" />
          <span className="text-sm font-medium">Sinh test case · {formatDateTime(event.occurredAt)}</span>
        </div>
        <Button size="sm" variant="ghost" onClick={onToggle}>
          Xem chi tiết
          <ChevronDown className={cn("h-4 w-4 transition-transform", isExpanded && "rotate-180")} />
        </Button>
      </div>
      <p className="mt-1 text-xs text-muted-foreground">Đã sinh {event.generatedCount} test case (AI)</p>

      {isExpanded && (
        <ul className="mt-3 flex flex-col gap-1.5 border-t border-border pt-3">
          {event.snapshotItems?.map((item, index) => (
            <li key={index} className="flex items-center gap-3 rounded-md border border-border p-2 text-sm">
              <span className="min-w-0 flex-1 truncate">{item.name}</span>
              <span className="shrink-0 text-xs text-muted-foreground">→ {item.expectedStatus}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function ExecutionEventRow({
  event,
  projectId,
  endpointId,
  currentTestCaseCount,
}: {
  event: HistoryEvent
  projectId: string
  endpointId: string
  currentTestCaseCount: number
}) {
  return (
    <div className="rounded-md border border-border p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <Play className="h-4 w-4 shrink-0 text-blue-600 dark:text-blue-400" />
          <span className="text-sm font-medium">Chạy test · {formatDateTime(event.occurredAt)}</span>
        </div>
        <Button
          size="sm"
          variant="ghost"
          nativeButton={false}
          render={<Link to={`/projects/${projectId}/executions/${event.executionId}?endpointId=${endpointId}`} />}
        >
          Xem chi tiết
        </Button>
      </div>
      {event.selectedCount === 0 ? (
        <p className="mt-1 text-xs text-muted-foreground">Không có test case nào của endpoint này được chạy.</p>
      ) : (
        <div className="mt-1 flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
          <span>{event.selectedCount}/{currentTestCaseCount} test case</span>
          <span>·</span>
          <StatusBadge status="PASSED" compact />
          <span>{event.passCount}</span>
          {(event.failCount ?? 0) > 0 && (
            <>
              <StatusBadge status="FAILED" compact />
              <span>{event.failCount}</span>
            </>
          )}
        </div>
      )}
      {(event.otherEndpointCount ?? 0) > 0 && (
        <p className="mt-0.5 text-xs text-muted-foreground italic">
          Chạy chung với {event.otherEndpointCount} endpoint khác
        </p>
      )}
    </div>
  )
}
