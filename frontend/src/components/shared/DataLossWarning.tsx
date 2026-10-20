import { TriangleAlert } from "lucide-react"

import type { ImportImpact } from "@/lib/endpoints"

/** Có dữ liệu nào sẽ mất không (không tính endpoint: endpoint được tạo lại từ spec chứ không "mất" theo nghĩa người dùng). */
export function hasDataToLose(impact: ImportImpact): boolean {
  return (
    impact.testCaseCount > 0 ||
    impact.executionCount > 0 ||
    impact.testResultCount > 0 ||
    impact.bugReportCount > 0 ||
    impact.generationEventCount > 0
  )
}

/**
 * Khối cảnh báo đỏ liệt kê bằng SỐ CỤ THỂ những gì sẽ bị xoá vĩnh viễn. Dòng nào bằng 0 thì ẩn để khối ngắn; phần
 * không thể sinh lại bằng AI (test case thêm tay, đã khoá) được nhấn mạnh hơn.
 */
export function DataLossWarning({ impact, title }: { impact: ImportImpact; title: string }) {
  const lines: { key: string; text: string; strong?: boolean }[] = []
  if (impact.testCaseCount > 0) {
    const extras: string[] = []
    if (impact.manualTestCaseCount > 0) extras.push(`${impact.manualTestCaseCount} thêm thủ công`)
    if (impact.lockedTestCaseCount > 0) extras.push(`${impact.lockedTestCaseCount} đã khoá`)
    lines.push({
      key: "tc",
      text: `${impact.testCaseCount} test case${extras.length > 0 ? ` (${extras.join(", ")})` : ""}`,
      strong: extras.length > 0,
    })
  }
  if (impact.testResultCount > 0 || impact.executionCount > 0) {
    lines.push({ key: "run", text: `${impact.executionCount} lần chạy, ${impact.testResultCount} kết quả chạy` })
  }
  if (impact.bugReportCount > 0) {
    lines.push({
      key: "bug",
      text: `${impact.bugReportCount} bug report${impact.openBugReportCount > 0 ? ` (${impact.openBugReportCount} chưa đóng)` : ""}`,
    })
  }
  if (impact.generationEventCount > 0) {
    lines.push({ key: "gen", text: `${impact.generationEventCount} bản ghi lịch sử sinh test case` })
  }

  return (
    <div role="alert" className="rounded-lg border border-destructive/40 bg-destructive/10 p-3 text-sm">
      <p className="flex items-center gap-2 font-medium text-destructive">
        <TriangleAlert className="h-4 w-4 shrink-0" />
        {title}
      </p>
      <ul className="mt-2 list-disc pl-5 text-foreground">
        {lines.map((line) => (
          <li key={line.key} className={line.strong ? "font-semibold text-destructive" : undefined}>
            {line.text}
          </li>
        ))}
      </ul>
      {impact.lockedTestCaseCount > 0 && (
        <p className="mt-2 text-xs font-medium text-destructive">
          Test case đã khoá cũng bị xoá - khoá chỉ chặn việc sinh lại, không chặn import.
        </p>
      )}
      <p className="mt-2 text-xs text-muted-foreground">
        Gồm cả assertion, liên kết dữ liệu giữa các test case và lịch sử bug report. Các dữ liệu này sẽ bị xoá vĩnh viễn
        và không khôi phục được.
      </p>
    </div>
  )
}
