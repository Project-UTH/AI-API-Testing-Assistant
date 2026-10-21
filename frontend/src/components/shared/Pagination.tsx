import { ChevronLeft, ChevronRight } from "lucide-react"

import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"

interface PaginationProps {
  /** Trang hiện tại, tính từ 0. */
  page: number
  totalPages: number
  totalElements: number
  /** Số dòng đang hiển thị ở trang này. */
  shownCount: number
  size: number
  onPageChange: (page: number) => void
  /** Đang tải trang mới (vẫn hiện dữ liệu cũ): làm mờ và báo cho trình đọc màn hình. */
  isFetching?: boolean
  /** "test case" | "endpoint" | "kết quả" */
  itemLabel: string
  className?: string
  /** false cho bộ đếm thứ 2 ở cuối danh sách để trình đọc màn hình không đọc số 2 lần. */
  announce?: boolean
}

/** Bộ đếm "Hiển thị a-b / N" + nút Trước/Sau, cùng kiểu với trang Lịch sử toàn cục. */
export function Pagination({ page, totalPages, totalElements, shownCount, size, onPageChange, isFetching, itemLabel, className, announce = true }: PaginationProps) {
  const from = totalElements === 0 ? 0 : page * size + 1
  const to = page * size + shownCount
  return (
    <nav aria-label="Phân trang" className={cn("flex flex-wrap items-center justify-between gap-3", isFetching && "opacity-60 transition-opacity", className)} aria-busy={isFetching}>
      <p role={announce ? "status" : undefined} aria-live={announce ? "polite" : undefined} className="text-sm text-muted-foreground">
        {totalElements === 0 ? `Không có ${itemLabel} nào` : `Hiển thị ${from}-${to} / ${totalElements} ${itemLabel}`}
      </p>
      {totalPages > 1 && (
        <div className="flex items-center gap-3">
          <Button type="button" variant="outline" size="sm" disabled={page <= 0} onClick={() => onPageChange(page - 1)}>
            <ChevronLeft className="h-4 w-4" />
            Trước
          </Button>
          <span className="text-sm text-muted-foreground" aria-current="page">
            Trang {page + 1}/{totalPages}
          </span>
          <Button type="button" variant="outline" size="sm" disabled={page + 1 >= totalPages} onClick={() => onPageChange(page + 1)}>
            Sau
            <ChevronRight className="h-4 w-4" />
          </Button>
        </div>
      )}
    </nav>
  )
}
