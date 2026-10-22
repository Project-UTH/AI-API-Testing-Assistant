import { BadgeCheck } from "lucide-react"

import { cn } from "@/lib/utils"
import { getTestStateDisplay } from "@/lib/coverage"

/** 17q G2 - chip trạng thái kiểm thử của endpoint (chưa sinh màu xám, không tô đỏ - chưa làm không phải làm sai). */
export function TestStateChip({ state, className }: { state: string | null | undefined; className?: string }) {
  const display = getTestStateDisplay(state)
  if (!display) return null
  return (
    <span className={cn("shrink-0 rounded-full px-2 py-0.5 text-xs font-medium", display.chip, className)} title={display.title}>
      {display.label}
    </span>
  )
}

interface CoverageCountsTextProps {
  relevant: number
  covered: number
  executed: number
  certain: number
  doubtful: number
  missing: number
  specGap?: number
  baselineVerified?: boolean
}

/**
 * 17q G2 - "có test x/y · chạy z · chắc a · nghi b · thiếu m", đếm theo MỤC và lồng nhau (có test ⊇ đã chạy ⊇ kết luận chắc), cùng mẫu
 * số y. Không có thanh nhiều nấc hay % rời - tránh đọc nhầm thành "điểm chất lượng".
 */
export function CoverageCountsText({ relevant, covered, executed, certain, doubtful, missing, specGap, baselineVerified }: CoverageCountsTextProps) {
  return (
    <span
      className="inline-flex shrink-0 flex-wrap items-center gap-x-1 text-xs text-muted-foreground"
      title={`Lồng nhau: có test ⊇ đã chạy ⊇ kết luận chắc, cùng mẫu số ${relevant} mục cần kiểm`}
    >
      {baselineVerified && (
        <BadgeCheck className="h-3.5 w-3.5 text-emerald-600 dark:text-emerald-400" aria-label="Test gốc đã xác nhận bằng chạy thật">
          <title>Test gốc đã xác nhận bằng chạy thật</title>
        </BadgeCheck>
      )}
      <span>
        có test{" "}
        <span className="font-medium text-foreground">
          {covered}/{relevant}
        </span>
      </span>
      <span>· chạy {executed}</span>
      <span>· chắc {certain}</span>
      {doubtful > 0 && <span className="text-amber-600 dark:text-amber-400">· nghi {doubtful}</span>}
      {missing > 0 && <span className="text-amber-600 dark:text-amber-400">· thiếu {missing}</span>}
      {(specGap ?? 0) > 0 && (
        <span
          className="ml-1 rounded-full border border-dashed border-muted-foreground/50 px-1.5 text-[11px]"
          title="Mục biên ngầm: spec không khai ràng buộc nên kỳ vọng chỉ là quy ước - Fail ở đây không quy là lỗi API"
        >
          Spec thiếu ràng buộc: {specGap}
        </span>
      )}
    </span>
  )
}
