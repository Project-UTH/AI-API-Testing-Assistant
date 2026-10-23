import { memo, useCallback, useEffect, useMemo, useRef, useState } from "react"
import { Link } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { ChevronDown, Coins, Sparkles } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { cn, METHOD_STYLES } from "@/lib/utils"
import { listEndpoints, type Endpoint } from "@/lib/endpoints"
import { useListFilters } from "@/hooks/useListFilters"
import { ListToolbar, type ListFilterSelect } from "@/components/shared/ListToolbar"
import { Pagination } from "@/components/shared/Pagination"
import { ListEmptyState } from "@/components/shared/ListEmptyState"
import { GenerationPlanReport } from "@/components/projects/GenerationPlanReport"
import { AuthHintBanner } from "@/components/projects/AuthHintBanner"
import { GenerateWizardDialog } from "@/components/generation/GenerateWizardDialog"
import { getDashboardSummary } from "@/lib/dashboard"
import { coverageRowCounts, getCoverageSummary, type CoverageEndpointRow } from "@/lib/coverage"
import { CoverageCountsText, TestStateChip } from "@/components/coverage/TestStateChip"

interface EndpointListProps {
  projectId: string
}

const FILTER_KEYS = ["method", "status"] as const
const PAGE_SIZE = 50
const METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE"]

interface EndpointRowProps {
  projectId: string
  endpoint: Endpoint
  row: CoverageEndpointRow | undefined
  selected: boolean
  expanded: boolean
  onToggleSelect: (endpointId: string, checked: boolean) => void
  onToggleExpanded: (endpointId: string) => void
  onGenerate: (endpointId: string) => void
}

/** 1 endpoint. memo: tick chọn / mở rộng 1 dòng hay gõ tìm kiếm không làm vẽ lại các dòng còn lại. */
const EndpointRow = memo(function EndpointRow({ projectId, endpoint, row, selected, expanded, onToggleSelect, onToggleExpanded, onGenerate }: EndpointRowProps) {
  const counts = row ? coverageRowCounts(row) : null
  return (
    <li className="rounded-lg border border-border p-3">
      <div className="flex items-center gap-3">
        <Checkbox
          aria-label={`Chọn ${endpoint.method} ${endpoint.path}`}
          checked={selected}
          onCheckedChange={(checked) => onToggleSelect(endpoint.id, checked === true)}
        />
        <span
          className={cn(
            "w-16 shrink-0 rounded-md px-2 py-0.5 text-center text-xs font-semibold",
            METHOD_STYLES[endpoint.method] ?? "bg-muted text-muted-foreground"
          )}
        >
          {endpoint.method}
        </span>
        <span className="min-w-0 truncate font-mono text-sm">{endpoint.path}</span>
        {endpoint.summary && (
          <span className="min-w-0 flex-1 truncate text-sm text-muted-foreground" title={endpoint.summary}>
            {endpoint.summary}
          </span>
        )}
        {!endpoint.summary && <span className="flex-1" />}
        {endpoint.missingFromSpec && (
          <span
            className="shrink-0 rounded-full bg-destructive/10 px-2 py-0.5 text-xs font-medium text-destructive"
            title="Lần import gần nhất không còn thấy endpoint này trong spec. Test case của nó vẫn được giữ - khi chạy có thể nhận 404."
          >
            Không còn trong spec
          </span>
        )}
        {!endpoint.missingFromSpec && endpoint.schemaChangedAt && (
          <span
            className="shrink-0 rounded-full bg-amber-500/10 px-2 py-0.5 text-xs font-medium text-amber-700 dark:text-amber-400"
            title="Spec của endpoint này đã đổi ở lần import gần nhất - test case có thể lỗi thời, nên xem lại hoặc chạy lại."
          >
            Spec đổi {new Date(endpoint.schemaChangedAt).toLocaleDateString("vi-VN", { day: "2-digit", month: "2-digit" })}
          </span>
        )}
        <TestStateChip state={row?.testState ?? (endpoint.testCaseCount === 0 ? "NOT_GENERATED" : null)} />
        {endpoint.testCaseCount > 0 && (
          <span className="shrink-0 text-xs text-muted-foreground">
            <span className="font-medium text-foreground">{endpoint.testCaseCount}</span>{" "}
            {row?.testState === "NOT_GENERATED" ? "test nghiệp vụ (không tính vào độ phủ EP/BVA)" : "test"}
          </span>
        )}
        {endpoint.testCaseCount > 0 && counts && (
          <CoverageCountsText
            relevant={counts.relevant}
            covered={counts.covered}
            executed={counts.executed}
            certain={counts.certain}
            doubtful={counts.doubtful}
            missing={counts.missing}
            specGap={counts.specGap}
            baselineVerified={row?.baselineVerified}
          />
        )}
        <Button
          type="button"
          size="sm"
          variant="outline"
          className="shrink-0"
          aria-label={`Sinh test case ${endpoint.method} ${endpoint.path}`}
          onClick={() => onGenerate(endpoint.id)}
        >
          <Sparkles className="h-3.5 w-3.5" />
          Sinh test case
        </Button>
        {endpoint.testCaseCount > 0 && (
          <>
            <Link to={`/projects/${projectId}/test-cases?endpointId=${endpoint.id}`} className="shrink-0 text-xs text-primary underline underline-offset-4">
              Xem test case
            </Link>
            <button
              type="button"
              aria-label={`${expanded ? "Thu gọn" : "Mở"} chi tiết ${endpoint.method} ${endpoint.path}`}
              aria-expanded={expanded}
              className="shrink-0 rounded-md p-1 text-muted-foreground hover:bg-muted hover:text-foreground"
              onClick={() => onToggleExpanded(endpoint.id)}
            >
              <ChevronDown className={cn("h-4 w-4 transition-transform", expanded && "rotate-180")} />
            </button>
          </>
        )}
      </div>
      {expanded && endpoint.testCaseCount > 0 && (
        <GenerationPlanReport projectId={projectId} endpointId={endpoint.id} coverage={row} />
      )}
    </li>
  )
})

