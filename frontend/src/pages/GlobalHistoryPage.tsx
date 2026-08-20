import { useState } from "react"
import { Link, useSearchParams } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { Bug, ChevronDown, ChevronLeft, ChevronRight, Play, Sparkles, Trash2 } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { cn, formatDateTime, METHOD_STYLES, selectClassName } from "@/lib/utils"
import { listProjects } from "@/lib/projects"
import { listEndpoints } from "@/lib/endpoints"
import { getHistoryFeed, type HistoryFeedItem, type HistoryStatusFilter } from "@/lib/history"

const STATUS_OPTIONS: { value: HistoryStatusFilter; label: string }[] = [
  { value: "ALL", label: "Tất cả" },
  { value: "HAS_FAIL", label: "Có fail" },
  { value: "ALL_PASS", label: "Toàn bộ pass" },
]

export function GlobalHistoryPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set())

  const projectId = searchParams.get("projectId") ?? ""
  const endpointId = searchParams.get("endpointId") ?? ""
  const from = searchParams.get("from") ?? ""
  const to = searchParams.get("to") ?? ""
  const status = (searchParams.get("status") as HistoryStatusFilter | null) ?? "ALL"
  const page = Number(searchParams.get("page") ?? "0")

  function updateParams(patch: Record<string, string | null>) {
    const next = new URLSearchParams(searchParams)
    for (const [key, value] of Object.entries(patch)) {
      if (value === null || value === "") {
        next.delete(key)
      } else {
        next.set(key, value)
      }
    }
    setSearchParams(next)
  }

  const { data: projectsData } = useQuery({
    queryKey: ["projects-for-filter"],
    queryFn: () => listProjects(100),
  })
  const projects = projectsData?.data ?? []

  const { data: endpointsData } = useQuery({
    queryKey: ["endpoints", projectId],
    queryFn: () => listEndpoints(projectId),
    enabled: Boolean(projectId),
  })
  const endpoints = endpointsData?.data ?? []

  const { data: feed, isLoading, isError } = useQuery({
    queryKey: ["history-feed", projectId, endpointId, from, to, status, page],
    queryFn: () =>
      getHistoryFeed({
        page,
        projectId: projectId || undefined,
        endpointId: endpointId || undefined,
        from: from || undefined,
        to: to || undefined,
        status,
      }),
  })

  function toggleExpanded(itemId: string) {
    setExpandedIds((prev) => {
      const next = new Set(prev)
      if (next.has(itemId)) {
        next.delete(itemId)
      } else {
        next.add(itemId)
      }
      return next
    })
  }

  const items = feed?.data ?? []
  const totalPages = feed?.totalPages ?? 0

  return (
    <div>
      <h1 className="text-2xl font-semibold">Lịch sử</h1>
      <p className="mt-1 text-muted-foreground">Toàn bộ lịch sử sinh test case và chạy test của mọi project.</p>

      <div className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">Project</label>
          <select
            className={cn(selectClassName, "sm:w-full")}
            value={projectId}
            onChange={(e) => updateParams({ projectId: e.target.value || null, endpointId: null, page: null })}
          >
            <option value="">Tất cả project</option>
            {projects.map((project) => (
              <option key={project.id} value={project.id}>
                {project.name}
              </option>
            ))}
          </select>
        </div>

        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">Endpoint</label>
          <select
            className={cn(selectClassName, "sm:w-full")}
            value={endpointId}
            disabled={!projectId}
            onChange={(e) => updateParams({ endpointId: e.target.value || null, page: null })}
          >
            <option value="">Tất cả endpoint</option>
            {endpoints.map((endpoint) => (
              <option key={endpoint.id} value={endpoint.id}>
                {endpoint.method} {endpoint.path}
              </option>
            ))}
          </select>
        </div>

        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">Từ ngày</label>
          <Input
            type="date"
            value={from}
            onChange={(e) => updateParams({ from: e.target.value || null, page: null })}
          />
        </div>

        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">Đến ngày</label>
          <Input
            type="date"
            value={to}
            onChange={(e) => updateParams({ to: e.target.value || null, page: null })}
          />
        </div>
      </div>

      <div className="mt-3">
        <label className="mb-1 block text-xs font-medium text-muted-foreground">Trạng thái</label>
        <select
          className={cn(selectClassName, "sm:w-64")}
          value={status}
          onChange={(e) => updateParams({ status: e.target.value === "ALL" ? null : e.target.value, page: null })}
        >
          {STATUS_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        {status !== "ALL" && (
          <p className="mt-1 text-xs text-muted-foreground">
            Bộ lọc trạng thái chỉ áp dụng cho sự kiện Chạy test — sự kiện Sinh test case và Bug Report bị ẩn khi lọc.
          </p>
        )}
      </div>

      {isLoading && <p className="mt-6 text-muted-foreground">Đang tải...</p>}
      {isError && <p className="mt-6 text-destructive">Không tải được lịch sử, vui lòng thử lại.</p>}
      {!isLoading && !isError && items.length === 0 && (
        <p className="mt-6 text-muted-foreground">Chưa có lịch sử nào khớp bộ lọc.</p>
      )}

      <div className="mt-4 flex flex-col gap-3">
        {items.map((item) => {
          if (item.type === "GENERATION") {
            return (
              <GenerationFeedRow
                key={item.id}
                item={item}
                isExpanded={expandedIds.has(item.id)}
                onToggle={() => toggleExpanded(item.id)}
              />
            )
          }
          if (item.type === "BUG_REPORT_CREATED" || item.type === "BUG_REPORT_DELETED") {
            return <BugReportFeedRow key={item.id} item={item} />
          }
          return <ExecutionFeedRow key={item.id} item={item} />
        })}
      </div>

      {totalPages > 1 && (
        <div className="mt-6 flex items-center justify-center gap-3">
          <Button
            variant="outline"
            size="sm"
            disabled={page <= 0}
            onClick={() => updateParams({ page: String(page - 1) })}
          >
            <ChevronLeft className="h-4 w-4" />
            Trước
          </Button>
          <span className="text-sm text-muted-foreground">
            Trang {page + 1}/{totalPages}
          </span>
          <Button
            variant="outline"
            size="sm"
            disabled={page + 1 >= totalPages}
            onClick={() => updateParams({ page: String(page + 1) })}
          >
            Sau
            <ChevronRight className="h-4 w-4" />
          </Button>
        </div>
      )}
    </div>
  )
}

