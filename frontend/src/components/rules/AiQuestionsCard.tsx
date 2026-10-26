import { useQuery } from "@tanstack/react-query"
import { HelpCircle } from "lucide-react"

import { listRecentJobs } from "@/lib/generationJobs"

/**
 * Câu hỏi AI nêu về chỗ tài liệu còn thiếu (thay vì đoán). Lấy từ lần chạy gần nhất có câu hỏi; trả lời bằng cách thêm vào ô "Chỉ dẫn thêm" rồi sinh lại.
 */
export function AiQuestionsCard({ projectId }: { projectId: string }) {
  const { data: jobs = [] } = useQuery({ queryKey: ["generation-jobs", projectId, "recent"], queryFn: () => listRecentJobs(projectId) })
  const latest = jobs.find((j) => (j.rules?.questions?.length ?? 0) > 0)
  const questions = latest?.rules?.questions ?? []
  if (questions.length === 0) return null
  return (
    <section aria-label="Câu hỏi của AI" className="mt-4 rounded-md border border-amber-500/30 bg-amber-500/5 p-3 text-sm">
      <h3 className="flex items-center gap-1.5 font-medium">
        <HelpCircle className="size-4" aria-hidden />
        AI cần bạn làm rõ ({questions.length})
      </h3>
      <ul className="mt-1.5 list-disc pl-5 text-xs">
        {questions.map((q) => (
          <li key={q}>{q}</li>
        ))}
      </ul>
      <p className="mt-2 text-xs text-muted-foreground">
        Tài liệu chưa nói rõ các điểm trên nên AI không đoán. Hãy ghi câu trả lời vào ô "Chỉ dẫn thêm cho AI" khi bấm "Sinh test case" lại (tick "AI bổ sung checklist
        nghiệp vụ").
      </p>
    </section>
  )
}
