import { useEffect, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { RuleParamsFields } from "@/components/rules/RuleParamsFields"
import { ApiError } from "@/lib/api"
import { reportReviewTiming } from "@/lib/reviewTiming"
import { listEndpoints } from "@/lib/endpoints"
import {
  createBusinessRule,
  getEndpointFields,
  RULE_TEMPLATE_DISPLAY,
  templateFit,
  RULE_TEMPLATE_ORDER,
  ruleSentence,
  updateBusinessRule,
  type BusinessRule,
  type RuleParams,
  type RuleTemplate,
} from "@/lib/rules"

interface BusinessRuleFormDialogProps {
  projectId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Có = sửa mục này (không đổi endpoint/mẫu); sửa xong mục quay về "Chờ duyệt". */
  rule?: BusinessRule | null
  defaultEndpointId?: string
}

const SELECT_CLASS = "h-8 w-full min-w-0 rounded-lg border border-input bg-transparent px-2.5 text-sm outline-none dark:bg-input/30"

/** Thêm/sửa checklist nghiệp vụ: chọn endpoint → mẫu → tham số (field chọn từ spec) → kỳ vọng; có câu mục xem trước. */
export function BusinessRuleFormDialog({ projectId, open, onOpenChange, rule, defaultEndpointId }: BusinessRuleFormDialogProps) {
  const queryClient = useQueryClient()
  const editing = Boolean(rule)
  const [endpointId, setEndpointId] = useState("")
  const [template, setTemplate] = useState<RuleTemplate>("COMPARE_FIELDS")
  const [params, setParams] = useState<RuleParams>({})
  const [rejectedAny, setRejectedAny] = useState(true)
  const [expectedStatus, setExpectedStatus] = useState("422")
  const [rationale, setRationale] = useState("")

  const { data: endpoints } = useQuery({
    queryKey: ["endpoints", projectId, "all-for-rules"],
    queryFn: () => listEndpoints(projectId),
    enabled: open,
  })
  const { data: fields = [] } = useQuery({
    queryKey: ["rule-fields", projectId, endpointId],
    queryFn: () => getEndpointFields(projectId, endpointId),
    enabled: open && Boolean(endpointId),
  })

  useEffect(() => {
    if (!open) return
    if (rule) {
      setEndpointId(rule.endpointId ?? "")
      setTemplate(rule.template)
      setParams(rule.params)
      setRejectedAny(rule.expectedStatus == null)
      setExpectedStatus(rule.expectedStatus == null ? "422" : String(rule.expectedStatus))
      setRationale(rule.rationale ?? "")
    } else {
      setEndpointId(defaultEndpointId ?? "")
      setTemplate("COMPARE_FIELDS")
      setParams({})
      setRejectedAny(true)
      setExpectedStatus("422")
      setRationale("")
    }
  }, [open, rule, defaultEndpointId])

  const mutation = useMutation({
    mutationFn: (approve: boolean) => {
      const expected = rejectedAny ? null : Number(expectedStatus)
      if (rule) {
        return updateBusinessRule(projectId, rule.id, { params, expectedStatus: expected, rationale: rationale || undefined })
      }
      return createBusinessRule(projectId, { endpointId, template, params, expectedStatus: expected, rationale: rationale || undefined, approve })
    },
    onSuccess: () => {
      if (rule) reportReviewTiming(projectId, rule.id, "EDITED") // chỉ khi SỬA mục có sẵn (tạo mới không phải một lần duyệt)
      queryClient.invalidateQueries({ queryKey: ["rules", projectId] })
      queryClient.invalidateQueries({ queryKey: ["project-coverage", projectId] })
      onOpenChange(false)
    },
  })

  const canSubmit = Boolean(endpointId) && (rejectedAny || (Number(expectedStatus) >= 400 && Number(expectedStatus) <= 499))
  const error = mutation.error instanceof ApiError ? mutation.error.message : mutation.isError ? "Đã xảy ra lỗi, vui lòng thử lại" : null

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{editing ? "Sửa checklist nghiệp vụ" : "Thêm checklist nghiệp vụ"}</DialogTitle>
          <DialogDescription>
            Bạn chịu trách nhiệm kỳ vọng của mục; hệ thống sinh test từ mục đã duyệt (không dùng AI). Sửa mục sẽ đưa nó về "Chờ duyệt".
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-1">
            <Label htmlFor="rule-endpoint" className="text-xs">
              Endpoint
            </Label>
            <select
              id="rule-endpoint"
              className={SELECT_CLASS}
              value={endpointId}
              disabled={editing}
              onChange={(e) => {
                setEndpointId(e.target.value)
                setParams({})
              }}
            >
              <option value="">Chọn endpoint…</option>
              {(endpoints?.data ?? []).map((ep) => (
                <option key={ep.id} value={ep.id}>
                  {ep.method} {ep.path}
                </option>
              ))}
            </select>
          </div>

          <fieldset className="flex flex-col gap-1.5" disabled={editing}>
            <legend className="text-xs font-medium">Mẫu mục</legend>
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              {RULE_TEMPLATE_ORDER.map((t) => {
                // Có endpoint + field: ví dụ ghép từ field thật của endpoint; mẫu không có field phù hợp bị mờ và không chọn được (trừ khi đang sửa mục có sẵn).
                const fit = endpointId && fields.length > 0 ? templateFit(t, fields) : null
                const blocked = !!fit && !fit.usable && !editing && template !== t
                return (
                <label
                  key={t}
                  title={blocked ? `Endpoint này không dùng được mẫu này: ${fit?.reason}` : undefined}
                  className={`flex flex-col rounded-md border p-2 text-sm ${blocked ? "cursor-not-allowed opacity-50" : "cursor-pointer"} ${template === t ? "border-primary bg-primary/5" : "border-border"}`}
                >
                  <span className="flex items-center gap-2 font-medium">
                    <input
                      type="radio"
                      name="rule-template"
                      disabled={blocked}
                      checked={template === t}
                      onChange={() => {
                        setTemplate(t)
                        setParams({})
                      }}
                    />
                    {RULE_TEMPLATE_DISPLAY[t].label}
                  </span>
                  <span className="pl-6 text-xs text-muted-foreground">
                    {fit ? (fit.usable ? `Ví dụ từ endpoint này: ${fit.example}` : `Không dùng được: ${fit.reason}`) : RULE_TEMPLATE_DISPLAY[t].example}
                  </span>
                </label>
                )
              })}
            </div>
          </fieldset>

          {endpointId ? (
            <RuleParamsFields template={template} params={params} fields={fields} onChange={setParams} />
          ) : (
            <p className="text-sm text-muted-foreground">Chọn endpoint để chọn field của mục.</p>
          )}

          <fieldset className="flex flex-col gap-1.5">
            <legend className="text-xs font-medium">Khi vi phạm mục, API phải trả</legend>
            <label className="flex items-center gap-2 text-sm">
              <input type="radio" name="rule-expected" checked={rejectedAny} onChange={() => setRejectedAny(true)} />
              Bị từ chối (mã 4xx bất kỳ, trừ 401/403/404/405/415/429)
            </label>
            <label className="flex items-center gap-2 text-sm">
              <input type="radio" name="rule-expected" checked={!rejectedAny} onChange={() => setRejectedAny(false)} />
              Mã cụ thể
              <Input
                aria-label="Mã trạng thái kỳ vọng"
                type="number"
                className="h-8 w-24"
                value={expectedStatus}
                disabled={rejectedAny}
                onChange={(e) => setExpectedStatus(e.target.value)}
              />
            </label>
          </fieldset>

          <div className="flex flex-col gap-1">
            <Label htmlFor="rule-rationale" className="text-xs">
              Ghi chú / nguồn của mục (tuỳ chọn)
            </Label>
            <Textarea id="rule-rationale" rows={2} value={rationale} onChange={(e) => setRationale(e.target.value)} />
          </div>

          <div className="rounded-md bg-muted p-3 text-sm" aria-live="polite">
            <span className="text-xs text-muted-foreground">Xem trước: </span>
            <code>{ruleSentence(template, params)}</code>
            <span className="text-muted-foreground">
              {" "}
              → vi phạm thì {rejectedAny ? "bị từ chối (4xx)" : `trả ${expectedStatus}`}
            </span>
          </div>

          {error && (
            <p role="alert" className="text-sm text-destructive">
              {error}
            </p>
          )}
        </div>

        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
            Huỷ
          </Button>
          <Button type="button" variant={editing ? "default" : "outline"} disabled={!canSubmit || mutation.isPending} onClick={() => mutation.mutate(false)}>
            {editing ? "Lưu (chờ duyệt lại)" : "Lưu - chờ duyệt"}
          </Button>
          {!editing && (
            <Button type="button" disabled={!canSubmit || mutation.isPending} onClick={() => mutation.mutate(true)}>
              Lưu và duyệt
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
