import { useState } from "react"
import { Link } from "react-router-dom"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Loader2, Sparkles } from "lucide-react"

import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { ApiError } from "@/lib/api"
import { generateBaseline, pathParamNames, WRITE_METHODS, type BaselineResult } from "@/lib/baseline"
import type { Endpoint } from "@/lib/endpoints"
import { getProject } from "@/lib/projects"

const REASON_HELP: Record<string, string> = {
  RESOURCE_NOT_FOUND: "Nhập id thật của tài nguyên (hoặc tạo trước 1 test POST tạo tài nguyên để hệ thống tự nối chuỗi) rồi thử lại.",
  MISSING_PARAM_VALUE: "Nhập giá trị thật cho tham số đường dẫn rồi thử lại.",
  AUTH: "Kiểm tra token/vai trò của project (nút Sửa xác thực hoặc Vai trò). AI không sửa được lỗi này.",
  SERVER_ERROR: "API đích lỗi với test hợp lệ: nhiều khả năng là lỗi phía API. Có thể mở test gốc để xem request.",
  REPAIR_EXHAUSTED: "AI đã sửa nhưng chưa Pass: xem phản hồi của API bên dưới và sửa tay test gốc ở trang Test case.",
  TOKEN_BUDGET: "Đã chạm trần token cho 1 lần chạy. Sửa tay test gốc rồi bấm Bổ sung.",
  EXECUTION_TIMEOUT: "Lần chạy thử quá thời gian chờ. Kiểm tra API đích có chạy không rồi thử lại.",
  EXECUTION_ERROR: "Không gọi được API đích: kiểm tra Target URL và mạng rồi thử lại.",
}

