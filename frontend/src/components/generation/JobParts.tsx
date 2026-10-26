import { useEffect, useRef, useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { Bot, CheckCircle2, Loader2, MinusCircle, Server, X, XCircle } from "lucide-react"

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Button } from "@/components/ui/button"
import { getJob, isTerminal, jobQueryKeys, type GenerationJob, type GenerationStep } from "@/lib/generationJobs"
import { cn } from "@/lib/utils"

/** Các phần dùng chung của trang chính lần sinh test: polling job, dòng bước, nút huỷ. (Khung giám sát nổi nhỏ ở góc màn hình đã bỏ.) */

export function formatElapsed(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000))
  return s >= 60 ? `${Math.floor(s / 60)}p ${String(s % 60).padStart(2, "0")}s` : `${s}s`
}

/** Polling 1 job (gộp các bước mới theo seq); dùng chung cho khung giám sát nổi và trang chính của lần sinh test. */
export function useJobPolling(projectId: string, jobId: string) {
  const queryClient = useQueryClient()
  const result = useQuery({
    queryKey: ["gen-job", jobId],
    queryFn: async (): Promise<GenerationJob> => {
      const previous = queryClient.getQueryData<GenerationJob>(["gen-job", jobId])
      const next = await getJob(projectId, jobId, previous?.lastSeq ?? 0)
      const bySeq = new Map<number, GenerationStep>()
      previous?.steps.forEach((s) => bySeq.set(s.seq, s))
      next.steps.forEach((s) => bySeq.set(s.seq, s))
      return { ...next, steps: Array.from(bySeq.values()).sort((a, b) => a.seq - b.seq), lastSeq: Math.max(previous?.lastSeq ?? 0, next.lastSeq) }
    },
    refetchInterval: (query) => {
      const data = query.state.data
      if (data && isTerminal(data.status)) return false
      return document.hidden ? 5000 : 1500
    },
    refetchIntervalInBackground: true,
    retry: 3,
    staleTime: 0,
  })
  const job = result.data
  // Khi job kết thúc: làm mới dữ liệu bị ảnh hưởng (test case, coverage, quota AI...).
  const wasActive = useRef(false)
  useEffect(() => {
    if (!job) return
    if (!isTerminal(job.status)) {
      wasActive.current = true
      return
    }
    if (wasActive.current) {
      wasActive.current = false
      for (const key of jobQueryKeys(projectId)) queryClient.invalidateQueries({ queryKey: key })
    }
  }, [job, projectId, queryClient])
  return result
}

export function StepRow({ step, now }: { step: GenerationStep; now: number }) {
  const stepMs = step.status === "RUNNING" && step.startedAt ? now - new Date(step.startedAt).getTime() : 0
  const icon =
    step.status === "RUNNING" ? (
      <Loader2 className="size-3.5 animate-spin text-primary motion-reduce:animate-none" role="img" aria-label="Đang chạy" />
    ) : step.status === "OK" ? (
      <CheckCircle2 className="size-3.5 text-green-600 dark:text-green-400" role="img" aria-label="Xong" />
    ) : step.status === "FAILED" ? (
      <XCircle className="size-3.5 text-destructive" role="img" aria-label="Lỗi/dừng" />
    ) : (
      <MinusCircle className="size-3.5 text-muted-foreground" role="img" aria-label="Bỏ qua" />
    )
  const httpOk = step.httpStatus !== null && step.httpStatus >= 200 && step.httpStatus < 300
  return (
    <li className={cn("rounded-md px-2 py-1 text-xs", step.status === "RUNNING" && "bg-primary/5 ring-1 ring-primary/30")}>
      <div className="flex flex-wrap items-center gap-1.5">
        {icon}
        <span
          className={cn(
            "inline-flex items-center gap-1 rounded-full px-1.5 py-0.5 text-[10px] font-medium",
            step.actor === "AI" ? "bg-violet-500/10 text-violet-700 dark:text-violet-300" : "bg-muted text-muted-foreground"
          )}
        >
          {step.actor === "AI" ? <Bot className="size-3" aria-hidden /> : <Server className="size-3" aria-hidden />}
          {step.actor === "AI" ? "AI" : "Hệ thống"}
        </span>
        <span className="font-medium">{step.title}</span>
        {step.httpStatus !== null && (
          <span className={cn("rounded px-1 font-mono", httpOk ? "bg-green-500/10 text-green-700 dark:text-green-300" : "bg-destructive/10 text-destructive")}>
            HTTP {step.httpStatus}
          </span>
        )}
        {step.status === "RUNNING" && stepMs > 0 && <span className="text-muted-foreground">{formatElapsed(stepMs)}</span>}
        {step.tokens !== null && step.tokens > 0 && <span className="text-muted-foreground">{step.tokens.toLocaleString("vi-VN")} token</span>}
      </div>
      {step.status === "RUNNING" && stepMs > 180000 && (
        <p className="mt-0.5 pl-5 text-amber-700 dark:text-amber-400">
          Bước này đã chạy hơn {Math.floor(stepMs / 60000)} phút (thường do AI xử lý yêu cầu lớn). Vẫn đang chờ phản hồi; bấm Huỷ nếu không muốn đợi.
        </p>
      )}
      {step.detail && <p className="mt-0.5 pl-5 break-words text-muted-foreground">{step.detail}</p>}
    </li>
  )
}

export function CancelButton({ onConfirm, pending, compact = false }: { onConfirm: () => void; pending: boolean; compact?: boolean }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button
        size={compact ? "icon-sm" : "sm"}
        variant={compact ? "ghost" : "outline"}
        disabled={pending}
        aria-label="Huỷ job sinh test"
        onClick={() => setOpen(true)}
      >
        {compact ? <X className="size-4" /> : pending ? "Đang huỷ..." : "Huỷ"}
      </Button>
      <AlertDialog open={open} onOpenChange={setOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Huỷ job sinh test?</AlertDialogTitle>
            <AlertDialogDescription>
              Job sẽ dừng ở bước an toàn tiếp theo. Các bước đã chạy được giữ lại, token AI đã dùng vẫn tính. Lần gọi AI hoặc chạy test đang dở vẫn kết thúc trước.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Tiếp tục chạy</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={() => {
                setOpen(false)
                onConfirm()
              }}
            >
              Huỷ job
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
