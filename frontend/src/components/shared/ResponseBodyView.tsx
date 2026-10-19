import { ShieldAlert } from "lucide-react"

import { formatResponseBody } from "@/lib/utils"

/** Dấu thay thế mà backend ghi vào body khi che giá trị nhạy cảm (SensitiveDataScanner.MASK_MARKER). */
export const MASK_MARKER = "***ĐÃ CHE***"

/**
 * Hiển thị nội dung phản hồi đã lưu. Nếu backend đã che giá trị nhạy cảm thì báo rõ đây không phải bản gốc và tô dấu che.
 * Không bao giờ có cách lấy lại giá trị gốc (đã bị thay trước khi lưu).
 */
export function ResponseBodyView({ body }: { body: string | null }) {
  const formatted = formatResponseBody(body)
  if (!formatted) return <p className="text-xs text-muted-foreground">Không có nội dung phản hồi.</p>
  const masked = formatted.includes(MASK_MARKER)
  const parts = masked ? formatted.split(MASK_MARKER) : [formatted]
  return (
    <div className="flex flex-col gap-1">
      {masked && (
        <p className="flex items-center gap-1 text-xs text-amber-700 dark:text-amber-400">
          <ShieldAlert className="size-3.5 shrink-0" aria-hidden />
          Giá trị nhạy cảm đã được thay bằng "{MASK_MARKER}" trước khi lưu. Nội dung này không phải bản gốc.
        </p>
      )}
      <pre
        tabIndex={0}
        aria-label="Nội dung phản hồi"
        className="max-h-80 overflow-auto rounded-md bg-muted p-2 text-xs whitespace-pre-wrap break-all"
      >
        {parts.map((part, index) => (
          <span key={index}>
            {index > 0 && <mark className="rounded bg-amber-500/20 px-0.5 text-amber-700 dark:text-amber-300">{MASK_MARKER}</mark>}
            {part}
          </span>
        ))}
      </pre>
    </div>
  )
}
