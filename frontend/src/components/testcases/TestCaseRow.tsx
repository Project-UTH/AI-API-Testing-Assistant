import { memo } from "react"
import { Eye, Info, Lock, Pencil, ShieldAlert, Trash2, Unlock } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { cn } from "@/lib/utils"
import {
  expectationChanged,
  getReliabilityDisplay,
  originDisplay,
  TEST_CASE_SOURCE_LABEL,
  TEST_CASE_SOURCE_STYLES,
  type TestCase,
} from "@/lib/testcases"

interface TestCaseRowProps {
  testCase: TestCase
  selected: boolean
  lockPending: boolean
  /** Đang hiện dữ liệu cũ trong lúc tải trang mới: tắt các nút thao tác để không tác động nhầm lên dòng sắp bị thay. */
  disabled: boolean
  onToggleSelect: (testCaseId: string, checked: boolean) => void
  onToggleLock: (testCase: TestCase) => void
  onOpenDetail: (testCase: TestCase) => void
  onEdit: (testCase: TestCase) => void
  onDelete: (testCase: TestCase) => void
}

/** 1 dòng test case. memo: gõ ô tìm kiếm hay đổi chọn ở dòng khác không làm vẽ lại cả trang. */
export const TestCaseRow = memo(function TestCaseRow({
  testCase,
  selected,
  lockPending,
  disabled,
  onToggleSelect,
  onToggleLock,
  onOpenDetail,
  onEdit,
  onDelete,
}: TestCaseRowProps) {
  const reliability = getReliabilityDisplay(testCase)
  const origin = testCase.generationOrigin ? originDisplay(testCase.generationOrigin) : null
  return (
    <li className="flex items-center gap-3 rounded-md border border-border p-2">
      <Checkbox
        aria-label={`Chọn test case ${testCase.name}`}
        checked={selected}
        onCheckedChange={(checked) => onToggleSelect(testCase.id, checked === true)}
      />
      <span
        className={cn(
          "shrink-0 rounded-md px-2 py-0.5 text-xs font-medium",
          // Case hệ thống bù (15h) vẫn lưu source=AI_GENERATED để sinh lại/khoá chạy đúng,
          // nhưng không do AI viết - hiện "Hệ thống" cho khỏi hiểu nhầm.
          testCase.generationOrigin === "SYSTEM_FILLED"
            ? "bg-teal-500/10 text-teal-600 dark:text-teal-400"
            : TEST_CASE_SOURCE_STYLES[testCase.source]
        )}
      >
        {testCase.generationOrigin === "SYSTEM_FILLED" ? "Hệ thống" : TEST_CASE_SOURCE_LABEL[testCase.source]}
      </span>
      {testCase.probeKind === "MASS_ASSIGNMENT" && (
        <span
          className="inline-flex shrink-0 items-center gap-1 rounded-full border border-destructive/40 px-2 py-0.5 text-xs font-medium text-destructive"
          title="Test bảo mật tự sinh: thêm field không khai báo vào body. Gửi request ghi thật."
        >
          <ShieldAlert className="size-3" aria-hidden />
          Probe bảo mật
        </span>
      )}
      {reliability && (
        <span
          title={reliability.title}
          className={cn(
            "shrink-0 rounded-full px-2 py-0.5 text-xs font-medium",
            reliability.tone === "reliable"
              ? "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400"
              : "bg-amber-500/10 text-amber-600 dark:text-amber-400"
          )}
        >
          {reliability.label}
        </span>
      )}
      {origin && (
        <span
          title={origin.title + (testCase.checklistItem ? `\nMục checklist: ${testCase.checklistItem}` : "")}
          className={cn("shrink-0 rounded-full px-2 py-0.5 text-xs font-medium", origin.className)}
        >
          {origin.label}
        </span>
      )}
      {testCase.source !== "MANUAL" && testCase.rationale && (
        <span
          title={`${testCase.generationOrigin === "SYSTEM_FILLED" ? "Vì sao hệ thống tạo case này" : "Vì sao AI sinh case này"}: ${testCase.rationale}`}
          className="shrink-0"
        >
          <Info className="h-3.5 w-3.5 text-muted-foreground/60" />
        </span>
      )}
      <span className="min-w-0 flex-1 truncate text-sm">{testCase.name}</span>
      <span
        className="shrink-0 text-xs text-muted-foreground"
        title={expectationChanged(testCase) ? `Hệ thống đã đổi kỳ vọng sau khi chạy thật test gốc: ${testCase.expectationNote ?? ""}` : undefined}
      >
        → {testCase.expectedStatus}
        {expectationChanged(testCase) && (
          <span className="ml-1">
            (trước: <span className="line-through">{testCase.originalExpectedStatus}</span>)
          </span>
        )}
      </span>
      <Button
        size="icon-sm"
        variant="ghost"
        disabled={lockPending || disabled}
        aria-label={testCase.locked ? "Mở khoá test case" : "Khoá test case"}
        title={
          testCase.locked
            ? "Đang khoá - sẽ không bị xoá khi sinh test case lại. Bấm để mở khoá."
            : "Bấm để khoá - giữ nguyên test case này khi sinh test case lại"
        }
        onClick={() => onToggleLock(testCase)}
      >
        {testCase.locked ? (
          <Lock className="h-3.5 w-3.5 text-amber-600 dark:text-amber-400" />
        ) : (
          <Unlock className="h-3.5 w-3.5 text-muted-foreground" />
        )}
      </Button>
      <Button size="icon-sm" variant="ghost" aria-label="Xem chi tiết" title="Xem chi tiết" onClick={() => onOpenDetail(testCase)}>
        <Eye className="h-3.5 w-3.5" />
      </Button>
      <Button size="icon-sm" variant="ghost" aria-label="Sửa test case" title="Sửa" disabled={disabled} onClick={() => onEdit(testCase)}>
        <Pencil className="h-3.5 w-3.5" />
      </Button>
      <Button size="icon-sm" variant="ghost" aria-label="Xoá test case" title="Xoá" disabled={disabled} onClick={() => onDelete(testCase)}>
        <Trash2 className="h-3.5 w-3.5" />
      </Button>
    </li>
  )
})
