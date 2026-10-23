import { useEffect, useState } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { ApiError } from "@/lib/api"
import { rejectBusinessRule, RULE_REJECT_REASON_LABEL, type BusinessRule, type RuleRejectReason } from "@/lib/rules"

/** Lý do chọn tay (DUPLICATE_OF_SPEC là lý do hệ thống tự loại, không cho người dùng chọn). */
const CHOICES: RuleRejectReason[] = ["WRONG_MEANING", "DUPLICATE", "NOT_APPLICABLE"]

interface RejectRuleDialogProps {
  projectId: string
  rule: BusinessRule | null
  onClose: () => void
}

/** Loại luật BẮT BUỘC chọn lý do; luật bị loại không xoá (giữ để đo độ chính xác trích luật) và chỉ ẩn sau bộ lọc. */
export function RejectRuleDialog({ projectId, rule, onClose }: RejectRuleDialogProps) {
  const queryClient = useQueryClient()
  const [reason, setReason] = useState<RuleRejectReason | "">("")
  const [note, setNote] = useState("")

  useEffect(() => {
    if (rule) {
      setReason("")
      setNote("")
    }
  }, [rule])

  const mutation = useMutation({
    mutationFn: () => rejectBusinessRule(projectId, rule!.id, { reason: reason as RuleRejectReason, note: note || undefined }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["rules", projectId] })
      queryClient.invalidateQueries({ queryKey: ["project-coverage", projectId] })
      onClose()
    },
  })

  return (
    <Dialog open={rule !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Loại luật</DialogTitle>
          <DialogDescription>{rule?.sentence}</DialogDescription>
        </DialogHeader>
        <fieldset className="flex flex-col gap-1.5">
          <legend className="text-xs font-medium">Vì sao loại? (bắt buộc)</legend>
          {CHOICES.map((value) => (
            <label key={value} className="flex items-center gap-2 text-sm">
              <input type="radio" name="reject-reason" checked={reason === value} onChange={() => setReason(value)} />
              {RULE_REJECT_REASON_LABEL[value]}
            </label>
          ))}
        </fieldset>
        <div className="flex flex-col gap-1">
          <Label htmlFor="reject-note" className="text-xs">
            Ghi chú (tuỳ chọn)
          </Label>
          <Textarea id="reject-note" rows={2} value={note} onChange={(e) => setNote(e.target.value)} />
        </div>
        {mutation.isError && (
          <p role="alert" className="text-sm text-destructive">
            {mutation.error instanceof ApiError ? mutation.error.message : "Đã xảy ra lỗi, vui lòng thử lại"}
          </p>
        )}
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>
            Huỷ
          </Button>
          <Button type="button" disabled={!reason || mutation.isPending} onClick={() => mutation.mutate()}>
            Loại luật
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
