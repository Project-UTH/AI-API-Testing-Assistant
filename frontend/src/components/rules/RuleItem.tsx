import { memo, useRef } from "react"

import { Button } from "@/components/ui/button"
import { useRuleTimer } from "@/lib/reviewTiming"
import { cn } from "@/lib/utils"
import {
  expectedLabel,
  getRejectReasonLabel,
  getRuleSourceDisplay,
  getRuleStatusDisplay,
  getRuleTemplateLabel,
  type BusinessRule,
} from "@/lib/rules"

interface RuleItemProps {
  rule: BusinessRule
  busy: boolean
  onApprove: (rule: BusinessRule) => void
  onEdit: (rule: BusinessRule) => void
  onReject: (rule: BusinessRule) => void
  onDelete: (rule: BusinessRule) => void
  onViewQuote: (rule: BusinessRule) => void
}

/** 1 luật nghiệp vụ: câu luật + chip mẫu/nguồn/trạng thái + kỳ vọng + thao tác. memo: duyệt 1 luật không vẽ lại cả danh sách. */
export const RuleItem = memo(function RuleItem({ rule, busy, onApprove, onEdit, onReject, onDelete, onViewQuote }: RuleItemProps) {
  const itemRef = useRef<HTMLLIElement | null>(null)
  useRuleTimer(itemRef, rule.id)
  const status = getRuleStatusDisplay(rule.status)
  const source = getRuleSourceDisplay(rule.source)
  const canApprove = rule.status === "PROPOSED" || rule.status === "CONFLICT"
  const canEdit = rule.status !== "REJECTED"
  const canReject = rule.status !== "REJECTED"
  const canDelete = rule.source === "USER" && !rule.reviewedAt

  return (
    <li ref={itemRef} className={cn("flex flex-col gap-1.5 rounded-md border border-border p-3", rule.status === "REJECTED" && "opacity-60")}>
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <span className={cn("text-sm font-medium", rule.status === "REJECTED" && "line-through")}>{rule.sentence}</span>
        {status && (
          <span className={cn("rounded-full px-2 py-0.5 text-xs font-medium", status.className)} title={status.title}>
            {status.label}
          </span>
        )}
        {source && (
          <span className={cn("rounded-full px-2 py-0.5 text-xs font-medium", source.className)} title={source.title}>
            {source.label}
          </span>
        )}
        <span className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">{getRuleTemplateLabel(rule.template)}</span>
        <span className="text-xs text-muted-foreground">→ vi phạm: {expectedLabel(rule.expectedStatus)}</span>
        {rule.status === "APPROVED" && (
          <span className="text-xs text-muted-foreground">
            · {rule.testCount > 0 ? `${rule.testCount} test đã sinh` : "chưa sinh test"}
          </span>
        )}
      </div>

      {rule.quote && (
        <blockquote className="border-l-2 border-emerald-500/50 pl-2 text-xs text-muted-foreground">
          “{rule.quote}”
          {rule.documentId && rule.quoteOffset !== null && (
            <button type="button" className="ml-2 text-primary underline underline-offset-2" onClick={() => onViewQuote(rule)}>
              Xem trong tài liệu
            </button>
          )}
        </blockquote>
      )}
      {rule.source === "INFERRED" && !rule.quote && rule.status === "PROPOSED" && (
        <p className="text-xs text-amber-700 dark:text-amber-400">
          AI đoán, không có trích dẫn nào xác nhận. Khi duyệt bạn phải tự chọn mã lỗi kỳ vọng.
        </p>
      )}
      {rule.rationale && !rule.quote && <p className="text-xs text-muted-foreground">{rule.rationale}</p>}
      {rule.statusNote && (
        <p className={cn("text-xs", rule.status === "CONFLICT" ? "text-destructive" : "text-muted-foreground")}>{rule.statusNote}</p>
      )}
      {rule.status === "REJECTED" && rule.rejectReason && (
        <p className="text-xs text-muted-foreground">Lý do loại: {getRejectReasonLabel(rule.rejectReason)}</p>
      )}

      <div className="flex flex-wrap justify-end gap-1.5">
        {canApprove && (
          <Button type="button" size="xs" disabled={busy} onClick={() => onApprove(rule)}>
            {rule.status === "CONFLICT" ? "Duyệt (kèm ghi chú)" : rule.source === "INFERRED" ? "Duyệt (chọn mã lỗi)…" : "Duyệt"}
          </Button>
        )}
        {canEdit && (
          <Button type="button" size="xs" variant="outline" disabled={busy} onClick={() => onEdit(rule)}>
            Sửa
          </Button>
        )}
        {canReject && (
          <Button type="button" size="xs" variant="outline" disabled={busy} onClick={() => onReject(rule)}>
            Loại…
          </Button>
        )}
        {canDelete && (
          <Button type="button" size="xs" variant="ghost" disabled={busy} onClick={() => onDelete(rule)}>
            Xoá
          </Button>
        )}
      </div>
    </li>
  )
})
