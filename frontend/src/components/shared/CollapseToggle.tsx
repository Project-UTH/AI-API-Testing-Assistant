import { ChevronRight } from "lucide-react"

import { cn } from "@/lib/utils"

interface CollapseToggleProps {
  collapsed: boolean
  onToggle: () => void
  /** Phần bị thu gọn, vd "test case của GET /pets" - ghép vào nhãn cho trình đọc màn hình và tooltip. */
  label: string
  /** id của vùng nội dung được thu gọn (chỉ gắn aria-controls khi vùng đó đang có trong DOM). */
  controlsId?: string
  className?: string
}

/** Nút mũi tên thu gọn/mở rộng 1 khối (dùng ở tiêu đề từng endpoint trong trang Test case và Lịch sử). */
export function CollapseToggle({ collapsed, onToggle, label, controlsId, className }: CollapseToggleProps) {
  const action = `${collapsed ? "Mở rộng" : "Thu gọn"} ${label}`
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-expanded={!collapsed}
      aria-controls={collapsed ? undefined : controlsId}
      aria-label={action}
      title={action}
      className={cn(
        "-ml-1 shrink-0 rounded-md p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none",
        className
      )}
    >
      <ChevronRight className={cn("size-4 transition-transform", !collapsed && "rotate-90")} aria-hidden />
    </button>
  )
}
