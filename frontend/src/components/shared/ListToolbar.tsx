import type { ReactNode } from "react"
import { Search, X } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { cn, selectClassName } from "@/lib/utils"

export interface ListFilterSelect {
  /** Khớp với khoá bộ lọc của useListFilters. */
  key: string
  /** Dùng cho aria-label. */
  label: string
  /** Chữ của lựa chọn "tất cả" (value rỗng). */
  allLabel: string
  value: string
  options: { value: string; label: string }[]
  className?: string
}

interface ListToolbarProps {
  searchValue: string
  onSearchChange: (value: string) => void
  searchPlaceholder: string
  searchLabel: string
  selects?: ListFilterSelect[]
  onSelectChange?: (key: string, value: string) => void
  /** Số điều kiện đang lọc; > 0 thì hiện nút "Xoá bộ lọc". */
  activeFilterCount?: number
  onReset?: () => void
  /** Nút riêng của từng trang (Thêm, Chạy...). */
  trailing?: ReactNode
  className?: string
}

/** Thanh tìm kiếm + bộ lọc dùng chung cho các danh sách lớn (Endpoint, Test case, Coverage, Kết quả chạy). */
export function ListToolbar({
  searchValue,
  onSearchChange,
  searchPlaceholder,
  searchLabel,
  selects = [],
  onSelectChange,
  activeFilterCount = 0,
  onReset,
  trailing,
  className,
}: ListToolbarProps) {
  return (
    <form
      role="search"
      className={cn("flex flex-wrap items-center gap-2", className)}
      onSubmit={(event) => event.preventDefault()}
    >
      <div className="relative min-w-[14rem] flex-1">
        <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
        <Input
          type="search"
          autoComplete="off"
          aria-label={searchLabel}
          placeholder={searchPlaceholder}
          value={searchValue}
          onChange={(event) => onSearchChange(event.target.value)}
          className="pl-8"
        />
      </div>
      {selects.map((select) => (
        <select
          key={select.key}
          aria-label={select.label}
          value={select.value}
          onChange={(event) => onSelectChange?.(select.key, event.target.value)}
          className={cn(selectClassName, "sm:w-44", select.className)}
        >
          <option value="">{select.allLabel}</option>
          {select.options.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      ))}
      {activeFilterCount > 0 && onReset && (
        <Button type="button" variant="ghost" size="sm" onClick={onReset}>
          <X className="h-4 w-4" />
          Xoá bộ lọc ({activeFilterCount})
        </Button>
      )}
      {trailing}
    </form>
  )
}
