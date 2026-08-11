import { useState } from "react"
import { Link, useParams } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { ArrowLeft, ChevronDown, Loader2 } from "lucide-react"

import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"
import { getExecution, type ExecutionStatus, type TestResultStatus } from "@/lib/executions"

function formatBody(body: string | null): string | null {
  if (body === null || body === "") return null
  try {
    return JSON.stringify(JSON.parse(body), null, 2)
  } catch {
    return body
  }
}

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

const RESULT_STATUS_STYLES: Record<TestResultStatus, string> = {
  PASSED: "bg-green-500/10 text-green-600 dark:text-green-400",
  FAILED: "bg-destructive/10 text-destructive",
  ERROR: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  BLOCKED: "bg-muted text-muted-foreground",
  SKIPPED: "bg-muted text-muted-foreground",
}

const RESULT_STATUS_LABEL: Record<TestResultStatus, string> = {
  PASSED: "Pass",
  FAILED: "Fail",
  ERROR: "Lỗi",
  BLOCKED: "Bị chặn",
  SKIPPED: "Bỏ qua",
}

export function TestExecutionPage() {
  const { id, executionId } = useParams<{ id: string; executionId: string }>()
  const projectId = id!
  const [expandedId, setExpandedId] = useState<string | null>(null)

  const { data: execution, isLoading, isError } = useQuery({
    queryKey: ["executions", projectId, executionId],
    queryFn: () => getExecution(projectId, executionId!),
    enabled: Boolean(executionId),
    refetchInterval: (query) => {
      const status = query.state.data?.status
      return status === "PENDING" || status === "RUNNING" ? 1500 : false
    },
  })

  return (
    <div>
      <Button
        variant="ghost"
        size="sm"
        nativeButton={false}
        render={<Link to={`/projects/${projectId}/test-cases`} />}
      >
        <ArrowLeft className="h-4 w-4" />
        Quay lại Test Case
      </Button>

      <div className="mt-4 flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Kết quả thực thi</h1>
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
      </div>

      {isLoading && <p className="mt-6 text-muted-foreground">Đang tải...</p>}
      {isError && <p className="mt-6 text-destructive">Không tải được kết quả thực thi.</p>}

      {execution && execution.autoIncludedTestCaseIds.length > 0 && (
        <p className="mt-4 rounded-md border border-border bg-muted/50 p-3 text-sm text-muted-foreground">
          {execution.autoIncludedTestCaseIds.length} test case phụ trợ đã tự động chạy kèm (dữ liệu
          nguồn cho Test Data Chaining).
        </p>
      )}

      {execution && execution.status !== "PENDING" && execution.status !== "RUNNING" && execution.results.length === 0 && (
        <p className="mt-6 text-muted-foreground">Không có kết quả nào.</p>
      )}

      <ul className="mt-4 flex flex-col gap-2">
        {execution?.results.map((result) => {
          const isExpanded = expandedId === result.testCaseId
          const formattedBody = formatBody(result.responseBody)
          return (
            <li key={result.testCaseId} className="rounded-md border border-border">
              <button
                type="button"
                onClick={() => setExpandedId(isExpanded ? null : result.testCaseId)}
                className="flex w-full flex-col gap-2 p-3 text-left sm:flex-row sm:items-center"
              >
                <span
                  className={cn(
                    "w-16 shrink-0 rounded-md px-2 py-0.5 text-center text-xs font-semibold",
                    RESULT_STATUS_STYLES[result.status]
                  )}
                >
                  {RESULT_STATUS_LABEL[result.status]}
                </span>
                <span className="min-w-0 flex-1 truncate text-sm">{result.testCaseName}</span>
                <span className="shrink-0 text-xs text-muted-foreground">
                  Kỳ vọng {result.expectedStatus} → thực tế {result.responseStatus ?? "—"}
                </span>
                {result.errorMessage && (
                  <span className="shrink-0 truncate text-xs text-destructive sm:max-w-64" title={result.errorMessage}>
                    {result.errorMessage}
                  </span>
                )}
                <ChevronDown
                  className={cn("h-4 w-4 shrink-0 text-muted-foreground transition-transform", isExpanded && "rotate-180")}
                />
              </button>

              {isExpanded && (
                <div className="flex flex-col gap-3 border-t border-border p-3">
                  {result.errorMessage && (
                    <div>
                      <p className="mb-1 text-xs font-medium text-muted-foreground">Lỗi</p>
                      <p className="text-xs whitespace-pre-wrap text-destructive">{result.errorMessage}</p>
                    </div>
                  )}
                  <div>
                    <p className="mb-1 text-xs font-medium text-muted-foreground">Response body</p>
                    {formattedBody ? (
                      <pre className="max-h-80 overflow-auto rounded-md bg-muted p-2 text-xs whitespace-pre-wrap break-all">
                        {formattedBody}
                      </pre>
                    ) : (
                      <p className="text-xs text-muted-foreground">Không có response body.</p>
                    )}
                  </div>
                </div>
              )}
            </li>
          )
        })}
      </ul>
    </div>
  )
}
