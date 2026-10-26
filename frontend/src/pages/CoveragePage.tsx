import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { useParams, useSearchParams } from "react-router-dom"
import { keepPreviousData, useQuery } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"
import { ApiError } from "@/lib/api"
import { getEndpointCoverage, getProjectCoveragePage, getTestStateDisplay, TEST_STATE_ORDER } from "@/lib/coverage"
import { useListFilters } from "@/hooks/useListFilters"
import { ListToolbar, type ListFilterSelect } from "@/components/shared/ListToolbar"
import { Pagination } from "@/components/shared/Pagination"
import { ListEmptyState } from "@/components/shared/ListEmptyState"
import { CoverageOverviewCard } from "@/components/shared/CoverageMatrixChart"
import { CoverageEndpointRow } from "@/components/coverage/CoverageEndpointRow"
import { BulkAuthBar } from "@/components/coverage/BulkAuthBar"
import { RoleMatrixPanel } from "@/components/coverage/RoleMatrixPanel"

const FILTER_KEYS = ["method", "status", "state"] as const
const PAGE_SIZE = 25
const METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE"]

/**
 * Module 15c - Coverage Matrix. Toàn bộ số liệu tính tất định từ OpenAPI spec +
 * test case hiện có (không gọi AI) - xem EndpointCoverageService (backend).
 * Module 15j - Coverage Gap Analysis: mỗi endpoint chỉ rõ còn thiếu mục nào, vì sao cần, bằng chứng, và
 * bổ sung ngay (hệ thống tự tạo, 0 token).
 * Module 17 (G4) - danh sách endpoint theo trang + tìm kiếm/lọc; số tổng vẫn tính trên TOÀN project.
 */
