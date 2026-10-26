import { useEffect, useState } from "react"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Label } from "@/components/ui/label"
import { expectedLabel, type BusinessRule } from "@/lib/rules"

interface Props {
  open: boolean
  /** Mục có trích dẫn, đang chờ duyệt (đã lọc ở trang). */
  rules: BusinessRule[]
  /** Mục GIẢ ĐỊNH (AI gợi ý, không trích dẫn) đang chờ duyệt: duyệt kèm mã lỗi bạn xác nhận riêng cho từng mục. */
  guessRules: BusinessRule[]
  pending: boolean
  error?: string | null
  onClose: () => void
  onConfirm: (ruleIds: string[], expectedStatuses: Record<string, number>) => void
}

/**
 * Duyệt hàng loạt CHỈ mục có trích dẫn: mỗi mục hiện đủ câu mục + đoạn trích để bạn đối chiếu, và mặc định KHÔNG tick sẵn - duyệt phải là hành
 * động có chủ đích, không phải bấm một nút cho xong.
 */
export function BatchApproveDialog({ open, rules, guessRules, pending, error, onClose, onConfirm }: Props) {
  const [ticked, setTicked] = useState<Set<string>>(new Set())
  // Mã lỗi cho mục GIẢ ĐỊNH: điền sẵn mã AI gợi ý, bạn đổi được; không tick sẵn và không có mã hợp lệ thì không được duyệt.
  const [tickedGuess, setTickedGuess] = useState<Set<string>>(new Set())
  const [codes, setCodes] = useState<Record<string, string>>({})
  useEffect(() => {
    if (open) {
      setTicked(new Set())
      setTickedGuess(new Set())
      setCodes({})
    }
  }, [open])
  const codeOf = (r: BusinessRule) => Number(codes[r.id] ?? (r.suggestedStatus ?? ""))
  const validGuess = guessRules.filter((r) => tickedGuess.has(r.id) && Number.isInteger(codeOf(r)) && codeOf(r) >= 400 && codeOf(r) <= 499)

  // Chỉ tính các mục còn trong danh sách (danh sách có thể đổi khi hộp thoại đang mở).
  const valid = rules.filter((r) => ticked.has(r.id))
  const all = rules.length > 0 && valid.length === rules.length
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
          <DialogTitle>Duyệt hàng loạt</DialogTitle>
          <DialogDescription>
            Đối chiếu từng mục với đoạn trích rồi tick những mục đúng. Mục GIẢ ĐỊNH chỉ duyệt được khi bạn tự tick và xác nhận mã lỗi cho đúng mục đó. Mục mâu thuẫn spec phải duyệt riêng từng mục.
          </DialogDescription>
        </DialogHeader>
        <Label className="items-center gap-2 text-sm font-normal">
          <Checkbox checked={all} indeterminate={valid.length > 0 && !all} onCheckedChange={(v) => setTicked(v ? new Set(rules.map((r) => r.id)) : new Set())} />
          Chọn tất cả sau khi đã đọc ({valid.length}/{rules.length})
        </Label>
        {rules.length === 0 && <p className="text-xs text-muted-foreground">Không có mục có trích dẫn nào đang chờ duyệt.</p>}
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
        {guessRules.length > 0 && (
          <section aria-label="Mục GIẢ ĐỊNH" className="flex flex-col gap-2">
            <h4 className="text-sm font-medium">Mục GIẢ ĐỊNH do AI gợi ý ({validGuess.length}/{guessRules.length} đã chọn)</h4>
            <p className="text-xs text-muted-foreground">
              Không có trích dẫn nào xác nhận. Tick từng mục bạn thấy đúng và kiểm tra mã lỗi: mã AI gợi ý chỉ được điền sẵn, API của bạn có thể trả mã khác.
            </p>
            <ul className="flex max-h-[30vh] flex-col gap-2 overflow-y-auto">
              {guessRules.map((rule) => (
                <li key={rule.id} className="flex items-start gap-2 rounded-md border border-dashed border-amber-600/50 p-2.5 text-sm">
                  <Checkbox
                    className="mt-0.5"
                    aria-label={`Chọn ${rule.sentence}`}
                    checked={tickedGuess.has(rule.id)}
                    onCheckedChange={(v) =>
                      setTickedGuess((prev) => {
                        const next = new Set(prev)
                        if (v === true) next.add(rule.id)
                        else next.delete(rule.id)
                        return next
                      })
                    }
                  />
                  <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                    <span className="font-medium">{rule.sentence}</span>
                    <span className="font-mono text-xs text-muted-foreground">{rule.endpointKey}</span>
                    {rule.rationale && <span className="text-xs text-muted-foreground">{rule.rationale}</span>}
                  </span>
                  <span className="flex shrink-0 flex-col items-end gap-0.5">
                    <input
                      aria-label={`Mã lỗi kỳ vọng cho ${rule.sentence}`}
                      inputMode="numeric"
                      value={codes[rule.id] ?? (rule.suggestedStatus ?? "")}
                      onChange={(e) => setCodes((prev) => ({ ...prev, [rule.id]: e.target.value.replace(/\D/g, "").slice(0, 3) }))}
                      className="h-8 w-20 rounded-md border border-input bg-transparent px-2 text-sm"
                    />
                    {rule.suggestedStatus !== null && <span className="text-[11px] text-muted-foreground">AI gợi ý {rule.suggestedStatus}</span>}
                  </span>
                </li>
              ))}
            </ul>
          </section>
        )}
        {error && (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        )}
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>
            Đóng
          </Button>
          <Button
            type="button"
            disabled={valid.length + validGuess.length === 0 || pending}
            onClick={() => onConfirm([...valid.map((r) => r.id), ...validGuess.map((r) => r.id)], Object.fromEntries(validGuess.map((r) => [r.id, codeOf(r)])))}
          >
            Duyệt {valid.length + validGuess.length} mục
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
