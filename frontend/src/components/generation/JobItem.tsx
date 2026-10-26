import { useEffect, useMemo, useRef, useState } from "react"
import { Link } from "react-router-dom"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Bot, CheckCircle2, ChevronsUpDown, Loader2, Maximize2, MinusCircle, Minus, Server, X, XCircle } from "lucide-react"

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
import { ApiError } from "@/lib/api"
import {
  cancelJob,
  getJob,
  isTerminal,
  JOB_STATUS_LABEL,
  JOB_TYPE_LABEL,
  jobQueryKeys,
  REASON_HELP,
  type GenerationJob,
  type GenerationStep,
} from "@/lib/generationJobs"
import { cn } from "@/lib/utils"

interface Props {
  projectId: string
  jobId: string
  expanded: boolean
  onExpand: () => void
  onMinimize: () => void
  onDismiss: () => void
}

const STATUS_TONE: Record<string, string> = {
  QUEUED: "border-border",
  RUNNING: "border-primary/50",
  CANCELLING: "border-amber-500/50",
  SUCCEEDED: "border-green-500/50",
  NEEDS_HUMAN: "border-amber-500/60",
  FAILED: "border-destructive/60",
  CANCELLED: "border-border",
  INTERRUPTED: "border-destructive/40",
}

function formatElapsed(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000))
  return s >= 60 ? `${Math.floor(s / 60)}p ${String(s % 60).padStart(2, "0")}s` : `${s}s`
}

