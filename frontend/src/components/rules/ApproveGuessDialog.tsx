import { useState } from "react"

import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Label } from "@/components/ui/label"
import { COMMON_REJECT_STATUSES, type BusinessRule } from "@/lib/rules"

interface Props {
  rule: BusinessRule | null
  pending: boolean
  onCancel: () => void
  onConfirm: (rule: BusinessRule, expectedStatus: number) => void
}

/**
 * Luật GIẢ ĐỊNH (AI đoán, không có trích dẫn) không thể duyệt "cho qua": không có nguồn nào nêu mã lỗi, nên người duyệt phải tự chọn mã lỗi
 * kỳ vọng. Backend cũng từ chối duyệt nếu thiếu mã này.
 */
export function ApproveGuessDialog({ rule, pending, onCancel, onConfirm }: Props) {
  const [custom, setCustom] = useState("")
  const [picked, setPicked] = useState<number | null>(null)
  const value = custom.trim() ? Number(custom) : picked
  const valid = value !== null && Number.isInteger(value) && value >= 400 && value <= 499

  return (
    <Dialog open={rule !== null} onOpenChange={(open) => !open && onCancel()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Duyệt luật GIẢ ĐỊNH</DialogTitle>
          <DialogDescription>
            AI đoán luật này, không có trích dẫn từ tài liệu. Nếu đúng là quy tắc của hệ thống, hãy chọn mã lỗi mà API phải trả khi bị vi phạm. Nếu API trả
            khác mã này, đó sẽ được ghi nhận là phát hiện lỗi của API.
          </DialogDescription>
        </DialogHeader>
        <p className="rounded-md bg-muted p-2 text-sm font-medium">{rule?.sentence}</p>
        <fieldset className="flex flex-wrap gap-1.5">
          <legend className="sr-only">Mã lỗi kỳ vọng khi vi phạm</legend>
          {COMMON_REJECT_STATUSES.map((code) => (
            <Button
              key={code}
              type="button"
              size="sm"
              variant={picked === code && !custom.trim() ? "default" : "outline"}
              aria-pressed={picked === code && !custom.trim()}
              onClick={() => {
                setPicked(code)
                setCustom("")
              }}
            >
              {code}
            </Button>
          ))}
        </fieldset>
        <div className="flex flex-col gap-1">
          <Label htmlFor="guess-status" className="text-xs">
            Hoặc nhập mã khác (400-499)
          </Label>
          <input
            id="guess-status"
            inputMode="numeric"
            value={custom}
            onChange={(e) => setCustom(e.target.value.replace(/\D/g, "").slice(0, 3))}
            className="h-8 w-28 rounded-md border border-input bg-transparent px-2 text-sm"
          />
        </div>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onCancel}>
            Huỷ
          </Button>
          <Button type="button" disabled={!valid || pending} onClick={() => rule && valid && onConfirm(rule, value)}>
            Duyệt với mã {valid ? value : "…"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
