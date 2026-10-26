import { cn } from "@/lib/utils"
import type { BugFrequency, BugPriority, BugSeverity, BugStatus } from "@/lib/bugReports"

export const BUG_STATUS_LABEL: Record<BugStatus, string> = {
  NEW: "Mới",
  NEED_REVISE: "Cần bổ sung",
  READY_TO_SUBMIT: "Sẵn sàng báo cáo",
  POSTED: "Đã báo cáo",
  PENDING: "Đang chờ xử lý",
  CANNOT_REPRODUCE: "Không tái hiện được",
  REOPENED: "Mở lại",
  CLOSED: "Đã đóng",
}

export const BUG_STATUS_STYLES: Record<BugStatus, string> = {
  NEW: "bg-slate-500/10 text-slate-600 dark:text-slate-400",
  NEED_REVISE: "bg-slate-500/10 text-slate-600 dark:text-slate-400",
  READY_TO_SUBMIT: "bg-blue-500/10 text-blue-600 dark:text-blue-400",
  POSTED: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  PENDING: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  CANNOT_REPRODUCE: "bg-purple-500/10 text-purple-600 dark:text-purple-400",
  REOPENED: "bg-orange-500/10 text-orange-600 dark:text-orange-400",
  CLOSED: "bg-green-500/10 text-green-600 dark:text-green-400",
}

export const BUG_SEVERITY_LABEL: Record<BugSeverity, string> = {
  MINOR: "Nhẹ",
  SERIOUS: "Nghiêm trọng",
  CRITICAL: "Chí mạng",
}

export const BUG_PRIORITY_LABEL: Record<BugPriority, string> = {
  TRIVIAL: "Không đáng kể",
  MINOR: "Thấp",
  MAJOR: "Cao",
  CRITICAL: "Nghiêm trọng",
  BLOCKER: "Chặn release",
}

export const BUG_FREQUENCY_LABEL: Record<BugFrequency, string> = {
  SELDOM: "Hiếm khi",
  OFTEN: "Thường xuyên",
  ALWAYS: "Luôn luôn",
}

function Chip({ label, style, className }: { label: string; style: string; className?: string }) {
  return (
    <span className={cn("inline-flex shrink-0 items-center rounded-md px-2 py-0.5 text-xs font-semibold", style, className)}>
      {label}
    </span>
  )
}

export function BugStatusBadge({ status, className }: { status: BugStatus; className?: string }) {
  return <Chip label={BUG_STATUS_LABEL[status]} style={BUG_STATUS_STYLES[status]} className={className} />
}
