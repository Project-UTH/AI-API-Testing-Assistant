import { useEffect, useRef, useState } from "react"
import { Link, useNavigate } from "react-router-dom"
import { useMutation } from "@tanstack/react-query"
import { CheckCircle2, Loader2, Play } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { ApiError } from "@/lib/api"
import { triggerExecution } from "@/lib/executions"

interface Props {
  projectId: string
  endpointId: string
  /** Id các test vừa sinh (hệ thống + AI). */
  testCaseIds: string[]
  systemCount: number
  aiCount: number
  /** Mục đã duyệt nhưng chưa sinh được test, kèm lý do. */
  notGenerated: { ruleId: string; sentence: string; reason: string }[]
}

/**
 * Bước 4 hoàn tất: hiện NGAY ĐẦU trang (cuộn tới) để biết đã xong và việc tiếp theo là gì - chạy các test vừa sinh trên API đích, hoặc xem chúng ở tab Test case.
 * Chạy là chạy THẬT (có thể ghi dữ liệu), nên có tuỳ chọn dọn dữ liệu đã tạo.
 */
export function GeneratedResultCard({ projectId, endpointId, testCaseIds, systemCount, aiCount, notGenerated }: Props) {
  const navigate = useNavigate()
  const ref = useRef<HTMLElement | null>(null)
  const [cleanup, setCleanup] = useState(true)
  useEffect(() => {
    ref.current?.scrollIntoView({ block: "start", behavior: "smooth" })
  }, [])
  const run = useMutation({
    mutationFn: () => triggerExecution(projectId, testCaseIds, cleanup),
    onSuccess: (execution) => navigate(`/projects/${projectId}/executions/${execution.id}`),
  })
  const total = systemCount + aiCount
  return (
    <section ref={ref} role="status" aria-label="Kết quả sinh test" className="mt-4 rounded-md border border-green-500/40 bg-green-500/5 p-4 text-sm">
      <h3 className="flex items-center gap-2 font-semibold">
        <CheckCircle2 className="size-4 text-green-600 dark:text-green-400" aria-hidden />
        Bước 4 hoàn tất: đã sinh {total} test
      </h3>
      <p className="mt-1 text-xs text-muted-foreground">
        {systemCount} từ checklist hệ thống, {aiCount} từ checklist nghiệp vụ AI. Việc tiếp theo: chạy các test này trên API đích để xem Pass/Fail và nguyên nhân.
      </p>
      {notGenerated.length > 0 && (
        <details className="mt-2 text-xs">
          <summary className="cursor-pointer text-amber-700 dark:text-amber-400">{notGenerated.length} mục đã duyệt chưa sinh được test (xem lý do)</summary>
          <ul className="mt-1 list-disc pl-5 text-muted-foreground">
            {notGenerated.map((n) => (
              <li key={n.ruleId}>
                <span className="text-foreground">{n.sentence}</span>: {n.reason}
              </li>
            ))}
          </ul>
        </details>
      )}
      <div className="mt-3 flex flex-wrap items-center gap-3">
        <Button type="button" size="sm" disabled={testCaseIds.length === 0 || run.isPending} onClick={() => run.mutate()}>
          {run.isPending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Play className="h-3.5 w-3.5" />}
          Chạy {testCaseIds.length} test vừa sinh
        </Button>
        <label className="flex items-center gap-2 text-xs">
          <Checkbox checked={cleanup} onCheckedChange={(c) => setCleanup(c === true)} />
          Dọn dữ liệu đã tạo trên API đích sau khi chạy
        </label>
        <Link to={`/projects/${projectId}/test-cases?endpointId=${endpointId}`} className="text-xs text-primary underline underline-offset-4">
          Xem các test ở tab Test case
        </Link>
      </div>
      {run.isError && (
        <p role="alert" className="mt-2 text-xs text-destructive">
          {run.error instanceof ApiError ? run.error.message : "Không bắt đầu được lần chạy, vui lòng thử lại."}
        </p>
      )}
    </section>
  )
}
