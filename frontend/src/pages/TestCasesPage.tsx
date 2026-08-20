import { useMemo, useState } from "react"
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { ArrowLeft, History, Loader2, Lock, Pencil, Play, Plus, Trash2, Unlock } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { cn, METHOD_STYLES, selectClassName } from "@/lib/utils"
import { ApiError } from "@/lib/api"
import { listEndpoints } from "@/lib/endpoints"
import { listTestCases, setTestCaseLocked, type TestCase } from "@/lib/testcases"
import { triggerExecution } from "@/lib/executions"
import { TestCaseFormDialog } from "@/components/testcases/TestCaseFormDialog"
import { DeleteTestCaseDialog } from "@/components/testcases/DeleteTestCaseDialog"

const SOURCE_STYLES: Record<TestCase["source"], string> = {
  AI_GENERATED: "bg-violet-500/10 text-violet-600 dark:text-violet-400",
  MANUAL: "bg-slate-500/10 text-slate-600 dark:text-slate-400",
  SECURITY: "bg-rose-500/10 text-rose-600 dark:text-rose-400",
}

const SOURCE_LABEL: Record<TestCase["source"], string> = {
  AI_GENERATED: "AI",
  MANUAL: "Tự thêm",
  SECURITY: "Security",
}

interface FormState {
  open: boolean
  endpointId: string
  endpointPath: string
  testCase: TestCase | null
}

