import { useMemo, useState } from "react"
import { useNavigate, useParams } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { ArrowLeft, ChevronDown, ChevronRight, Lock } from "lucide-react"

import { Button } from "@/components/ui/button"
import { cn, METHOD_STYLES } from "@/lib/utils"
import { getAdminUserProject, listAdminUserEndpoints, listAdminUserTestCases } from "@/lib/admin"
import { TestCaseDetailDialog } from "@/components/testcases/TestCaseDetailDialog"
import type { TestCase } from "@/lib/testcases"

const SOURCE_STYLES: Record<string, string> = {
  AI_GENERATED: "bg-violet-500/10 text-violet-600 dark:text-violet-400",
  MANUAL: "bg-slate-500/10 text-slate-600 dark:text-slate-400",
  SECURITY: "bg-rose-500/10 text-rose-600 dark:text-rose-400",
}

const SOURCE_LABEL: Record<string, string> = {
  AI_GENERATED: "AI",
  MANUAL: "Tự thêm",
  SECURITY: "Security",
}

/** Chỉ đọc - xem Endpoint/TestCase của project 1 user khác (Module 11). Không có nút sửa/xoá nào. */
export function AdminProjectDataPage() {
  const { userId, projectId } = useParams<{ userId: string; projectId: string }>()
  const navigate = useNavigate()
  const [expandedEndpointIds, setExpandedEndpointIds] = useState<Set<string>>(new Set())
  const [selectedTestCase, setSelectedTestCase] = useState<TestCase | null>(null)

  const { data: project } = useQuery({
    queryKey: ["admin-user-project", userId, projectId],
    queryFn: () => getAdminUserProject(userId!, projectId!),
    enabled: Boolean(userId && projectId),
  })

  const { data: endpointsData, isLoading: endpointsLoading } = useQuery({
    queryKey: ["admin-user-endpoints", userId, projectId],
    queryFn: () => listAdminUserEndpoints(userId!, projectId!),
    enabled: Boolean(userId && projectId),
  })

  const { data: testCases, isLoading: testCasesLoading } = useQuery({
    queryKey: ["admin-user-test-cases", userId, projectId],
    queryFn: () => listAdminUserTestCases(userId!, projectId!),
    enabled: Boolean(userId && projectId),
  })

  const testCasesByEndpointId = useMemo(() => {
    const map = new Map<string, typeof testCases>()
    for (const tc of testCases ?? []) {
      const list = map.get(tc.endpointId) ?? []
      list.push(tc)
      map.set(tc.endpointId, list)
    }
    return map
  }, [testCases])

  function toggleExpanded(endpointId: string) {
    setExpandedEndpointIds((prev) => {
      const next = new Set(prev)
      if (next.has(endpointId)) {
        next.delete(endpointId)
      } else {
        next.add(endpointId)
      }
      return next
    })
  }

  const endpoints = endpointsData?.data ?? []
  const isLoading = endpointsLoading || testCasesLoading

  return (
    <div>
      <Button variant="ghost" size="sm" className="mb-4" onClick={() => navigate(`/admin/users/${userId}`)}>
        <ArrowLeft className="h-4 w-4" />
        Quay lại danh sách project
      </Button>

      <div className="mb-6 flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">{project?.name ?? "Project"}</h1>
          {project?.description && <p className="mt-1 text-muted-foreground">{project.description}</p>}
          <p className="mt-1 text-xs text-muted-foreground">Chế độ chỉ xem - phục vụ hỗ trợ/điều tra.</p>
        </div>
        <Button variant="outline" size="sm" onClick={() => navigate(`/admin/users/${userId}/projects/${projectId}/bug-reports`)}>
          Xem Bug Report
        </Button>
      </div>

      {isLoading && (
        <div className="space-y-2">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="h-14 animate-pulse rounded-lg border border-border bg-card" />
          ))}
        </div>
      )}

      {!isLoading && endpoints.length === 0 && (
        <div className="rounded-lg border border-dashed border-border p-8 text-center text-sm text-muted-foreground">
          Project này chưa có endpoint nào.
        </div>
      )}

      {!isLoading && (
      <div className="space-y-2">
        {endpoints.map((endpoint) => {
          const isExpanded = expandedEndpointIds.has(endpoint.id)
          const cases = testCasesByEndpointId.get(endpoint.id) ?? []
          return (
            <div key={endpoint.id} className="rounded-lg border border-border bg-card">
              <button
                type="button"
                className="flex w-full items-center gap-3 p-4 text-left"
                onClick={() => toggleExpanded(endpoint.id)}
              >
                {isExpanded ? (
                  <ChevronDown className="h-4 w-4 shrink-0 text-muted-foreground" />
                ) : (
                  <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" />
                )}
                <span
                  className={cn(
                    "shrink-0 rounded-md px-2 py-0.5 text-xs font-semibold",
                    METHOD_STYLES[endpoint.method] ?? "bg-muted text-muted-foreground"
                  )}
                >
                  {endpoint.method}
                </span>
                <span className="min-w-0 flex-1 truncate font-mono text-sm">{endpoint.path}</span>
                <span className="shrink-0 text-xs text-muted-foreground">{endpoint.testCaseCount} test case</span>
              </button>

              {isExpanded && (
                <div className="border-t border-border">
                  {cases.length === 0 ? (
                    <p className="p-4 text-sm text-muted-foreground">Chưa có test case nào.</p>
                  ) : (
                    cases.map((tc) => (
                      <button
                        key={tc.id}
                        type="button"
                        onClick={() => setSelectedTestCase(tc)}
                        className="flex w-full items-center gap-3 border-b border-border p-3 text-left text-sm last:border-b-0 hover:bg-accent"
                      >
                        {tc.locked && <Lock className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />}
                        <span className="min-w-0 flex-1 truncate">{tc.name}</span>
                        <span
                          className={cn(
                            "shrink-0 rounded-full px-2 py-0.5 text-xs font-medium",
                            SOURCE_STYLES[tc.source] ?? "bg-muted text-muted-foreground"
                          )}
                        >
                          {SOURCE_LABEL[tc.source] ?? tc.source}
                        </span>
                        <span className="shrink-0 font-mono text-xs text-muted-foreground">
                          {tc.expectedStatus}
                        </span>
                      </button>
                    ))
                  )}
                </div>
              )}
            </div>
          )
        })}
      </div>
      )}

      <TestCaseDetailDialog
        open={selectedTestCase !== null}
        onOpenChange={(open) => !open && setSelectedTestCase(null)}
        testCase={selectedTestCase}
      />
    </div>
  )
}