export function CoveragePage() {
  const { id } = useParams<{ id: string }>()
  const projectId = id!
  const [searchParams, setSearchParams] = useSearchParams()
  // ?endpoint=<id> (link từ trang Project) - ghim endpoint đó ở đầu danh sách, mở sẵn và cuộn tới (nó có thể không nằm ở trang hiện tại).
  const focusEndpointId = searchParams.get("endpoint")
  const lf = useListFilters({ filterKeys: FILTER_KEYS, defaultPageSize: PAGE_SIZE })
  const topRef = useRef<HTMLDivElement>(null)
  const [expandedEndpointIds, setExpandedEndpointIds] = useState<Set<string>>(new Set(focusEndpointId ? [focusEndpointId] : []))

  const { data, isLoading, isError, isFetching, isPlaceholderData } = useQuery({
    queryKey: ["project-coverage", projectId, "page", lf.params],
    queryFn: () =>
      getProjectCoveragePage(projectId, {
        page: lf.page,
        size: lf.size,
        q: lf.q,
        method: lf.filters.method,
        onlyGaps: lf.filters.status === "gaps",
        onlyFailing: lf.filters.status === "failing",
        state: lf.filters.state || undefined,
      }),
    placeholderData: keepPreviousData,
  })

  const { data: pinned, isError: pinnedError, error: pinnedErrorValue } = useQuery({
    queryKey: ["project-coverage", projectId, "endpoint", focusEndpointId],
    queryFn: () => getEndpointCoverage(projectId, focusEndpointId!),
    enabled: Boolean(focusEndpointId),
    retry: false,
  })

  const { clampPage } = lf
  useEffect(() => {
    if (data && !isPlaceholderData) clampPage(data.endpointsPage.totalPages)
  }, [data, isPlaceholderData, clampPage])

  // Cuộn tới thẻ được ghim đúng 1 lần cho mỗi endpoint (không cuộn lại mỗi lần dữ liệu làm mới).
  const scrolledFor = useRef<string | null>(null)
  const pageLoaded = Boolean(data)
  useEffect(() => {
    if (!focusEndpointId || !pinned || !pageLoaded || scrolledFor.current === focusEndpointId) return
    const element = document.getElementById(`coverage-endpoint-${focusEndpointId}`)
    if (element) {
      scrolledFor.current = focusEndpointId
      element.scrollIntoView({ block: "start" })
    }
  }, [focusEndpointId, pinned, pageLoaded])

  const toggleExpanded = useCallback((endpointId: string) => {
    setExpandedEndpointIds((prev) => {
      const next = new Set(prev)
      if (next.has(endpointId)) next.delete(endpointId)
      else next.add(endpointId)
      return next
    })
  }, [])

  function unpin() {
    setSearchParams((prev) => {
      const params = new URLSearchParams(prev)
      params.delete("endpoint")
      return params
    })
  }

  function changePage(next: number) {
    lf.setPage(next)
    topRef.current?.scrollIntoView({ block: "start" })
  }

  const endpoints = useMemo(
    () => (focusEndpointId && pinned ? (data?.endpoints ?? []).filter((endpoint) => endpoint.endpointId !== focusEndpointId) : (data?.endpoints ?? [])),
    [data, focusEndpointId, pinned]
  )
  const pinnedNotFound = pinnedError && pinnedErrorValue instanceof ApiError && pinnedErrorValue.code.includes("NOT_FOUND")

  const selects: ListFilterSelect[] = [
    {
      key: "method",
      label: "Lọc theo method",
      allLabel: "Mọi method",
      value: lf.filters.method,
      className: "sm:w-32",
      options: METHODS.map((method) => ({ value: method, label: method })),
    },
    {
      key: "status",
      label: "Lọc theo tình trạng",
      allLabel: "Mọi tình trạng",
      value: lf.filters.status,
      className: "sm:w-52",
      options: [
        { value: "gaps", label: "Còn thiếu mục cần kiểm" },
        { value: "failing", label: "Có mục đang Fail" },
      ],
    },
    {
      key: "state",
      label: "Lọc theo trạng thái kiểm thử",
      allLabel: "Mọi trạng thái",
      value: lf.filters.state,
      className: "sm:w-44",
      options: TEST_STATE_ORDER.map((state) => ({ value: state, label: getTestStateDisplay(state)!.label })),
    },
  ]

  return (
    <div ref={topRef}>
      <h2 className="text-lg font-semibold">Coverage Matrix</h2>
      <p className="mt-1 text-sm text-muted-foreground">
        Các tình huống cần kiểm tra được suy ra từ spec OpenAPI, đối chiếu với test case hiện có. Tính tất định, không dùng AI.
      </p>

      {isLoading && <p className="mt-6 text-muted-foreground">Đang tính coverage...</p>}
      {isError && (
        <div className="mt-6 flex flex-col gap-3">
          <p className="text-destructive">Không tải được dữ liệu coverage, vui lòng thử lại.</p>
          {lf.activeCount > 0 && (
            <div>
              <Button type="button" size="sm" variant="outline" onClick={lf.reset}>
                Xoá bộ lọc ({lf.activeCount}) và thử lại
              </Button>
            </div>
          )}
        </div>
      )}

      {data && (
        <div className="mt-6 flex flex-col gap-4">
          <CoverageOverviewCard
            overview={data.overview}
            selectedState={lf.filters.state}
            onSelectState={(state) => lf.setFilter("state", state ?? "")}
          />
          <p className="-mt-2 text-xs text-muted-foreground">
            Số liệu trên tính cho toàn project ({data.overview.endpointCount} endpoint, {data.overview.endpointsWithGaps} endpoint còn thiếu mục
            cần kiểm), không đổi theo bộ lọc bên dưới.
          </p>

          <BulkAuthBar projectId={projectId} endpointCount={data.overview.endpointCount} />
          <RoleMatrixPanel projectId={projectId} />

          <ListToolbar
            searchValue={lf.searchInput}
            onSearchChange={lf.setSearchInput}
            searchPlaceholder="Tìm endpoint theo đường dẫn hoặc method..."
            searchLabel="Tìm endpoint"
            selects={selects}
            onSelectChange={(key, value) => lf.setFilter(key as (typeof FILTER_KEYS)[number], value)}
            activeFilterCount={lf.activeCount}
            onReset={lf.reset}
          />
          {lf.filters.status === "gaps" && (
            <p className="-mt-2 text-xs text-muted-foreground">
              Bổ sung xong mục cuối cùng thì endpoint sẽ biến khỏi danh sách này ở lần tải lại.
            </p>
          )}

          {focusEndpointId && (
            <div className="flex flex-col gap-2">
              {pinned ? (
                <>
                  <CoverageEndpointRow
                    projectId={projectId}
                    endpoint={pinned}
                    expanded={expandedEndpointIds.has(pinned.endpointId)}
                    onToggle={toggleExpanded}
                    pinned
                  />
                  <div>
                    <Button type="button" size="sm" variant="ghost" onClick={unpin}>
                      Bỏ ghim endpoint này
                    </Button>
                  </div>
                </>
              ) : pinnedError ? (
                <p className="rounded-md bg-muted p-3 text-sm text-muted-foreground">
                  {pinnedNotFound ? "Không tìm thấy endpoint được chọn (có thể đã bị xoá)." : "Không tải được endpoint được chọn, vui lòng thử lại."}{" "}
                  <button type="button" className="underline" onClick={unpin}>
                    Bỏ liên kết
                  </button>
                </p>
              ) : (
                <p className="text-sm text-muted-foreground">Đang tải endpoint được chọn...</p>
              )}
            </div>
          )}

          <Pagination
            page={data.endpointsPage.page}
            totalPages={data.endpointsPage.totalPages}
            totalElements={data.endpointsPage.totalElements}
            shownCount={data.endpoints.length}
            size={data.endpointsPage.size}
            isFetching={isFetching}
            itemLabel="endpoint"
            onPageChange={changePage}
          />

          {data.endpointsPage.totalElements === 0 ? (
            <ListEmptyState
              filtered={lf.activeCount > 0}
              emptyText="Project chưa có endpoint nào."
              filteredText="Không có endpoint nào khớp bộ lọc."
              onReset={lf.reset}
            />
          ) : (
            <div className={cn("flex flex-col gap-2", isPlaceholderData && "opacity-60 transition-opacity")} aria-busy={isFetching}>
              {endpoints.map((endpoint) => (
                <CoverageEndpointRow
                  key={endpoint.endpointId}
                  projectId={projectId}
                  endpoint={endpoint}
                  expanded={expandedEndpointIds.has(endpoint.endpointId)}
                  onToggle={toggleExpanded}
                />
              ))}
            </div>
          )}

          {data.endpointsPage.totalElements > 0 && (
            <Pagination
              page={data.endpointsPage.page}
              totalPages={data.endpointsPage.totalPages}
              totalElements={data.endpointsPage.totalElements}
              shownCount={data.endpoints.length}
              size={data.endpointsPage.size}
              isFetching={isFetching}
              itemLabel="endpoint"
              onPageChange={changePage}
            />
          )}
        </div>
      )}
    </div>
  )
}
