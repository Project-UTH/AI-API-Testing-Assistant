import { useState } from "react"
import { Link, useParams } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { ArrowLeft, ChevronDown, Play, Sparkles } from "lucide-react"

import { Button } from "@/components/ui/button"
import { cn, formatDateTime, METHOD_STYLES } from "@/lib/utils"
import { getProjectHistory, type HistoryEvent } from "@/lib/history"

export function TestHistoryPage() {
  const { id } = useParams<{ id: string }>()
  const projectId = id!
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set())

  const { data: history, isLoading, isError } = useQuery({
    queryKey: ["history", projectId],
    queryFn: () => getProjectHistory(projectId),
  })

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

  return (
    <div>
      <Button variant="ghost" size="sm" nativeButton={false} render={<Link to={`/projects/${projectId}`} />}>
        <ArrowLeft className="h-4 w-4" />
        Quay lại Project
      </Button>

      <h1 className="mt-4 text-2xl font-semibold">Lịch sử kiểm thử</h1>

      {isLoading && <p className="mt-6 text-muted-foreground">Đang tải...</p>}
      {isError && <p className="mt-6 text-destructive">Không tải được lịch sử kiểm thử, vui lòng thử lại.</p>}
      {!isLoading && !isError && history?.length === 0 && (
        <p className="mt-6 text-muted-foreground">Chưa có lịch sử kiểm thử nào.</p>
      )}

      <div className="mt-4 flex flex-col gap-4">
        {history?.map((endpointHistory) => (
          <div key={endpointHistory.endpointId} className="rounded-lg border border-border p-4">
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
              {endpointHistory.events.map((event) => (
                <li key={event.id} className="relative">
                  <span className="absolute top-1.5 -left-[25px] h-2.5 w-2.5 rounded-full border-2 border-background bg-primary" />
                  {event.type === "GENERATION" ? (
                    <GenerationEventRow
                      event={event}
                      isExpanded={expandedIds.has(event.id)}
                      onToggle={() => toggleExpanded(event.id)}
                    />
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
          </div>
        ))}
      </div>
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
      <p className="mt-1 text-xs text-muted-foreground">
        {event.selectedCount}/{currentTestCaseCount} test case · {event.passCount} pass
        {(event.failCount ?? 0) > 0 && <> · {event.failCount} fail</>}
      </p>
      {(event.otherEndpointCount ?? 0) > 0 && (
        <p className="mt-0.5 text-xs text-muted-foreground italic">
          Chạy chung với {event.otherEndpointCount} endpoint khác
        </p>
      )}
    </div>
  )
}