export function EndpointList({ projectId }: EndpointListProps) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["endpoints", projectId],
    queryFn: () => listEndpoints(projectId),
  })

  // Cùng queryKey với Dashboard - dùng chung cache, không gọi thêm request.
  const { data: summary } = useQuery({
    queryKey: ["dashboard-summary"],
    queryFn: () => getDashboardSummary(),
  })

  // Số đếm coverage từng endpoint (bản nhẹ: không kéo từng mục chi tiết của cả project), truyền xuống báo cáo kế hoạch sinh test case.
  const { data: coverage } = useQuery({
    queryKey: ["project-coverage", projectId, "summary"],
    queryFn: () => getCoverageSummary(projectId),
  })
  const coverageByEndpointId = useMemo(
    () => new Map((coverage?.endpoints ?? []).map((row) => [row.endpointId, row])),
    [coverage]
  )
  const lf = useListFilters({ filterKeys: FILTER_KEYS, defaultPageSize: PAGE_SIZE })
  const listTopRef = useRef<HTMLDivElement>(null)

  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  // Endpoint đang mở phần chi tiết (coverage + báo cáo lần sinh) - mặc định gập để danh sách gọn, 1 dòng/endpoint.
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set())
  // Mặc định cả 3 đều bật - giữ đúng hành vi cũ (luôn sinh đủ Positive/Negative/Boundary) cho tới
  // khi người dùng chủ động bỏ tích bớt.
  const [wizardIds, setWizardIds] = useState<string[] | null>(null)

  const endpoints = useMemo(() => data?.data ?? [], [data])

  // Lọc + phân trang ngay trên trình duyệt: danh sách endpoint nhỏ (hàng trăm), cái nặng là số dòng được vẽ ra chứ không phải dữ liệu.
  const { q, filters, page: currentPage, clampPage } = lf
  const filtered = useMemo(() => {
    const needle = q.toLowerCase()
    return endpoints.filter((endpoint) => {
      if (filters.method && endpoint.method !== filters.method) return false
      if (needle && !`${endpoint.method} ${endpoint.path} ${endpoint.summary ?? ""}`.toLowerCase().includes(needle)) return false
      if (filters.status === "nocases") return endpoint.testCaseCount === 0
      // Chưa tải xong coverage thì chưa lọc theo "thiếu mục"/"đang Fail" (nếu không danh sách tạm thời rỗng oan).
      if (filters.status === "gaps") return !coverage || (coverageByEndpointId.get(endpoint.id)?.missingCount ?? 0) > 0
      if (filters.status === "failing") return !coverage || (coverageByEndpointId.get(endpoint.id)?.failingCount ?? 0) > 0
      return true
    })
  }, [endpoints, q, filters, coverage, coverageByEndpointId])
  const totalPages = Math.ceil(filtered.length / PAGE_SIZE)
  const pageRows = useMemo(() => filtered.slice(currentPage * PAGE_SIZE, (currentPage + 1) * PAGE_SIZE), [filtered, currentPage])

  useEffect(() => {
    clampPage(totalPages)
  }, [totalPages, clampPage])

  // Bỏ khỏi vùng chọn các endpoint không còn tồn tại (vd sau khi import lại ở chế độ thay thế).
  useEffect(() => {
    if (!data) return
    const existing = new Set(data.data.map((endpoint) => endpoint.id))
    setSelectedIds((prev) => {
      const next = new Set([...prev].filter((endpointId) => existing.has(endpointId)))
      return next.size === prev.size ? prev : next
    })
  }, [data])

  const toggleSelect = useCallback((endpointId: string, checked: boolean) => {
    setSelectedIds((prev) => {
      const next = new Set(prev)
      if (checked) {
        next.add(endpointId)
      } else {
        next.delete(endpointId)
      }
      return next
    })
  }, [])

  const toggleExpanded = useCallback((endpointId: string) => {
    setExpandedIds((prev) => {
      const next = new Set(prev)
      if (next.has(endpointId)) {
        next.delete(endpointId)
      } else {
        next.add(endpointId)
      }
      return next
    })
  }, [])

  const openWizardFor = useCallback((endpointId: string) => setWizardIds([endpointId]), [])


  if (isLoading) {
    return <p className="mt-4 text-muted-foreground">Đang tải danh sách endpoint...</p>
  }

  if (isError) {
    return (
      <p className="mt-4 text-destructive">
        Không tải được danh sách endpoint, vui lòng thử lại.
      </p>
    )
  }

  if (endpoints.length === 0) {
    return (
      <div className="mt-4 rounded-lg border border-dashed border-border p-6 text-center text-muted-foreground">
        Chưa có endpoint nào. Bấm "Import OpenAPI" để nạp định nghĩa API.
      </div>
    )
  }

  const pageSelectedCount = pageRows.filter((endpoint) => selectedIds.has(endpoint.id)).length
  const allOnPageSelected = pageRows.length > 0 && pageSelectedCount === pageRows.length
  const filteredSelectedCount = filtered.filter((endpoint) => selectedIds.has(endpoint.id)).length
  const hiddenSelected = selectedIds.size - filteredSelectedCount
  const selects: ListFilterSelect[] = [
    {
      key: "method",
      label: "Lọc theo method",
      allLabel: "Mọi method",
      value: filters.method,
      className: "sm:w-32",
      options: METHODS.map((method) => ({ value: method, label: method })),
    },
    {
      key: "status",
      label: "Lọc theo tình trạng test",
      allLabel: "Mọi tình trạng",
      value: filters.status,
      className: "sm:w-52",
      options: [
        { value: "nocases", label: "Chưa có test case" },
        ...(coverage
          ? [
              { value: "gaps", label: "Còn thiếu mục cần kiểm" },
              { value: "failing", label: "Có mục đang Fail" },
            ]
          : []),
      ],
    },
  ]
  const todayTokens = summary?.aiTokensToday ?? 0
  const dailyLimit = summary?.aiDailyTokenLimit ?? 0
  const nearOrOverQuota = dailyLimit > 0 && todayTokens >= dailyLimit
  // Số token chỉ đáng nhìn khi đã dùng đáng kể (từ nửa hạn mức) - lúc còn thoải mái nó chỉ là 1 khối nhiễu ngay đầu danh sách.
  const showTokenUsage = dailyLimit > 0 && todayTokens >= dailyLimit / 2

  return (
    <div ref={listTopRef} className="mt-4 flex scroll-mt-4 flex-col gap-3">
      <AuthHintBanner projectId={projectId} />
      {summary && showTokenUsage && (
        <div
          className={cn(
            "flex flex-wrap items-center gap-2 rounded-md border px-3 py-2 text-sm",
            nearOrOverQuota
              ? "border-destructive/40 bg-destructive/5"
              : "border-violet-500/30 bg-violet-500/5"
          )}
        >
          <Coins className={cn("h-4 w-4 shrink-0", nearOrOverQuota ? "text-destructive" : "text-violet-500")} />
          <span className="text-muted-foreground">Token AI đã dùng hôm nay:</span>
          <span
            className={cn(
              "text-base font-bold tabular-nums",
              nearOrOverQuota ? "text-destructive" : "text-foreground"
            )}
          >
            {todayTokens.toLocaleString("vi-VN")} / {dailyLimit.toLocaleString("vi-VN")}
          </span>
          {nearOrOverQuota && (
            <span className="font-medium text-destructive">Đã đạt giới hạn hôm nay - Sinh test case có dùng AI sẽ bị chặn</span>
          )}
        </div>
      )}
      <p className="text-sm text-muted-foreground">
        Tick endpoint rồi bấm Sinh test case: AI sinh 1 test gốc hợp lệ (tốn token), hệ thống chạy thật xác nhận và bổ sung các nhóm test bạn chọn.
      </p>

      <ListToolbar
        searchValue={lf.searchInput}
        onSearchChange={lf.setSearchInput}
        searchPlaceholder="Tìm endpoint theo đường dẫn, method hoặc mô tả..."
        searchLabel="Tìm endpoint"
        selects={selects}
        onSelectChange={(key, value) => lf.setFilter(key as (typeof FILTER_KEYS)[number], value)}
        activeFilterCount={lf.activeCount}
        onReset={lf.reset}
      />

      <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
        <div className="flex min-w-0 flex-1 items-center gap-3">
          {/* Ô chọn tất cả các endpoint đang hiển thị ở trang này: tick là chọn hết, bỏ tick là bỏ chọn (có trạng thái "chọn một phần"). */}
          <Checkbox
            aria-label="Chọn tất cả endpoint trên trang này"
            checked={allOnPageSelected}
            indeterminate={pageSelectedCount > 0 && !allOnPageSelected}
            disabled={pageRows.length === 0}
            onCheckedChange={(checked) =>
              setSelectedIds((prev) => {
                const next = new Set(prev)
                for (const endpoint of pageRows) {
                  if (checked === true) next.add(endpoint.id)
                  else next.delete(endpoint.id)
                }
                return next
              })
            }
          />
          <Pagination
            className="min-w-0 flex-1"
            page={currentPage}
            totalPages={totalPages}
            totalElements={filtered.length}
            shownCount={pageRows.length}
            size={PAGE_SIZE}
            itemLabel="endpoint"
            onPageChange={(next) => {
              lf.setPage(next)
              listTopRef.current?.scrollIntoView({ block: "start" })
            }}
          />
        </div>
        {filtered.length > pageRows.length && filteredSelectedCount < filtered.length && (
          <button
            type="button"
            className="text-primary underline underline-offset-4"
            onClick={() => setSelectedIds((prev) => new Set([...prev, ...filtered.map((endpoint) => endpoint.id)]))}
          >
            Chọn cả {filtered.length} endpoint khớp bộ lọc
          </button>
        )}
      </div>

      {filtered.length === 0 && (
        <ListEmptyState
          filtered={lf.activeCount > 0}
          emptyText="Chưa có endpoint nào."
          filteredText="Không có endpoint nào khớp bộ lọc."
          onReset={lf.reset}
        />
      )}

      <ul className="flex flex-col gap-2">
        {pageRows.map((endpoint) => {
          return (
            <EndpointRow
              key={endpoint.id}
              projectId={projectId}
              endpoint={endpoint}
              row={coverageByEndpointId.get(endpoint.id)}
              selected={selectedIds.has(endpoint.id)}
              expanded={expandedIds.has(endpoint.id)}
              onToggleSelect={toggleSelect}
              onToggleExpanded={toggleExpanded}
              onGenerate={openWizardFor}
            />
          )
        })}
      </ul>


      {wizardIds && endpoints.some((e) => wizardIds.includes(e.id)) && (
        <GenerateWizardDialog
          key={wizardIds.join(",")}
          projectId={projectId}
          endpoints={endpoints.filter((e) => wizardIds.includes(e.id))}
          open
          onOpenChange={(open) => !open && setWizardIds(null)}
        />
      )}

      {selectedIds.size > 0 && (
        <div className="sticky bottom-4 z-20 flex flex-wrap items-center justify-between gap-3 rounded-lg border border-border bg-card p-3 shadow-lg">
          <span className="text-sm font-medium">
            Đã chọn {selectedIds.size} endpoint
            {hiddenSelected > 0 && <span className="font-normal text-muted-foreground"> ({hiddenSelected} ngoài bộ lọc hiện tại)</span>}
          </span>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setSelectedIds(new Set())}
          >
            Bỏ chọn tất cả
          </Button>
          <Button
            size="sm"
            title="AI sinh test gốc hợp lệ, hệ thống chạy thật xác nhận rồi bổ sung các nhóm test bạn chọn (chạy nền, xem từng bước, huỷ được)"
            onClick={() => {
              setWizardIds(Array.from(selectedIds))
              setSelectedIds(new Set())
            }}
          >
            <Sparkles className="h-4 w-4" />
            Sinh test case ({selectedIds.size})
          </Button>
        </div>
      )}
    </div>
  )
}
