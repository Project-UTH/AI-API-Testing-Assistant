import { useState } from "react"

import type { ImportDiff } from "@/lib/endpoints"

const MAX_SHOWN = 8

function Group({ title, items, tone }: { title: string; items: string[]; tone: string }) {
  const [expanded, setExpanded] = useState(false)
  if (items.length === 0) return null
  const shown = expanded ? items : items.slice(0, MAX_SHOWN)
  return (
    <div className="text-xs">
      <p className={tone}>{title} ({items.length})</p>
      <ul className="mt-0.5 flex flex-col gap-0.5 font-mono text-muted-foreground">
        {shown.map((name) => (
          <li key={name} className="truncate" title={name}>
            {name}
          </li>
        ))}
      </ul>
      {items.length > MAX_SHOWN && (
        <button type="button" className="mt-0.5 text-primary underline-offset-4 hover:underline" onClick={() => setExpanded((v) => !v)}>
          {expanded ? "Thu gọn" : `Xem thêm ${items.length - MAX_SHOWN}`}
        </button>
      )}
    </div>
  )
}

/**
 * Module 17 (17c) - kết quả so sánh spec mới với endpoint hiện có, hiện TRƯỚC khi người dùng xác nhận import theo chế độ
 * "cập nhật theo thay đổi". Không chứa dữ liệu bị xoá: chế độ này giữ test case/lịch sử của endpoint không đổi.
 */
export function ImportDiffPreview({ diff }: { diff: ImportDiff }) {
  return (
    <div className="flex flex-col gap-3 rounded-lg border border-border p-3" aria-live="polite">
      <div className="grid grid-cols-4 gap-2 text-center">
        {[
          { label: "Thêm mới", value: diff.addedCount, tone: "text-green-600 dark:text-green-400" },
          { label: "Đổi spec", value: diff.changedCount, tone: "text-amber-600 dark:text-amber-400" },
          { label: "Không còn", value: diff.removedCount, tone: "text-destructive" },
          { label: "Không đổi", value: diff.unchangedCount, tone: "text-muted-foreground" },
        ].map((cell) => (
          <div key={cell.label} className="rounded-md bg-muted/50 px-2 py-1.5">
            <p className={`text-lg font-semibold ${cell.tone}`}>{cell.value}</p>
            <p className="text-xs text-muted-foreground">{cell.label}</p>
          </div>
        ))}
      </div>

      {diff.affectedTestCaseCount > 0 && (
        <p className="rounded-md bg-amber-500/10 p-2 text-xs text-amber-700 dark:text-amber-400">
          {diff.affectedTestCaseCount} test case thuộc endpoint đã đổi spec - có thể lỗi thời, nên xem lại hoặc chạy lại sau khi import.
        </p>
      )}
      {diff.removedCount > 0 && (
        <p className="text-xs text-muted-foreground">
          Endpoint không còn trong spec được GIỮ nguyên cùng test case (chỉ đánh dấu &quot;không còn trong spec&quot;), không bị xoá.
        </p>
      )}

      <Group title="Thêm mới" items={diff.addedEndpoints} tone="font-medium text-green-600 dark:text-green-400" />
      <Group title="Đổi spec" items={diff.changedEndpoints} tone="font-medium text-amber-600 dark:text-amber-400" />
      <Group title="Không còn trong spec" items={diff.removedEndpoints} tone="font-medium text-destructive" />
    </div>
  )
}
