import { useEffect, useState } from "react"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Label } from "@/components/ui/label"
import { expectedLabel, type BusinessRule } from "@/lib/rules"

interface Props {
  open: boolean
  /** Luật có trích dẫn, đang chờ duyệt (đã lọc ở trang). */
  rules: BusinessRule[]
  pending: boolean
  onClose: () => void
  onConfirm: (ruleIds: string[]) => void
}

/**
 * Duyệt hàng loạt CHỈ luật có trích dẫn: mỗi luật hiện đủ câu luật + đoạn trích để bạn đối chiếu, và mặc định KHÔNG tick sẵn - duyệt phải là hành
 * động có chủ đích, không phải bấm một nút cho xong.
 */
export function BatchApproveDialog({ open, rules, pending, onClose, onConfirm }: Props) {
  const [ticked, setTicked] = useState<Set<string>>(new Set())
  useEffect(() => {
    if (open) setTicked(new Set())
  }, [open])

  const all = rules.length > 0 && ticked.size === rules.length
  const toggle = (id: string, on: boolean) =>
    setTicked((prev) => {
      const next = new Set(prev)
      if (on) next.add(id)
      else next.delete(id)
      return next
    })

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Duyệt hàng loạt luật có trích dẫn</DialogTitle>
          <DialogDescription>
            Đối chiếu từng luật với đoạn trích rồi tick những luật đúng. Luật GIẢ ĐỊNH và luật mâu thuẫn spec không nằm ở đây vì phải duyệt riêng từng luật.
          </DialogDescription>
        </DialogHeader>
        <Label className="items-center gap-2 text-sm font-normal">
          <Checkbox checked={all} indeterminate={ticked.size > 0 && !all} onCheckedChange={(v) => setTicked(v ? new Set(rules.map((r) => r.id)) : new Set())} />
          Chọn tất cả sau khi đã đọc ({ticked.size}/{rules.length})
        </Label>
        <ul className="flex max-h-[50vh] flex-col gap-2 overflow-y-auto">
          {rules.map((rule) => (
            <li key={rule.id} className="rounded-md border border-border p-2.5">
              <Label className="items-start gap-2 text-sm font-normal">
                <Checkbox className="mt-0.5" checked={ticked.has(rule.id)} onCheckedChange={(v) => toggle(rule.id, v === true)} />
                <span className="flex flex-col gap-1">
                  <span className="font-medium">{rule.sentence}</span>
                  <span className="font-mono text-xs text-muted-foreground">{rule.endpointKey}</span>
                  {rule.quote && <span className="border-l-2 border-emerald-500/50 pl-2 text-xs text-muted-foreground">“{rule.quote}”</span>}
                  <span className="text-xs text-muted-foreground">→ vi phạm: {expectedLabel(rule.expectedStatus)}</span>
                </span>
              </Label>
            </li>
          ))}
        </ul>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>
            Đóng
          </Button>
          <Button type="button" disabled={ticked.size === 0 || pending} onClick={() => onConfirm([...ticked])}>
            Duyệt {ticked.size} luật
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
