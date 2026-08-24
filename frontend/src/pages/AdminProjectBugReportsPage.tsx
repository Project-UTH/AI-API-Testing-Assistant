import { useState } from "react"
import { useNavigate, useParams } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { ArrowLeft, ChevronDown, ChevronRight } from "lucide-react"

import { Button } from "@/components/ui/button"
import { cn, formatDateTime, METHOD_STYLES } from "@/lib/utils"
import { getAdminUserBugReports, getAdminUserRunHistory } from "@/lib/admin"
import { StatusBadge } from "@/components/shared/StatusBadge"
import { BugStatusBadge } from "@/components/shared/BugStatusBadge"

/** Chỉ đọc - xem Bug Report của project 1 user khác (Module 11c). Không có nút tạo/sửa/xoá bug. */
export function AdminProjectBugReportsPage() {
  const { userId, projectId } = useParams<{ userId: string; projectId: string }>()
  const navigate = useNavigate()
  const [expandedEndpointIds, setExpandedEndpointIds] = useState<Set<string>>(new Set())
  const [expandedTestCaseIds, setExpandedTestCaseIds] = useState<Set<string>>(new Set())

  const { data, isLoading, isError } = useQuery({
    queryKey: ["admin-user-bug-reports", userId, projectId],
    queryFn: () => getAdminUserBugReports(userId!, projectId!),
    enabled: Boolean(userId && projectId),
  })

  function toggleEndpoint(id: string) {
    setExpandedEndpointIds((prev) => {
      const next = new Set(prev)
      next.has(id) ? next.delete(id) : next.add(id)
      return next
    })
  }

  function toggleTestCase(id: string) {
    setExpandedTestCaseIds((prev) => {
      const next = new Set(prev)
      next.has(id) ? next.delete(id) : next.add(id)
      return next
    })
  }

  const endpoints = data?.endpoints ?? []

  return (
    <div>
      <Button variant="ghost" size="sm" className="mb-4" onClick={() => navigate(`/admin/users/${userId}/projects/${projectId}`)}>
        <ArrowLeft className="h-4 w-4" />
        Quay lại Endpoint/Test Case
      </Button>

      <div className="mb-6">
        <h1 className="text-2xl font-semibold">Bug Report</h1>
        <p className="mt-1 text-xs text-muted-foreground">Chế độ chỉ xem - phục vụ hỗ trợ/điều tra.</p>
      </div>

      {isLoading && (
        <div className="space-y-2">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="h-14 animate-pulse rounded-lg border border-border bg-card" />
          ))}
        </div>
      )}

      {isError && (
        <div className="rounded-lg border border-destructive/40 bg-destructive/5 p-4 text-sm text-destructive">
          Không tải được Bug Report.
        </div>
      )}

      {!isLoading && !isError && data && (
        <>
          <div className="mb-6 grid grid-cols-3 gap-4">
            <div className="rounded-2xl border border-border bg-card p-4">
              <p className="text-sm text-muted-foreground">Đang mở</p>
              <p className="mt-1 text-2xl font-bold text-amber-500">{data.summary.openCount}</p>
            </div>
            <div className="rounded-2xl border border-border bg-card p-4">
              <p className="text-sm text-muted-foreground">Đã đóng</p>
              <p className="mt-1 text-2xl font-bold">{data.summary.closedCount}</p>
            </div>
            <div className="rounded-2xl border border-border bg-card p-4">
              <p className="text-sm text-muted-foreground">Tổng số</p>
              <p className="mt-1 text-2xl font-bold">{data.summary.totalCount}</p>
            </div>
          </div>

          {endpoints.length === 0 && (
            <div className="rounded-lg border border-dashed border-border p-8 text-center text-sm text-muted-foreground">
              Chưa có test case nào đã chạy để hiện ở đây.
            </div>
          )}

          <div className="space-y-2">
            {endpoints.map((endpoint) => {
              const isExpanded = expandedEndpointIds.has(endpoint.endpointId)
              return (
                <div key={endpoint.endpointId} className="rounded-lg border border-border bg-card">
                  <button
                    type="button"
                    className="flex w-full items-center gap-3 p-4 text-left"
                    onClick={() => toggleEndpoint(endpoint.endpointId)}
                  >
                    {isExpanded ? (
                      <ChevronDown className="h-4 w-4 shrink-0 text-muted-foreground" />
                    ) : (
                      <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" />
                    )}
                    <span
                      className={cn(
                        "shrink-0 rounded-md px-2 py-0.5 text-xs font-semibold",
                        METHOD_STYLES[endpoint.endpointMethod] ?? "bg-muted text-muted-foreground"
                      )}
                    >
                      {endpoint.endpointMethod}
                    </span>
                    <span className="min-w-0 flex-1 truncate font-mono text-sm">{endpoint.endpointPath}</span>
                  </button>

                  {isExpanded && (
                    <div className="space-y-2 border-t border-border p-3">
                      {endpoint.testCases.map((testCase) => (
                        <div key={testCase.testCaseId} className="rounded-md border border-border p-3">
                          <button
                            type="button"
                            className="flex w-full items-center gap-2 text-left"
                            onClick={() => toggleTestCase(testCase.testCaseId)}
                          >
                            {expandedTestCaseIds.has(testCase.testCaseId) ? (
                              <ChevronDown className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
                            ) : (
                              <ChevronRight className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
                            )}
                            <span className="min-w-0 flex-1 truncate text-base font-semibold">{testCase.testCaseName}</span>
                          </button>

                          {testCase.bugs.length > 0 && (
                            <div className="mt-2 space-y-2 pl-5">
                              {testCase.bugs.map((bug) => (
                                <div key={bug.id} className="border-l-4 border-l-amber-500 bg-card p-2 pl-3">
                                  <div className="flex items-center gap-2">
                                    <span className="font-mono text-xs font-medium">{bug.bugId}</span>
                                    <BugStatusBadge status={bug.status} />
                                  </div>
                                  <p className="mt-1 text-sm text-muted-foreground">{bug.summary}</p>
                                </div>
                              ))}
                            </div>
                          )}

                          {expandedTestCaseIds.has(testCase.testCaseId) && (
                            <RunHistoryList userId={userId!} projectId={projectId!} testCaseId={testCase.testCaseId} />
                          )}
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )
            })}
          </div>
        </>
      )}
    </div>
  )
}

function RunHistoryList({ userId, projectId, testCaseId }: { userId: string; projectId: string; testCaseId: string }) {
  const { data, isLoading } = useQuery({
    queryKey: ["admin-user-run-history", userId, projectId, testCaseId],
    queryFn: () => getAdminUserRunHistory(userId, projectId, testCaseId),
  })

  if (isLoading) {
    return <p className="mt-2 pl-5 text-xs text-muted-foreground">Đang tải lịch sử chạy...</p>
  }

  if (!data || data.length === 0) {
    return <p className="mt-2 pl-5 text-xs text-muted-foreground">Chưa có lần chạy nào.</p>
  }

  return (
    <div className="mt-2 space-y-1 pl-5">
      {data.map((run) => (
        <div key={run.testResultId} className="flex items-center gap-2 rounded-md border border-border bg-card p-2 text-xs">
          <StatusBadge status={run.status} compact />
          <span className="text-muted-foreground">{formatDateTime(run.occurredAt)}</span>
          <span className="ml-auto font-mono text-muted-foreground">
            Kỳ vọng {run.expectedStatus} → thực tế {run.responseStatus ?? "—"}
          </span>
        </div>
      ))}
    </div>
  )
}
