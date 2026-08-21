import { AlertTriangle, Ban, CheckCircle2, SkipForward, XCircle, type LucideIcon } from "lucide-react"

import { cn } from "@/lib/utils"
import type { TestResultStatus } from "@/lib/executions"

/**
 * Nguồn sự thật duy nhất cho màu/nhãn/icon của TestResultStatus - tách ra từ TestExecutionPage.tsx
 * (trước đây định nghĩa cứng, lặp lại ở nhiều nơi) để dùng chung cho Test Case/Lịch sử/Kết quả
 * thực thi/Bug Report, tránh raw enum "PASSED"/"FAILED" hiện thẳng ra UI.
 */
export const RESULT_STATUS_STYLES: Record<TestResultStatus, string> = {
  PASSED: "bg-green-500/10 text-green-600 dark:text-green-400",
  FAILED: "bg-destructive/10 text-destructive",
  ERROR: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  BLOCKED: "bg-muted text-muted-foreground",
  SKIPPED: "bg-muted text-muted-foreground",
}

export const RESULT_STATUS_LABEL: Record<TestResultStatus, string> = {
  PASSED: "Pass",
  FAILED: "Fail",
  ERROR: "Lỗi",
  BLOCKED: "Bị chặn",
  SKIPPED: "Bỏ qua",
}

export const RESULT_STATUS_ICON: Record<TestResultStatus, LucideIcon> = {
  PASSED: CheckCircle2,
  FAILED: XCircle,
  ERROR: AlertTriangle,
  BLOCKED: Ban,
  SKIPPED: SkipForward,
}

interface StatusBadgeProps {
  status: TestResultStatus
  className?: string
  /** Chip nhỏ gọn hơn - dùng ở nơi liệt kê nhiều badge cạnh nhau (vd dòng tóm tắt Lịch sử kiểm thử). */
  compact?: boolean
  /** Khi có - render <button> thay vì <span> (vd bấm badge "Fail" để mở form tạo Bug Report). */
  onClick?: () => void
}

export function StatusBadge({ status, className, compact, onClick }: StatusBadgeProps) {
  const content = (
    <>
      {RESULT_STATUS_LABEL[status]}
    </>
  )
  const sharedClassName = cn(
    "inline-flex shrink-0 items-center justify-center rounded-md text-center font-semibold",
    compact ? "px-1.5 py-0.5 text-[11px]" : "w-16 px-2 py-0.5 text-xs",
    RESULT_STATUS_STYLES[status],
    onClick && "cursor-pointer hover:opacity-80",
    className
  )

  if (onClick) {
    return (
      // stopPropagation - nơi dùng badge này thường đặt trong 1 hàng có thể bấm để mở rộng (vd
      // TestExecutionPage), tự chặn nổi bọt để không kích hoạt luôn hành động của hàng cha khi
      // người dùng chỉ định bấm riêng badge "Fail".
      <button
        type="button"
        onClick={(e) => {
          e.stopPropagation()
          onClick()
        }}
        className={sharedClassName}
      >
        {content}
      </button>
    )
  }
  return <span className={sharedClassName}>{content}</span>
}