function FeedRowHeader({ item }: { item: HistoryFeedItem }) {
  return (
    <div className="flex flex-wrap items-center gap-2 text-sm">
      <span className="font-medium">{item.projectName}</span>
      <span className="text-muted-foreground">·</span>
      <span
        className={cn(
          "w-16 shrink-0 rounded-md px-2 py-0.5 text-center text-xs font-semibold",
          METHOD_STYLES[item.endpointMethod] ?? "bg-muted text-muted-foreground"
        )}
      >
        {item.endpointMethod}
      </span>
      <span className="truncate font-mono text-sm">{item.endpointPath}</span>
    </div>
  )
}

function GenerationFeedRow({
  item,
  isExpanded,
  onToggle,
}: {
  item: HistoryFeedItem
  isExpanded: boolean
  onToggle: () => void
}) {
  return (
    <div className="rounded-lg border border-border p-3">
      <FeedRowHeader item={item} />
      <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <Sparkles className="h-4 w-4 shrink-0 text-violet-600 dark:text-violet-400" />
          <span className="text-sm font-medium">Sinh test case · {formatDateTime(item.occurredAt)}</span>
        </div>
        <Button size="sm" variant="ghost" onClick={onToggle}>
          Xem chi tiết
          <ChevronDown className={cn("h-4 w-4 transition-transform", isExpanded && "rotate-180")} />
        </Button>
      </div>
      <p className="mt-1 text-xs text-muted-foreground">Đã sinh {item.generatedCount} test case (AI)</p>

      {isExpanded && (
        <ul className="mt-3 flex flex-col gap-1.5 border-t border-border pt-3">
          {item.snapshotItems?.map((snapshotItem, index) => (
            <li key={index} className="flex items-center gap-3 rounded-md border border-border p-2 text-sm">
              <span className="min-w-0 flex-1 truncate">{snapshotItem.name}</span>
              <span className="shrink-0 text-xs text-muted-foreground">→ {snapshotItem.expectedStatus}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function ExecutionFeedRow({ item }: { item: HistoryFeedItem }) {
  return (
    <div className="rounded-lg border border-border p-3">
      <FeedRowHeader item={item} />
      <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <Play className="h-4 w-4 shrink-0 text-blue-600 dark:text-blue-400" />
          <span className="text-sm font-medium">Chạy test · {formatDateTime(item.occurredAt)}</span>
        </div>
        <Button
          size="sm"
          variant="ghost"
          nativeButton={false}
          render={<Link to={`/projects/${item.projectId}/executions/${item.executionId}?endpointId=${item.endpointId}`} />}
        >
          Xem chi tiết
        </Button>
      </div>
      <p className="mt-1 text-xs text-muted-foreground">
        {item.selectedCount} test case · {item.passCount} pass
        {(item.failCount ?? 0) > 0 && <> · {item.failCount} fail</>}
      </p>
      {(item.otherEndpointCount ?? 0) > 0 && (
        <p className="mt-0.5 text-xs text-muted-foreground italic">
          Chạy chung với {item.otherEndpointCount} endpoint khác
        </p>
      )}
    </div>
  )
}

function BugReportFeedRow({ item }: { item: HistoryFeedItem }) {
  const isDeleted = item.type === "BUG_REPORT_DELETED"
  return (
    <div className="rounded-lg border border-border p-3">
      <FeedRowHeader item={item} />
      <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          {isDeleted ? (
            <Trash2 className="h-4 w-4 shrink-0 text-red-600 dark:text-red-400" />
          ) : (
            <Bug className="h-4 w-4 shrink-0 text-amber-600 dark:text-amber-400" />
          )}
          <span className="text-sm font-medium">
            {isDeleted ? "Xoá Bug Report" : "Tạo Bug Report"} · {formatDateTime(item.occurredAt)}
          </span>
        </div>
        {!isDeleted && item.bugReportId && (
          <Button
            size="sm"
            variant="ghost"
            nativeButton={false}
            render={<Link to={`/projects/${item.projectId}/bug-reports?bugReportId=${item.bugReportId}`} />}
          >
            Xem chi tiết
          </Button>
        )}
      </div>
      <p className="mt-1 text-xs text-muted-foreground">
        {item.bugId} · {item.bugSummary}
      </p>
    </div>
  )
}
