import { useEffect, useMemo, useState } from "react"
import { Link, useParams } from "react-router-dom"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { CheckCircle2, Loader2, XCircle } from "lucide-react"

import { Button } from "@/components/ui/button"
import { CancelButton, formatElapsed, StepRow, useJobPolling } from "@/components/generation/JobParts"
import { ApiError } from "@/lib/api"
import { cancelJob, isTerminal, JOB_STATUS_LABEL, REASON_HELP, type GenerationJob, type GenerationStep } from "@/lib/generationJobs"
import { cn } from "@/lib/utils"
import { QuestionsAnswerCard } from "@/components/rules/AiQuestionsCard"
import { EndpointChecklistView } from "@/pages/BusinessRulesPage"

/** Trạng thái kết quả endpoint nghĩa là đã có test gốc hợp lệ (khớp backend): đủ điều kiện duyệt checklist và sinh test. */
const BASELINE_OK = new Set(["CONFIRMED", "ALREADY_HAS_BASELINE", "FILLED"])

const STAGES = ["Test gốc Pass", "AI lập checklist", "Bạn duyệt checklist", "Sinh test"]

/**
 * Trang chính của 1 lần "Sinh test case": tiến trình ở trên, bên dưới là màn duyệt checklist (hệ thống + AI) của từng endpoint và nút sinh test cuối.
 * Mở tự động khi bấm "Bắt đầu sinh"; thay cho việc chỉ có khung giám sát nhỏ ở góc màn hình.
 */