export function TestCasesPage() {
  const { id } = useParams<{ id: string }>()
  const projectId = id!
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()
  const endpointFilter = searchParams.get("endpointId") ?? ""
  const [lockPendingIds, setLockPendingIds] = useState<Set<string>>(new Set())

  const { data: endpointsData } = useQuery({
    queryKey: ["endpoints", projectId],
    queryFn: () => listEndpoints(projectId),
  })
  const { data: testCases, isLoading, isError } = useQuery({
    queryKey: ["test-cases", projectId],
    queryFn: () => listTestCases(projectId),
  })

  const endpoints = endpointsData?.data ?? []
  const allTestCases = testCases ?? []

  const [formState, setFormState] = useState<FormState>({
    open: false,
    endpointId: "",
    endpointPath: "",
    testCase: null,
  })
  const [deleteTarget, setDeleteTarget] = useState<TestCase | null>(null)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  const [isRunning, setIsRunning] = useState(false)
  const [runError, setRunError] = useState<string | null>(null)

  const testCasesByEndpoint = useMemo(() => {
    const map = new Map<string, TestCase[]>()
    for (const testCase of allTestCases) {
      const list = map.get(testCase.endpointId) ?? []
      list.push(testCase)
      map.set(testCase.endpointId, list)
    }
    return map
  }, [allTestCases])

  const visibleEndpoints = endpointFilter
    ? endpoints.filter((endpoint) => endpoint.id === endpointFilter)
    : endpoints

  function openCreate(endpointId: string, endpointPath: string) {
    setFormState({ open: true, endpointId, endpointPath, testCase: null })
  }

  function openEdit(testCase: TestCase) {
    setFormState({
      open: true,
      endpointId: testCase.endpointId,
      endpointPath: testCase.endpointPath,
      testCase,
    })
  }

  function openDelete(testCase: TestCase) {
    setDeleteTarget(testCase)
    setDeleteOpen(true)
  }

  async function handleToggleLock(testCase: TestCase) {
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
  }

  function toggleSelect(testCaseId: string, checked: boolean) {
    setSelectedIds((prev) => {
      const next = new Set(prev)
      if (checked) {
        next.add(testCaseId)
      } else {
        next.delete(testCaseId)
      }
      return next
    })
  }

  async function handleRun() {
    setRunError(null)
    setIsRunning(true)
    try {
      const execution = await triggerExecution(projectId, Array.from(selectedIds))
      navigate(`/projects/${projectId}/executions/${execution.id}`)
    } catch (error) {
      setRunError(error instanceof ApiError ? error.message : "Đã xảy ra lỗi, vui lòng thử lại")
    } finally {
      setIsRunning(false)
    }
  }

  return (
    <div>
      <Button variant="ghost" size="sm" nativeButton={false} render={<Link to={`/projects/${projectId}`} />}>
        <ArrowLeft className="h-4 w-4" />
        Quay lại Project
      </Button>

      <div className="mt-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <h1 className="text-2xl font-semibold">Test Case</h1>
        <div className="flex flex-wrap items-center gap-2">
          <select
            className={selectClassName}
            value={endpointFilter}
            onChange={(e) => {
              const value = e.target.value
              setSearchParams(value ? { endpointId: value } : {})
            }}
          >
            <option value="">Tất cả endpoint</option>
            {endpoints.map((endpoint) => (
              <option key={endpoint.id} value={endpoint.id}>
                {endpoint.method} {endpoint.path}
              </option>
            ))}
          </select>
          <Button
            size="sm"
            variant="outline"
            nativeButton={false}
            render={
              <Link
                to={`/projects/${projectId}/history?onlyExecution=1${endpointFilter ? `&endpointId=${endpointFilter}` : ""}`}
              />
            }
          >
            <History className="h-4 w-4" />
            Lịch sử chạy test
          </Button>
          <Button size="sm" disabled={selectedIds.size === 0 || isRunning} onClick={handleRun}>
            {isRunning ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}
            Chạy Test {selectedIds.size > 0 ? `(${selectedIds.size})` : ""}
          </Button>
        </div>
      </div>

      {runError && <p className="mt-2 text-sm text-destructive">{runError}</p>}

      {isLoading && <p className="mt-6 text-muted-foreground">Đang tải...</p>}

      {isError && (
        <p className="mt-6 text-destructive">Không tải được danh sách test case, vui lòng thử lại.</p>
      )}

      {!isLoading && !isError && visibleEndpoints.length === 0 && (
        <p className="mt-6 text-muted-foreground">Chưa có endpoint nào.</p>
      )}

      <div className="mt-4 flex flex-col gap-4">
        {visibleEndpoints.map((endpoint) => {
          const cases = testCasesByEndpoint.get(endpoint.id) ?? []
          return (
            <div key={endpoint.id} className="rounded-lg border border-border p-4">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex min-w-0 items-center gap-3">
                  <span
                    className={cn(
                      "w-16 shrink-0 rounded-md px-2 py-0.5 text-center text-xs font-semibold",
                      METHOD_STYLES[endpoint.method] ?? "bg-muted text-muted-foreground"
                    )}
                  >
                    {endpoint.method}
                  </span>
                  <span className="truncate font-mono text-sm">{endpoint.path}</span>
                </div>
                <Button size="sm" variant="outline" onClick={() => openCreate(endpoint.id, endpoint.path)}>
                  <Plus className="h-4 w-4" />
                  Thêm test case
                </Button>
              </div>

              {cases.length === 0 ? (
                <p className="mt-3 text-sm text-muted-foreground">Chưa có test case nào.</p>
              ) : (
                <ul className="mt-3 flex flex-col gap-2">
                  {cases.map((testCase) => (
                    <li
                      key={testCase.id}
                      className="flex items-center gap-3 rounded-md border border-border p-2"
                    >
                      <Checkbox
                        checked={selectedIds.has(testCase.id)}
                        onCheckedChange={(checked) => toggleSelect(testCase.id, checked === true)}
                      />
                      <span
                        className={cn(
                          "shrink-0 rounded-md px-2 py-0.5 text-xs font-medium",
                          SOURCE_STYLES[testCase.source]
                        )}
                      >
                        {SOURCE_LABEL[testCase.source]}
                      </span>
                      <span className="min-w-0 flex-1 truncate text-sm">{testCase.name}</span>
                      <span className="shrink-0 text-xs text-muted-foreground">
                        → {testCase.expectedStatus}
                      </span>
                      <Button
                        size="icon-sm"
                        variant="ghost"
                        disabled={lockPendingIds.has(testCase.id)}
                        title={
                          testCase.locked
                            ? "Đang khoá - sẽ không bị xoá khi Sinh Test Case lại. Bấm để mở khoá."
                            : "Bấm để khoá - giữ nguyên test case này khi Sinh Test Case lại"
                        }
                        onClick={() => handleToggleLock(testCase)}
                      >
                        {testCase.locked ? (
                          <Lock className="h-3.5 w-3.5 text-amber-600 dark:text-amber-400" />
                        ) : (
                          <Unlock className="h-3.5 w-3.5 text-muted-foreground" />
                        )}
                      </Button>
                      <Button size="icon-sm" variant="ghost" onClick={() => openEdit(testCase)}>
                        <Pencil className="h-3.5 w-3.5" />
                      </Button>
                      <Button size="icon-sm" variant="ghost" onClick={() => openDelete(testCase)}>
                        <Trash2 className="h-3.5 w-3.5" />
                      </Button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )
        })}
      </div>

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
      />
    </div>
  )
}
