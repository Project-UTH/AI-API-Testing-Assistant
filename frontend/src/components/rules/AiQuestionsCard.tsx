import { useState } from "react"
import { useNavigate } from "react-router-dom"
import { useMutation, useQuery } from "@tanstack/react-query"
import { HelpCircle, Loader2, Send } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Textarea } from "@/components/ui/textarea"
import { ApiError } from "@/lib/api"
import { listEndpoints } from "@/lib/endpoints"
import { listRecentJobs, startJob } from "@/lib/generationJobs"
import { getRequirementDocUsage } from "@/lib/requirementDocs"

const MAX_INSTRUCTION = 4000

/** Ghép các câu đã trả lời thành "chỉ dẫn" gửi AI: mỗi cặp là nguồn trích dẫn nguyên văn hợp lệ (nhãn "Từ chỉ dẫn"). */
export function buildAnswerInstruction(pairs: { q: string; a: string }[]): string {
  let text = "Người dùng đã trả lời các câu hỏi về chỗ tài liệu còn thiếu. Hãy dựa vào câu trả lời để lập thêm mục checklist.\n"
  for (const { q, a } of pairs) {
    const block = `\nCâu hỏi: ${q.slice(0, 400)}\nTrả lời: ${a.trim()}\n`
    if (text.length + block.length > MAX_INSTRUCTION) break
    text += block
  }
  return text
}

interface Props {
  projectId: string
  /** Endpoint mà checklist được lập lại (đã có test gốc Pass). */
  endpointIds: string[]
  questions: string[]
}

/**
 * Câu hỏi AI nêu về chỗ tài liệu còn thiếu (thay vì đoán). Bạn trả lời vào ô bên dưới rồi gửi: AI đọc lại tài liệu cùng câu trả lời và lập thêm mục checklist
 * (nguồn "Từ chỉ dẫn", có trích dẫn câu trả lời của bạn). Không sinh lại test gốc, chỉ lập lại checklist.
 */
export function QuestionsAnswerCard({ projectId, endpointIds, questions }: Props) {
  const navigate = useNavigate()
  const [answers, setAnswers] = useState<Record<number, string>>({})
  const { data: endpointsPage } = useQuery({ queryKey: ["endpoints", projectId], queryFn: () => listEndpoints(projectId) })
  const { data: usage = [] } = useQuery({ queryKey: ["requirement-doc-usage", projectId], queryFn: () => getRequirementDocUsage(projectId) })

  const answered = questions.map((q, i) => ({ q, a: answers[i] ?? "" })).filter((p) => p.a.trim().length > 0)
  const send = useMutation({
    mutationFn: () => {
      // Chỉ gửi các tài liệu đã từng dùng cho đúng các endpoint này (đỡ tốn token); chưa có lịch sử thì để backend dùng mọi tài liệu đang có.
      const keys = new Set((endpointsPage?.data ?? []).filter((e) => endpointIds.includes(e.id)).map((e) => `${e.method.toUpperCase()} ${e.path}`))
      const docIds = [...new Set(usage.filter((u) => u.documentActive && keys.has(u.endpointKey)).map((u) => u.documentId))]
      return startJob(projectId, {
        endpointIds,
        type: "FULL",
        businessChecklist: true,
        checklistOnly: true,
        instruction: buildAnswerInstruction(answered),
        documentIds: docIds.length > 0 ? docIds : undefined,
        tokenCap: 60_000,
      })
    },
    onSuccess: (job) => navigate(`/projects/${projectId}/jobs/${job.id}`),
  })

  if (questions.length === 0) return null
  return (
    <section aria-label="Câu hỏi của AI" className="mt-4 rounded-md border border-amber-500/30 bg-amber-500/5 p-3 text-sm">
      <h3 className="flex items-center gap-1.5 font-medium">
        <HelpCircle className="size-4" aria-hidden />
        AI cần bạn làm rõ ({questions.length})
      </h3>
      <p className="mt-1 text-xs text-muted-foreground">
        Tài liệu chưa nói rõ các điểm dưới đây nên AI không đoán. Trả lời câu nào bạn biết (bỏ trống câu không biết), rồi gửi: AI sẽ đọc lại và lập thêm mục checklist dựa trên
        câu trả lời của bạn. Chỉ lập lại checklist, không sinh lại test gốc.
      </p>
      <ol className="mt-3 flex flex-col gap-3">
        {questions.map((q, i) => (
          <li key={q} className="text-xs">
            <label htmlFor={`ai-answer-${i}`} className="font-medium">
              {i + 1}. {q}
            </label>
            <Textarea
              id={`ai-answer-${i}`}
              rows={2}
              maxLength={600}
              className="mt-1"
              placeholder="Câu trả lời của bạn (để trống nếu chưa biết)"
              value={answers[i] ?? ""}
              onChange={(e) => setAnswers((prev) => ({ ...prev, [i]: e.target.value }))}
            />
          </li>
        ))}
      </ol>
      {send.isError && (
        <p role="alert" className="mt-2 text-xs text-destructive">
          {send.error instanceof ApiError ? send.error.message : "Không gửi được, vui lòng thử lại."}
        </p>
      )}
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <Button type="button" size="sm" disabled={answered.length === 0 || send.isPending || endpointIds.length === 0} onClick={() => send.mutate()}>
          {send.isPending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Send className="h-3.5 w-3.5" />}
          Gửi {answered.length} câu trả lời cho AI
        </Button>
        <span className="text-xs text-muted-foreground">Tốn token AI (đọc lại tài liệu), tính trong trần 60.000 của lần gửi này.</span>
      </div>
    </section>
  )
}

/** Dùng ở màn duyệt checklist của 1 endpoint: lấy câu hỏi từ lần chạy gần nhất có câu hỏi của project. */
export function AiQuestionsCard({ projectId, endpointId }: { projectId: string; endpointId: string }) {
  const { data: jobs = [] } = useQuery({ queryKey: ["generation-jobs", projectId, "recent"], queryFn: () => listRecentJobs(projectId) })
  const latest = jobs.find((j) => (j.rules?.questions?.length ?? 0) > 0)
  return <QuestionsAnswerCard projectId={projectId} endpointIds={[endpointId]} questions={latest?.rules?.questions ?? []} />
}