export function GenerationJobPage() {
  const { id, jobId } = useParams<{ id: string; jobId: string }>()
  const projectId = id!
  const queryClient = useQueryClient()
  const { data: job, error } = useJobPolling(projectId, jobId!)
  const [now, setNow] = useState(() => Date.now())
  const [selected, setSelected] = useState<string | null>(null)

  const active = !!job && !isTerminal(job.status)
  useEffect(() => {
    if (!active) return
    const t = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(t)
  }, [active])
  const cancel = useMutation({ mutationFn: () => cancelJob(projectId, jobId!), onSuccess: () => queryClient.invalidateQueries({ queryKey: ["gen-job", jobId] }) })

  const groups = useMemo(() => {
    const map = new Map<string, GenerationStep[]>()
    job?.steps.forEach((s) => map.set(s.endpointLabel ?? "Chung", [...(map.get(s.endpointLabel ?? "Chung") ?? []), s]))
    return Array.from(map.entries())
  }, [job?.steps])

  if (!job) {
    return (
      <p className={cn("mt-6", error ? "text-destructive" : "text-muted-foreground")}>
        {error ? (error instanceof ApiError ? error.message : "Không tải được lần sinh test này.") : "Đang tải..."}
      </p>
    )
  }

  const eligible = job.results.filter((r) => BASELINE_OK.has(r.status))
  const blocked = job.results.filter((r) => !BASELINE_OK.has(r.status) && r.status !== "DRY_RUN")
  const current = selected && eligible.some((r) => r.endpointId === selected) ? selected : (eligible[0]?.endpointId ?? null)
  const elapsedMs = (job.finishedAt ? new Date(job.finishedAt).getTime() : now) - new Date(job.startedAt ?? job.createdAt).getTime()
  const hasChecklistPhase = job.rules !== null || job.steps.some((s) => s.phase.startsWith("RULES"))

  // Đang ở bước nào của luồng: chạy -> (1 hoặc 2); xong và có endpoint đủ điều kiện -> 3 (chờ bạn); không có endpoint đủ điều kiện -> dừng ở 1.
  const stage = active ? (hasChecklistPhase ? 1 : 0) : eligible.length > 0 ? 2 : 0

  return (
    <div>
      <Link to={`/projects/${projectId}`} className="text-xs text-primary underline underline-offset-4">
        ← Về danh sách endpoint
      </Link>
      <div className="mt-2 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="flex items-center gap-2 text-lg font-semibold">
            {active ? <Loader2 className="size-4 animate-spin" aria-hidden /> : job.status === "SUCCEEDED" ? <CheckCircle2 className="size-4 text-green-600" aria-hidden /> : <XCircle className="size-4 text-amber-600" aria-hidden />}
            Sinh test case · {JOB_STATUS_LABEL[job.status]}
          </h2>
          <p className="mt-0.5 text-xs text-muted-foreground">
            {job.results.length > 0 ? `${job.results.length} endpoint` : "Đang khởi động"} · {formatElapsed(elapsedMs)} · {job.tokensUsed.toLocaleString("vi-VN")} token AI
            {job.dryRun ? " · chạy thử (không gọi AI/API đích)" : ""}
          </p>
        </div>
        {active && <CancelButton onConfirm={() => cancel.mutate()} pending={cancel.isPending || job.status === "CANCELLING"} />}
      </div>

      <ol className="mt-4 flex flex-wrap gap-2 text-xs" aria-label="Các bước của luồng sinh test">
        {STAGES.map((label, i) => (
          <li
            key={label}
            aria-current={i === stage ? "step" : undefined}
            className={cn("rounded-full border px-3 py-1", i < stage ? "border-green-500/40 bg-green-500/5 text-green-700 dark:text-green-400" : i === stage ? "border-primary bg-primary/10 font-medium text-primary" : "border-border text-muted-foreground")}
          >
            {i + 1}. {label}
          </li>
        ))}
      </ol>

      <NextAction job={job} active={active} eligibleCount={eligible.length} blockedCount={blocked.length} />

      {blocked.map((r) => (
        <div key={r.endpointId} className="mt-3 rounded-md border border-amber-500/30 bg-amber-500/5 p-3 text-sm">
          <p className="font-mono text-xs font-medium">{r.label}</p>
          <p className="mt-0.5">{r.message}</p>
          {r.reason && REASON_HELP[r.reason] && <p className="mt-1 text-xs text-muted-foreground">Cách xử lý: {REASON_HELP[r.reason]}</p>}
          {(r.reason === "MISSING_PARAM_VALUE" || r.reason === "RESOURCE_NOT_FOUND") && (
            <div className="mt-2 text-xs">
              <p className="text-muted-foreground">
                Endpoint này cần một id có thật trong hệ thống đích (id bịa sẽ bị trả 404). Hệ thống đã tự thử lấy id từ endpoint danh sách (chỉ đọc) và tự tạo dữ liệu bằng
                POST tạo tài nguyên (AI sinh test, chạy thật); kết quả từng lần thử nằm ở lý do phía trên. Nếu lỗi do quyền (403), kiểm tra token/vai trò của project; nếu API đích chưa có dữ liệu
                hoặc tạo bằng tải tệp, hãy nhập id có thật vào ô giá trị rồi chạy lại.
              </p>
              <Link to={`/projects/${projectId}?generate=${r.endpointId}`} className="mt-2 inline-block rounded-md border border-primary px-2.5 py-1 font-medium text-primary hover:bg-primary/5">
                Nhập giá trị và chạy lại
              </Link>
            </div>
          )}
        </div>
      ))}

      {!active && job.rules && <ChecklistResult job={job} />}
      {!active && job.rules && (job.rules.questions?.length ?? 0) > 0 && eligible.length > 0 && (
        <QuestionsAnswerCard projectId={projectId} endpointIds={eligible.map((r) => r.endpointId)} questions={job.rules.questions ?? []} />
      )}

      <details className="mt-4 rounded-md border border-border" open={active}>
        <summary className="cursor-pointer px-3 py-2 text-sm font-medium">Tiến trình chi tiết ({job.steps.length} bước)</summary>
        <div className="border-t border-border px-3 py-3">
          {groups.length === 0 && <p className="text-xs text-muted-foreground">Đang khởi động...</p>}
          {groups.map(([label, steps]) => (
            <div key={label} className="mb-3 last:mb-0">
              <p className="mb-1 font-mono text-xs font-medium">{label}</p>
              <ol className="flex flex-col gap-1.5 border-l border-border pl-3">
                {steps.map((s) => (
                  <StepRow key={s.seq} step={!active && s.status === "RUNNING" ? { ...s, status: "SKIPPED" } : s} now={now} />
                ))}
              </ol>
            </div>
          ))}
        </div>
      </details>

      {!active && eligible.length > 0 && current && (
        <section aria-label="Duyệt checklist và sinh test" className="mt-6 border-t border-border pt-4">
          <h3 className="text-base font-semibold">Duyệt checklist và sinh test</h3>
          {eligible.length > 1 && (
            <div role="tablist" aria-label="Chọn endpoint để duyệt" className="mt-2 flex flex-wrap gap-1.5">
              {eligible.map((r) => (
                <Button key={r.endpointId} type="button" role="tab" size="xs" variant={current === r.endpointId ? "default" : "outline"} aria-selected={current === r.endpointId} onClick={() => setSelected(r.endpointId)}>
                  <span className="font-mono">{r.label}</span>
                </Button>
              ))}
            </div>
          )}
          <div className="mt-3">
            <EndpointChecklistView key={current} endpointIdProp={current} embedded />
          </div>
        </section>
      )}
    </div>
  )
}

const STOP_TEXT: Record<string, string> = {
  BUDGET_STOPPED: "Dừng vì chạm trần token - phần đã lập được giữ lại. Tăng trần token rồi chạy lại để lập tiếp.",
  DAILY_QUOTA: "Dừng vì hết quota token AI trong ngày - phần đã lập được giữ lại.",
  AI_ERROR: "Dừng vì gọi AI lỗi - phần đã lập được giữ lại.",
  WRITE_ERROR: "Dừng vì lỗi hệ thống khi lưu - phần đã lưu ở các lô trước được giữ.",
  NO_INPUT: "Không có tài liệu hay chỉ dẫn để trích (hãy bật AI gợi ý thêm hoặc thêm tài liệu).",
}

