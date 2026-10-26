import { useState } from "react"
import { Link } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { Loader2 } from "lucide-react"

import { coverageRowCounts, type CoverageEndpointRow } from "@/lib/coverage"
import { CoverageCountsText } from "@/components/coverage/TestStateChip"
import { getLatestGenerationPlan, type GenerationPlanSummary } from "@/lib/testcases"

interface GenerationPlanReportProps {
  projectId: string
  endpointId: string
  /** Báo cáo vừa nhận khi sinh trong phiên này - có thì dùng luôn, không gọi API. */
  freshPlan?: GenerationPlanSummary
  /** Tóm tắt coverage của endpoint (đã tải sẵn ở EndpointList) - không gọi thêm request. */
  coverage?: CoverageEndpointRow
}

/**
 * Module 15j - gộp báo cáo checklist vào trang Coverage: Coverage là nơi DUY NHẤT xem chi tiết từng mục
 * (trước đây báo cáo lần sinh chỉ đếm các loại được tick lúc sinh, còn Coverage đếm tất cả - 2 con số khác
 * nhau cho cùng 1 endpoint). Ở đây chỉ còn 2 dòng: (1) tình trạng coverage hiện tại + link mở thẳng đúng
 * endpoint ở trang Coverage; (2) kết quả lần sinh gần nhất của AI - phần Coverage không có vì nó chỉ nhìn bộ
 * test hiện tại, không biết case nào do AI viết đúng. Báo cáo lần sinh chỉ gọi API khi người dùng bấm xem.
 */
export function GenerationPlanReport({ projectId, endpointId, freshPlan, coverage }: GenerationPlanReportProps) {
  const [showPlan, setShowPlan] = useState(false)

  const { data: savedPlan, isLoading, isError } = useQuery({
    queryKey: ["generation-plan", projectId, endpointId],
    queryFn: () => getLatestGenerationPlan(projectId, endpointId),
    enabled: showPlan && !freshPlan,
  })
  const plan = freshPlan ?? savedPlan ?? null
  const counts = coverage ? coverageRowCounts(coverage) : null

  return (
    <div className="mt-3 flex flex-col gap-1 rounded-md border border-border bg-muted/30 px-3 py-2 text-xs leading-relaxed">
      {counts && (
        <p className="flex flex-wrap items-center gap-x-2">
          <span className="font-medium">Coverage:</span>
          <CoverageCountsText
            relevant={counts.relevant}
            covered={counts.covered}
            executed={counts.executed}
            certain={counts.certain}
            doubtful={counts.doubtful}
            missing={counts.missing}
            specGap={counts.specGap}
            baselineVerified={coverage?.baselineVerified}
          />
          <Link
            to={`/projects/${projectId}/coverage?endpoint=${endpointId}`}
            className="text-primary underline-offset-4 hover:underline"
          >
            Xem chi tiết ở Coverage →
          </Link>
        </p>
      )}

      <p className="flex flex-wrap items-center gap-x-2">
        <span className="font-medium">Lần sinh gần nhất:</span>
        {plan ? (
          <span>{aiSummary(plan)}</span>
        ) : showPlan ? (
          isLoading ? (
            <span className="flex items-center gap-1.5 text-muted-foreground">
              <Loader2 className="h-3 w-3 animate-spin" /> Đang tải...
            </span>
          ) : (
            <span className="text-muted-foreground">
              {isError
                ? "Không tải được, vui lòng thử lại."
                : "Chưa có báo cáo (test case được sinh trước khi có tính năng này) - sinh lại để có."}
            </span>
          )
        ) : (
          <button type="button" onClick={() => setShowPlan(true)} className="text-primary underline-offset-4 hover:underline">
            Xem tóm tắt
          </button>
        )}
      </p>
    </div>
  )
}

/** Phần chỉ báo cáo lần sinh có: AI làm đúng bao nhiêu mục được giao, hệ thống bù bao nhiêu, AI đề xuất thêm bao nhiêu. */
function aiSummary(plan: GenerationPlanSummary) {
  const extra = plan.extraCaseNames.length
  if (plan.aiAskedCount === 0) {
    return `không giao mục nào cho AI (đã có sẵn ${plan.alreadyCoveredCount}) · AI đề xuất thêm ${extra}`
  }
  return `AI làm đạt ${plan.aiSatisfiedCount}/${plan.aiAskedCount} mục được giao · hệ thống bù ${plan.systemFilledCount} · AI đề xuất thêm ${extra}`
}