/** 1 job: tự polling (gộp các bước mới theo seq), hiện thành pill thu nhỏ hoặc panel giám sát đầy đủ. */
export function JobItem({ projectId, jobId, expanded, onExpand, onMinimize, onDismiss }: Props) {
  const queryClient = useQueryClient()
  const [now, setNow] = useState(() => Date.now())
  const panelRef = useRef<HTMLElement | null>(null)
  useEffect(() => {
    if (!expanded) return
    // chờ hộp thoại vừa đóng trả focus xong rồi mới chuyển focus vào panel
    const t = setTimeout(() => panelRef.current?.focus({ preventScroll: true }), 150)
    return () => clearTimeout(t)
  }, [expanded])

  const { data: job, error, isError: pollFailed, dataUpdatedAt } = useQuery({
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

  // Job không còn tồn tại (xoá project, đổi tài khoản...) -> tự gỡ khỏi khung.
  const notFound = error instanceof ApiError && error.code === "NOT_FOUND"
  useEffect(() => {
    if (notFound) onDismiss()
  }, [notFound, onDismiss])

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

  const active = !!job && !isTerminal(job.status)
  useEffect(() => {
    if (!active) return
    const t = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(t)
  }, [active])

  const cancelMutation = useMutation({
    mutationFn: () => cancelJob(projectId, jobId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["gen-job", jobId] }),
  })

  const groups = useMemo(() => {
    const map = new Map<string, GenerationStep[]>()
    job?.steps.forEach((s) => {
      const key = s.endpointLabel ?? "Chung"
      map.set(key, [...(map.get(key) ?? []), s])
    })
    return Array.from(map.entries())
  }, [job?.steps])

  if (!job) {
    return error && !notFound ? (
      <div className="pointer-events-auto rounded-lg border border-destructive/40 bg-card px-3 py-2 text-xs shadow-lg" role="alert">
        Mất kết nối khi theo dõi job, đang thử lại...
        <Button size="sm" variant="ghost" onClick={onDismiss}>
          Đóng
        </Button>
      </div>
    ) : null
  }

  const started = job.startedAt ?? job.createdAt
  const elapsed = (job.finishedAt ? new Date(job.finishedAt).getTime() : now) - new Date(started).getTime()
  const label = JOB_TYPE_LABEL[job.type]
  const running = groups.length > 0 ? groups[groups.length - 1][0] : null
  const tokenText = job.tokenCap > 0 ? `${job.tokensUsed.toLocaleString("vi-VN")}/${job.tokenCap.toLocaleString("vi-VN")}` : job.tokensUsed.toLocaleString("vi-VN")
  const statusIcon = active ? (
    <Loader2 className="size-4 animate-spin motion-reduce:animate-none" aria-hidden />
  ) : job.status === "SUCCEEDED" ? (
    <CheckCircle2 className="size-4 text-green-600 dark:text-green-400" aria-hidden />
  ) : job.status === "CANCELLED" ? (
    <MinusCircle className="size-4 text-muted-foreground" aria-hidden />
  ) : (
    <XCircle className="size-4 text-amber-600 dark:text-amber-400" aria-hidden />
  )

  if (!expanded) {
    return (
      <div
        className={cn("pointer-events-auto flex w-80 items-center gap-2 rounded-lg border bg-card px-3 py-2 text-xs shadow-lg", STATUS_TONE[job.status])}
        role="group"
        aria-label={`Job sinh test, ${JOB_STATUS_LABEL[job.status]}`}
      >
        {statusIcon}
        <div className="min-w-0 flex-1">
          <p className="truncate font-medium">{JOB_STATUS_LABEL[job.status]}{running && active ? `: ${running}` : ""}</p>
          <p className="text-muted-foreground">
            {formatElapsed(elapsed)} · {tokenText} token
          </p>
        </div>
        <Button size="icon-sm" variant="ghost" aria-label="Mở khung giám sát" onClick={onExpand}>
          <Maximize2 className="size-4" />
        </Button>
        {active ? (
          <CancelButton onConfirm={() => cancelMutation.mutate()} pending={cancelMutation.isPending || job.status === "CANCELLING"} compact />
        ) : (
          <Button size="icon-sm" variant="ghost" aria-label="Đóng thông báo job" onClick={onDismiss}>
            <X className="size-4" />
          </Button>
        )}
      </div>
    )
  }

  return (
    <section
      ref={panelRef}
      tabIndex={-1}
      className={cn("pointer-events-auto flex max-h-[75vh] w-[560px] max-w-full flex-col rounded-lg border bg-card shadow-xl", STATUS_TONE[job.status])}
      aria-label="Giám sát sinh test"
      onKeyDown={(e) => e.key === "Escape" && onMinimize()}
    >
      <header className="flex flex-wrap items-center gap-2 border-b border-border px-4 py-3">
        {statusIcon}
        <div className="min-w-0 flex-1">
          <h2 className="truncate text-sm font-semibold">{label}</h2>
          <p className="text-xs text-muted-foreground">
            {JOB_STATUS_LABEL[job.status]}
            {job.dryRun ? " · chạy thử (không gọi AI/API đích)" : ""} · {formatElapsed(elapsed)} · {tokenText} token
          </p>
        </div>
        {active && <CancelButton onConfirm={() => cancelMutation.mutate()} pending={cancelMutation.isPending || job.status === "CANCELLING"} />}
        <Button size="icon-sm" variant="ghost" aria-label="Thu nhỏ khung giám sát" onClick={onMinimize}>
          <Minus className="size-4" />
        </Button>
        {!active && (
          <Button size="icon-sm" variant="ghost" aria-label="Đóng khung giám sát" onClick={onDismiss}>
            <X className="size-4" />
          </Button>
        )}
      </header>

      {job.status === "CANCELLING" && (
        <p role="status" className="border-b border-border bg-amber-500/5 px-4 py-2 text-xs text-amber-700 dark:text-amber-400">
          Đang huỷ: sẽ dừng ở bước an toàn tiếp theo (lần gọi AI hoặc chạy test đang dở vẫn kết thúc trước). Các bước đã làm được giữ lại.
        </p>
      )}
      {active && pollFailed && now - dataUpdatedAt > 15000 && (
        <p role="alert" className="border-b border-border bg-destructive/5 px-4 py-2 text-xs text-destructive">
          Mất kết nối tới máy chủ, dữ liệu có thể đã cũ (cập nhật lần cuối {formatElapsed(now - dataUpdatedAt)} trước). Job vẫn chạy ở backend; đang tự thử lại.
        </p>
      )}
      {cancelMutation.isError && (
        <p role="alert" className="px-4 py-2 text-xs text-destructive">
          {cancelMutation.error instanceof ApiError ? cancelMutation.error.message : "Không huỷ được, vui lòng thử lại."}
        </p>
      )}

      <div className="min-h-0 flex-1 overflow-y-auto px-4 py-3" role="log" aria-live="polite" aria-relevant="additions">
        {groups.length === 0 && <p className="text-xs text-muted-foreground">Đang khởi động...</p>}
        {groups.map(([endpointLabel, steps]) => (
          <div key={endpointLabel} className="mb-3 last:mb-0">
            <p className="mb-1 flex items-center gap-1 font-mono text-xs font-medium">
              <ChevronsUpDown className="size-3 text-muted-foreground" aria-hidden />
              {endpointLabel}
            </p>
            <ol className="flex flex-col gap-1.5 border-l border-border pl-3">
              {steps.map((s) => (
                <StepRow key={s.seq} step={!active && s.status === "RUNNING" ? { ...s, status: "SKIPPED" } : s} now={now} />
              ))}
            </ol>
          </div>
        ))}
      </div>

      {isTerminal(job.status) && <Summary job={job} projectId={projectId} />}
    </section>
  )
}

function StepRow({ step, now }: { step: GenerationStep; now: number }) {
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

const STOP_TEXT: Record<string, string> = {
  BUDGET_STOPPED: "Dừng vì chạm ngân sách token - phần đã trích được giữ lại. Tăng ngân sách rồi chạy lại để trích tiếp (mục trùng được bỏ qua).",
  DAILY_QUOTA: "Dừng vì hết quota token AI trong ngày - phần đã trích được giữ lại.",
  AI_ERROR: "Dừng vì gọi AI lỗi - phần đã trích được giữ lại.",
  WRITE_ERROR: "Dừng vì lỗi hệ thống khi lưu mục - phần đã lưu ở các lô trước được giữ.",
  NO_INPUT: "Không có tài liệu hay chỉ dẫn để trích (hãy bật AI gợi ý thêm hoặc thêm tài liệu). Tài liệu có thể vừa bị xoá.",
}

/** Phần tóm tắt checklist nghiệp vụ (job RULES cũ hoặc pha trích trong job FULL/SYSTEM). */
function RulesBlock({ job, projectId }: { job: GenerationJob; projectId: string }) {
  const r = job.rules
  if (!r) return null
  const pending = r.fromDocument + r.fromInstruction + r.inferred + r.conflicts
  return (
    <div className="mt-2 border-t border-border pt-2" aria-live="polite">
      <p className="font-medium">Checklist nghiệp vụ</p>
      {(
        <>
          <p className="font-medium">
            {job.dryRun ? "Chạy thử: " : ""}
            {r.endpointCount} endpoint · {r.lotsDone}/{r.lotsPlanned} lô · ngân sách checklist {r.tokenBudget.toLocaleString("vi-VN")} token
            {job.dryRun ? ` · ước tính tối đa ~${r.estimatedTokens.toLocaleString("vi-VN")} token` : ""}
          </p>
          {!job.dryRun && (
            <p className="mt-1">
              Đề xuất mới: {r.fromDocument} có trích dẫn tài liệu · {r.fromInstruction} từ chỉ dẫn · {r.inferred} GIẢ ĐỊNH
              {r.conflicts > 0 ? ` · ${r.conflicts} mâu thuẫn spec` : ""}. Tất cả đang ở "Chờ duyệt".
            </p>
          )}
          {!job.dryRun && (r.skippedExisting > 0 || r.skippedInferred > 0 || r.skippedOverCap > 0 || r.rejectedAsDuplicateOfSpec > 0) && (
            <p className="mt-1 text-muted-foreground">
              Bỏ qua: {r.skippedExisting} đã có · {r.skippedInferred} không có trích dẫn · {r.skippedOverCap} vượt trần · {r.rejectedAsDuplicateOfSpec} trùng
              ràng buộc spec
              {Object.keys(r.dropped ?? {}).length > 0 ? ` · ${Object.values(r.dropped ?? {}).reduce((a, b) => a + b, 0)} sai endpoint/mẫu/field` : ""}
            </p>
          )}
          {r.stoppedReason && <p className="mt-1.5 rounded-md border border-amber-500/30 bg-amber-500/5 p-2">{STOP_TEXT[r.stoppedReason] ?? r.stoppedReason}</p>}
          {r.suggestStoppedReason && (
            <p className="mt-1.5 rounded-md border border-amber-500/30 bg-amber-500/5 p-2">AI gợi ý thêm: {STOP_TEXT[r.suggestStoppedReason] ?? r.suggestStoppedReason}</p>
          )}
          {r.questions && r.questions.length > 0 && (
            <p className="mt-1.5 rounded-md border border-border bg-muted/40 p-2">AI có {r.questions.length} câu hỏi về chỗ tài liệu còn thiếu: xem ở màn duyệt.</p>
          )}
        </>
      )}
      {!job.dryRun && (
        <Link to={`/projects/${projectId}/rules?status=PROPOSED`} className="mt-2 inline-block text-primary underline underline-offset-4">
          Duyệt checklist ({pending} chờ duyệt)
        </Link>
      )}
    </div>
  )
}

function RulesSummary({ job, projectId }: { job: GenerationJob; projectId: string }) {
  return (
    <footer className="border-t border-border px-4 py-3 text-xs" aria-live="polite">
      {job.errorMessage && <p className="text-destructive">{job.errorMessage}</p>}
      <RulesBlock job={job} projectId={projectId} />
    </footer>
  )
}

function Summary({ job, projectId }: { job: GenerationJob; projectId: string }) {
  if (job.type === "RULES") return <RulesSummary job={job} projectId={projectId} />
  const needsHuman = job.results.filter((r) => r.status === "NEEDS_HUMAN" || r.status === "FAILED")
  const total = job.results.reduce((sum, r) => sum + r.filled, 0)
  const writeCalls = job.steps.filter(
    (st) => st.phase === "RUN" && st.httpStatus !== null && st.httpStatus >= 200 && st.httpStatus < 300 && /^(POST|PUT|PATCH|DELETE) /.test(st.endpointLabel ?? "")
  ).length
  return (
    <footer className="border-t border-border px-4 py-3 text-xs" aria-live="polite">
      <p className="font-medium">
        {job.results.length} endpoint · bổ sung {total} test · {job.tokensUsed.toLocaleString("vi-VN")} token AI
      </p>
      {job.errorMessage && <p className="mt-1 text-destructive">{job.errorMessage}</p>}
      {writeCalls > 0 && (
        <p className="mt-1.5 rounded-md border border-border bg-muted/40 p-2 text-muted-foreground">
          Đã có {writeCalls} lần chạy GHI THẬT thành công lên API đích (POST/PUT/PATCH/DELETE). Hệ thống chưa tự xoá dữ liệu này: nếu cần, dọn thủ công ở môi trường
          test.
        </p>
      )}
      {needsHuman.map((r) => (
        <div key={r.endpointId} className="mt-1.5 rounded-md border border-amber-500/30 bg-amber-500/5 p-2">
          <p className="font-mono">{r.label}</p>
          <p>{r.message}</p>
          {r.reason && REASON_HELP[r.reason] && <p className="text-muted-foreground">{REASON_HELP[r.reason]}</p>}
        </div>
      ))}
      <Link to={`/projects/${projectId}/test-cases`} className="mt-2 inline-block text-primary underline underline-offset-4">
        Xem test case
      </Link>
      <RulesBlock job={job} projectId={projectId} />
    </footer>
  )
}

function CancelButton({ onConfirm, pending, compact = false }: { onConfirm: () => void; pending: boolean; compact?: boolean }) {
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