/** Kết quả pha AI lập checklist: số mục mới, và nếu không có mục nào thì NÓI RÕ VÌ SAO (để không tưởng là hệ thống lỗi). */
function ChecklistResult({ job }: { job: GenerationJob }) {
  const r = job.rules
  if (!r) return null
  const created = r.fromDocument + r.fromInstruction + r.inferred + r.conflicts
  const reasons: string[] = []
  if (r.rejectedAsDuplicateOfSpec > 0) reasons.push(`${r.rejectedAsDuplicateOfSpec} mục AI đề xuất trùng ràng buộc spec đã có nên bị loại tự động (xem ở tab "Đã loại" của màn duyệt bên dưới)`)
  if (r.skippedExisting > 0) reasons.push(`${r.skippedExisting} mục đã có từ trước`)
  const dropped = Object.values(r.dropped ?? {}).reduce((a, b) => a + b, 0)
  if (dropped > 0) reasons.push(`${dropped} mục bị bộ kiểm chứng loại (sai endpoint/mẫu/field)`)
  if ((r.questions?.length ?? 0) > 0) reasons.push(`AI có ${r.questions?.length} câu hỏi về chỗ tài liệu chưa nói rõ (xem bên dưới, trả lời để AI lập thêm)`)
  return (
    <section aria-label="Kết quả AI lập checklist" className="mt-4 rounded-md border border-border p-3 text-sm">
      <h3 className="font-semibold">AI lập checklist nghiệp vụ</h3>
      <p className="mt-1">
        {created > 0
          ? `Đã đề xuất ${created} mục mới: ${r.fromDocument} có trích dẫn tài liệu, ${r.fromInstruction} từ chỉ dẫn, ${r.inferred} GIẢ ĐỊNH${r.conflicts > 0 ? `, ${r.conflicts} mâu thuẫn spec` : ""}. Tất cả đang chờ bạn duyệt.`
          : "AI không đề xuất được mục mới nào cho lần này."}
      </p>
      {created === 0 && (
        <p className="mt-1 text-xs text-muted-foreground">
          Điều này không có nghĩa hệ thống lỗi: tài liệu/spec có thể chưa nêu ràng buộc nghiệp vụ nào ngoài những gì spec đã khai cho endpoint này.
        </p>
      )}
      {reasons.length > 0 && (
        <ul className="mt-1 list-disc pl-5 text-xs text-muted-foreground">
          {reasons.map((t) => (
            <li key={t}>{t}</li>
          ))}
        </ul>
      )}
      {r.stoppedReason && <p className="mt-2 rounded-md border border-amber-500/30 bg-amber-500/5 p-2 text-xs">{STOP_TEXT[r.stoppedReason] ?? r.stoppedReason}</p>}
      {r.suggestStoppedReason && (
        <p className="mt-2 rounded-md border border-amber-500/30 bg-amber-500/5 p-2 text-xs">AI gợi ý thêm: {STOP_TEXT[r.suggestStoppedReason] ?? r.suggestStoppedReason}</p>
      )}
    </section>
  )
}

function NextAction({ job, active, eligibleCount, blockedCount }: { job: GenerationJob; active: boolean; eligibleCount: number; blockedCount: number }) {
  let tone = "border-border bg-muted/30"
  let title = ""
  let body = ""
  if (active) {
    title = "Đang chạy..."
    body = "Hệ thống sinh và chạy 1 test gốc, rồi AI lập checklist. Khi xong, trang này sẽ cho bạn duyệt checklist ngay bên dưới. Bạn có thể ở lại trang hoặc làm việc khác."
  } else if (job.status === "CANCELLED") {
    title = "Đã huỷ"
    body = "Các bước đã làm được giữ lại. Bấm 'Sinh test case' ở danh sách endpoint để chạy lại."
  } else if (job.dryRun) {
    title = "Đây là lần chạy thử"
    body = "Chạy thử chỉ liệt kê các bước sẽ làm, không gọi AI hay API đích và không tạo checklist. Bỏ tick 'Chạy thử' để chạy thật."
  } else if (eligibleCount > 0) {
    tone = "border-primary/40 bg-primary/5"
    title = "Việc bạn cần làm tiếp: duyệt checklist"
    body = "Bên dưới là checklist của hệ thống (tick mục muốn có test) và của AI (duyệt từng mục). Chưa duyệt thì chưa có test nào; xong bấm nút \"Sinh test từ checklist đã duyệt\" ở cuối phần duyệt."
  } else {
    tone = "border-amber-500/40 bg-amber-500/5"
    title = "Chưa có test gốc Pass nên chưa có checklist để duyệt"
    body = blockedCount > 0 ? "Xem nguyên nhân và cách xử lý ở khung vàng bên dưới, sửa xong rồi bấm 'Sinh test case' lại." : "Không có endpoint nào đủ điều kiện."
  }
  return (
    <div role="status" className={cn("mt-4 rounded-md border p-3", tone)}>
      <p className="text-sm font-semibold">{title}</p>
      <p className="mt-0.5 text-sm text-muted-foreground">{body}</p>
    </div>
  )
}