interface Props {
  projectId: string
  endpoint: Endpoint
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * Sinh đầy đủ: AI sinh 1 test gốc hợp lệ -> hệ thống CHẠY THẬT để xác nhận (có vòng AI sửa theo lỗi thật) -> bổ sung checklist (0 token).
 * Test gốc được chạy trên API đích nên với method ghi bắt buộc người dùng xác nhận.
 */
export function BaselineGenerateDialog({ projectId, endpoint, open, onOpenChange }: Props) {
  const queryClient = useQueryClient()
  const isWrite = WRITE_METHODS.includes(endpoint.method.toUpperCase())
  const params = pathParamNames(endpoint.path)
  const { data: project } = useQuery({ queryKey: ["project", projectId], queryFn: () => getProject(projectId), enabled: open })

  const [confirmWrite, setConfirmWrite] = useState(false)
  const [rounds, setRounds] = useState(2)
  const [fillCoverage, setFillCoverage] = useState(true)
  const [values, setValues] = useState<Record<string, string>>({})
  const [result, setResult] = useState<BaselineResult | null>(null)

  const mutation = useMutation({
    mutationFn: () =>
      generateBaseline(projectId, endpoint.id, {
        confirmWrite,
        maxRepairRounds: rounds,
        pathParamValues: Object.fromEntries(Object.entries(values).filter(([, v]) => v.trim() !== "")),
        fillCoverage,
      }),
    onSuccess: (data) => {
      setResult(data)
      const keys = [["endpoints", projectId], ["project-coverage", projectId], ["test-cases", projectId], ["generation-plan", projectId, endpoint.id], ["ai-usage"]]
      for (const key of keys) {
        queryClient.invalidateQueries({ queryKey: key })
      }
    },
  })

  const running = mutation.isPending
  const targetUrl = project?.targetBaseUrl ?? null
  const canRun = !running && !!targetUrl && (!isWrite || confirmWrite)

  function handleOpenChange(next: boolean) {
    if (running) return // đang chạy: không đóng được (không huỷ được yêu cầu đang gửi)
    if (!next) {
      setResult(null)
      mutation.reset()
    }
    onOpenChange(next)
  }

  return (
    <AlertDialog open={open} onOpenChange={handleOpenChange}>
      <AlertDialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-lg">
        <AlertDialogHeader>
          <AlertDialogTitle>
            Sinh đầy đủ: {endpoint.method} {endpoint.path}
          </AlertDialogTitle>
          <AlertDialogDescription>
            AI sinh 1 test gốc hợp lệ, hệ thống chạy thử trên API thật để xác nhận (AI tự sửa theo lỗi thật nếu cần), rồi tự bổ sung toàn bộ
            checklist (0 token).
          </AlertDialogDescription>
        </AlertDialogHeader>

        {!result && (
          <div className="flex flex-col gap-3 text-sm">
            <p>
              Đích: <code className="font-mono text-xs">{targetUrl ?? "(chưa cấu hình Target URL)"}</code>
            </p>
            {isWrite && (
              <div role="note" className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-xs">
                <p className="font-medium text-destructive">Hệ thống sẽ GHI THẬT vào API đích ({endpoint.method})</p>
                <p className="mt-1 text-muted-foreground">
                  Test gốc được chạy thật, có thể tạo hoặc sửa dữ liệu (và chạy lại nếu AI phải sửa). Chỉ dùng với môi trường test/staging, không dùng với production.
                </p>
                <label className="mt-2 flex items-start gap-2">
                  <Checkbox checked={confirmWrite} onCheckedChange={(c) => setConfirmWrite(c === true)} disabled={running} />
                  <span>Tôi hiểu và đồng ý ghi dữ liệu thật lên môi trường này</span>
                </label>
              </div>
            )}
            {params.length > 0 && (
              <div className="flex flex-col gap-2">
                <p className="text-xs text-muted-foreground">
                  Giá trị thật cho tham số đường dẫn (nên điền nếu endpoint thao tác trên tài nguyên có sẵn; bỏ trống nếu đã có test POST tạo tài nguyên để hệ thống tự nối chuỗi):
                </p>
                {params.map((name) => (
                  <div key={name} className="flex items-center gap-2">
                    <Label htmlFor={`bl-${name}`} className="w-28 shrink-0 font-mono text-xs">
                      {"{" + name + "}"}
                    </Label>
                    <Input
                      id={`bl-${name}`}
                      value={values[name] ?? ""}
                      onChange={(e) => setValues((prev) => ({ ...prev, [name]: e.target.value }))}
                      disabled={running}
                    />
                  </div>
                ))}
              </div>
            )}
            <div className="flex flex-wrap items-center gap-3">
              <Label htmlFor="bl-rounds" className="text-xs">
                Số lần AI sửa tối đa
              </Label>
              <select
                id="bl-rounds"
                className="h-8 rounded-lg border border-input bg-transparent px-2 text-sm dark:bg-input/30"
                value={rounds}
                onChange={(e) => setRounds(Number(e.target.value))}
                disabled={running}
              >
                {[0, 1, 2, 3].map((n) => (
                  <option key={n} value={n}>
                    {n}
                  </option>
                ))}
              </select>
              <label className="flex items-center gap-2 text-xs">
                <Checkbox checked={fillCoverage} onCheckedChange={(c) => setFillCoverage(c === true)} disabled={running} />
                Tự bổ sung toàn bộ checklist sau khi xác nhận
              </label>
            </div>
            <p className="text-xs text-muted-foreground">
              Ước tính ~10.000 token AI mỗi lần gọi (tối đa 30.000), mất 20–120 giây. Không huỷ được khi đang chạy.
            </p>
            {!targetUrl && <p className="text-xs text-destructive">Project chưa có Target URL: import lại spec và điền URL trước.</p>}
          </div>
        )}

        {running && (
          <p role="status" aria-live="polite" className="flex items-center gap-2 text-sm">
            <Loader2 className="h-4 w-4 animate-spin" />
            Đang sinh và chạy thử trên API thật... (có thể mất vài phút, không thể huỷ)
          </p>
        )}

        {mutation.isError && (
          <p role="alert" className="text-sm text-destructive">
            {mutation.error instanceof ApiError
              ? mutation.error.message
              : "Không nhận được phản hồi. Backend có thể vẫn đang chạy, hãy kiểm tra danh sách test case trước khi thử lại."}
          </p>
        )}

        {result && <ResultPanel result={result} projectId={projectId} endpoint={endpoint} />}

        <AlertDialogFooter>
          {!result && (
            <>
              <AlertDialogCancel disabled={running}>Đóng</AlertDialogCancel>
              <Button onClick={() => mutation.mutate()} disabled={!canRun} aria-busy={running}>
                {running ? <Loader2 className="h-4 w-4 animate-spin" /> : <Sparkles className="h-4 w-4" />}
                {running ? "Đang chạy..." : "Sinh đầy đủ"}
              </Button>
            </>
          )}
          {result && (
            <>
              {result.status === "NEEDS_HUMAN" && (
                <Button variant="outline" onClick={() => setResult(null)}>
                  Sửa thông tin và thử lại
                </Button>
              )}
              <AlertDialogCancel>Đóng</AlertDialogCancel>
            </>
          )}
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

function ResultPanel({ result, projectId, endpoint }: { result: BaselineResult; projectId: string; endpoint: Endpoint }) {
  const ok = result.status !== "NEEDS_HUMAN"
  return (
    <div role="status" aria-live="polite" className="flex flex-col gap-2 text-sm">
      <p className={ok ? "font-medium text-green-600 dark:text-green-400" : "font-medium text-amber-700 dark:text-amber-400"}>
        {result.status === "CONFIRMED" && `Đã xác nhận test gốc, bổ sung ${result.filledCount} test`}
        {result.status === "ALREADY_HAS_BASELINE" && `Đã có test gốc đã Pass (không tốn token), bổ sung ${result.filledCount} test`}
        {result.status === "NEEDS_HUMAN" && "Cần bạn xử lý"}
      </p>
      {result.message && <p className="text-xs">{result.message}</p>}
      {result.reason && REASON_HELP[result.reason] && <p className="text-xs text-muted-foreground">{REASON_HELP[result.reason]}</p>}
      {result.rounds.length > 0 && (
        <ul className="list-disc pl-5 text-xs text-muted-foreground">
          {result.rounds.map((r) => (
            <li key={r.round}>
              Lần {r.round}: {r.httpStatus ?? "không có phản hồi"} {r.passed ? "- Pass" : "- chưa Pass"} ({r.tokens} token)
            </li>
          ))}
        </ul>
      )}
      <p className="text-xs text-muted-foreground">Tổng token AI: {result.tokensUsed}</p>
      {result.lastResponseBodyMasked && result.status === "NEEDS_HUMAN" && (
        <div>
          <p className="text-xs font-medium">Phản hồi cuối của API (đã che dữ liệu nhạy cảm):</p>
          <pre className="mt-1 max-h-40 overflow-auto rounded-md bg-muted p-2 text-xs whitespace-pre-wrap break-all">
            {result.lastResponseBodyMasked}
          </pre>
        </div>
      )}
      {result.baselineTestCaseId && (
        <Link to={`/projects/${projectId}/test-cases?endpointId=${endpoint.id}`} className="text-xs text-primary underline underline-offset-4">
          Xem test case của endpoint
        </Link>
      )}
    </div>
  )
}
