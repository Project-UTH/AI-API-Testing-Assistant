import type { ReactNode } from "react"

import { Button } from "@/components/ui/button"

interface ListEmptyStateProps {
  /** true khi danh sách rỗng vì đang lọc/tìm kiếm (khác với chưa có dữ liệu nào). */
  filtered: boolean
  emptyText: string
  filteredText: string
  onReset?: () => void
  action?: ReactNode
}

export function ListEmptyState({ filtered, emptyText, filteredText, onReset, action }: ListEmptyStateProps) {
  return (
    <div className="rounded-lg border border-dashed p-8 text-center text-sm text-muted-foreground">
      <p>{filtered ? filteredText : emptyText}</p>
      <div className="mt-3 flex flex-wrap justify-center gap-2">
        {filtered && onReset && (
          <Button type="button" variant="outline" size="sm" onClick={onReset}>
            Xoá bộ lọc
          </Button>
        )}
        {action}
      </div>
    </div>
  )
}
