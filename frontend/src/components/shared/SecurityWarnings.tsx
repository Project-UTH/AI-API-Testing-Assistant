import { ShieldAlert } from "lucide-react"

import type { SecurityFinding } from "@/lib/executions"

/** Badge nhỏ trên dòng kết quả. Cảnh báo (cam), không phải lỗi (đỏ): Pass/Fail không đổi. */
export function SecurityBadge({ findings }: { findings?: SecurityFinding[] }) {
  if (!findings || findings.length === 0) return null
  return (
    <span
      className="inline-flex shrink-0 items-center gap-1 rounded-full bg-rose-500/10 px-2 py-0.5 text-xs font-medium text-rose-600 dark:text-rose-400"
      title="Phản hồi có thể chứa dữ liệu nhạy cảm - kết quả Pass/Fail không đổi"
    >
      <ShieldAlert className="size-3" aria-hidden />
      Dữ liệu nhạy cảm ({findings.length})
    </span>
  )
}

/** Khối chi tiết khi mở rộng dòng kết quả. Không bao giờ hiển thị giá trị thật, chỉ đường dẫn + mô tả. */
export function SecurityDetail({ findings }: { findings?: SecurityFinding[] }) {
  if (!findings || findings.length === 0) return null
  return (
    <div role="note" className="rounded-md border border-rose-500/30 bg-rose-500/5 p-3 text-xs">
      <p className="font-medium text-rose-700 dark:text-rose-400">
        Phản hồi có thể lộ dữ liệu nhạy cảm (chỉ cảnh báo, kết quả Pass/Fail không đổi)
      </p>
      <ul className="mt-1.5 list-disc pl-5 text-foreground">
        {findings.map((f) => (
          <li key={`${f.path}|${f.rule}`}>
            <span className="font-mono">{f.path}</span> - {f.detail}
          </li>
        ))}
      </ul>
      <p className="mt-1.5 text-muted-foreground">
        Phát hiện theo tên field/mẫu giá trị nên có thể nhầm. Giá trị thật không được lưu vào cảnh báo này.
      </p>
    </div>
  )
}
