import { useMemo, useRef, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { AlertTriangle, FileText, Loader2, Sparkles, Trash2, Upload } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { useGenerationJobs } from "@/components/generation/GenerationJobsProvider"
import { ApiError } from "@/lib/api"
import { startJob } from "@/lib/generationJobs"
import {
  deleteRequirementDoc,
  estimateTokens,
  listRequirementDocs,
  MAX_DOC_BYTES,
  MAX_DOCS,
  uploadRequirementDoc,
} from "@/lib/requirementDocs"

const DEFAULT_BUDGET = 60_000
const MIN_BUDGET = 5_000
const MAX_BUDGET = 1_000_000
const MAX_INSTRUCTION = 4_000

function errorText(error: unknown): string {
  return error instanceof ApiError ? error.message : "Đã xảy ra lỗi, vui lòng thử lại"
}

/**
 * 17q G3.3 - nạp tài liệu nghiệp vụ (.md/.txt) và/hoặc chỉ dẫn, rồi cho AI TRÍCH luật. AI chỉ đề xuất: mỗi luật phải có đoạn trích nguyên văn
 * (kiểm tất định ở backend), luật nằm ở "Chờ duyệt" và không sinh test cho tới khi bạn duyệt. Ngân sách token là trần cứng do bạn đặt.
 */
export function ExtractRulesPanel({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient()
  const { track } = useGenerationJobs()
  const fileInput = useRef<HTMLInputElement | null>(null)
  const [excluded, setExcluded] = useState<Set<string>>(new Set())
  const [instruction, setInstruction] = useState("")
  const [budget, setBudget] = useState(String(DEFAULT_BUDGET))
  const [allowInferred, setAllowInferred] = useState(false)
  const [consent, setConsent] = useState(false)
  const [localError, setLocalError] = useState<string | null>(null)

  const { data: docs = [], isLoading } = useQuery({
    queryKey: ["requirement-docs", projectId],
    queryFn: () => listRequirementDocs(projectId),
  })
  const selected = useMemo(() => docs.filter((d) => !excluded.has(d.id)), [docs, excluded])

  const upload = useMutation({
    mutationFn: (file: File) => uploadRequirementDoc(projectId, file),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["requirement-docs", projectId] })
      queryClient.invalidateQueries({ queryKey: ["rules", projectId] }) // thay bản cũ có thể làm luật cũ thành lỗi thời
    },
  })
  const remove = useMutation({
    mutationFn: (id: string) => deleteRequirementDoc(projectId, id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["requirement-docs", projectId] }),
  })
  const start = useMutation({
    mutationFn: (dryRun: boolean) =>
      startJob(projectId, {
        type: "RULES",
        dryRun,
        documentIds: selected.map((d) => d.id),
        instruction: instruction.trim() || undefined,
        allowInferred,
        tokenCap: budgetValue,
      }),
    onSuccess: (job) => track(job),
  })

  const budgetValue = Math.min(MAX_BUDGET, Math.max(MIN_BUDGET, Math.floor(Number(budget)) || DEFAULT_BUDGET))
  const chars = selected.reduce((sum, d) => sum + d.sizeBytes, 0) + instruction.length
  const perLotEstimate = estimateTokens(Math.min(40_000, chars) + 6_000) + 4_000
  const hasInput = selected.length > 0 || instruction.trim().length > 0

  function onFiles(files: FileList | null) {
    const file = files?.[0]
    if (fileInput.current) fileInput.current.value = ""
    setLocalError(null)
    if (!file) return
    if (!/\.(md|txt)$/i.test(file.name)) return setLocalError("Chỉ nhận tệp .md hoặc .txt")
    if (file.size > MAX_DOC_BYTES) return setLocalError(`Tệp quá lớn (tối đa ${MAX_DOC_BYTES / 1000} KB)`)
    upload.mutate(file)
  }

  const error = localError ?? [upload, remove, start].map((m) => m.error).find(Boolean)

  return (
    <section aria-label="Trích luật bằng AI" className="mt-5 rounded-lg border border-border p-4">
      <h3 className="text-sm font-semibold">Để AI trích luật từ tài liệu nghiệp vụ</h3>
      <p className="mt-1 text-xs text-muted-foreground">
        AI chỉ <em>đề xuất</em>: mỗi luật phải kèm đoạn trích nguyên văn từ tài liệu hoặc chỉ dẫn của bạn (hệ thống kiểm lại từng chữ). Luật không có
        trích dẫn bị gắn nhãn GIẢ ĐỊNH. Mọi luật nằm ở "Chờ duyệt" cho tới khi bạn duyệt.
      </p>

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <input ref={fileInput} type="file" accept=".md,.txt,text/markdown,text/plain" className="sr-only" id="req-doc-file" onChange={(e) => onFiles(e.target.files)} />
        <Button
          type="button"
          size="sm"
          variant="outline"
          disabled={upload.isPending || docs.length >= MAX_DOCS}
          title={docs.length >= MAX_DOCS ? `Tối đa ${MAX_DOCS} tài liệu - xoá bớt hoặc tải trùng tên để thay bản cũ` : undefined}
          onClick={() => fileInput.current?.click()}
        >
          {upload.isPending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Upload className="h-3.5 w-3.5" />}
          Tải tài liệu (.md / .txt)
        </Button>
        <span className="text-xs text-muted-foreground">
          {docs.length}/{MAX_DOCS} tài liệu · mỗi tệp tối đa {MAX_DOC_BYTES / 1000} KB · tải trùng tên sẽ thay bản cũ (luật từ bản cũ thành "Lỗi thời")
        </span>
      </div>

      {isLoading && <p className="mt-2 text-xs text-muted-foreground">Đang tải danh sách tài liệu...</p>}
      {docs.length > 0 && (
        <ul className="mt-3 flex flex-col gap-1.5">
          {docs.map((d) => (
            <li key={d.id} className="rounded-md border border-border px-2.5 py-1.5 text-xs">
              <div className="flex items-center gap-2">
                <Checkbox
                  id={`doc-${d.id}`}
                  checked={!excluded.has(d.id)}
                  onCheckedChange={(checked) =>
                    setExcluded((prev) => {
                      const next = new Set(prev)
                      if (checked) next.delete(d.id)
                      else next.add(d.id)
                      return next
                    })
                  }
                />
                <Label htmlFor={`doc-${d.id}`} className="flex min-w-0 flex-1 items-center gap-1.5 text-xs font-normal">
                  <FileText className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
                  <span className="truncate font-medium">{d.fileName}</span>
                  <span className="shrink-0 text-muted-foreground">
                    {(d.sizeBytes / 1000).toFixed(1)} KB · {d.lineCount} dòng
                  </span>
                </Label>
                <Button
                  type="button"
                  size="icon-sm"
                  variant="ghost"
                  aria-label={`Xoá tài liệu ${d.fileName}`}
                  disabled={remove.isPending}
                  onClick={() => window.confirm(`Xoá tài liệu "${d.fileName}"? Luật đã trích vẫn giữ đoạn trích của chúng.`) && remove.mutate(d.id)}
                >
                  <Trash2 className="size-3.5" />
                </Button>
              </div>
              {d.warnings.length > 0 && (
                <p className="mt-1 flex items-start gap-1 pl-6 text-amber-700 dark:text-amber-400">
                  <AlertTriangle className="mt-0.5 size-3 shrink-0" aria-hidden />
                  Có {d.warnings.length} chuỗi giống mật khẩu/khoá (dòng {d.warnings.map((w) => w.line).join(", ")}). Chúng sẽ được che khi gửi AI, nhưng
                  nên xoá khỏi tài liệu.
                </p>
              )}
            </li>
          ))}
        </ul>
      )}

      <div className="mt-3 flex flex-col gap-1">
        <Label htmlFor="rule-instruction" className="text-xs">
          Chỉ dẫn thêm cho AI (tuỳ chọn) — ví dụ: "Chú ý các luật về ngày hết hạn và lương"
        </Label>
        <Textarea
          id="rule-instruction"
          rows={3}
          maxLength={MAX_INSTRUCTION}
          value={instruction}
          onChange={(e) => setInstruction(e.target.value)}
          placeholder="Có thể dán trực tiếp một đoạn quy định nghiệp vụ vào đây (nguồn trích dẫn: 'Từ chỉ dẫn')"
        />
        <span className="self-end text-[11px] text-muted-foreground">
          {instruction.length}/{MAX_INSTRUCTION}
        </span>
      </div>

      <div className="mt-2 flex flex-wrap items-end gap-x-6 gap-y-3">
        <div className="flex flex-col gap-1">
          <Label htmlFor="rule-budget" className="text-xs">
            Ngân sách token (trần cứng)
          </Label>
          <input
            id="rule-budget"
            type="number"
            min={MIN_BUDGET}
            max={MAX_BUDGET}
            step={5000}
            value={budget}
            onChange={(e) => setBudget(e.target.value)}
            className="h-8 w-36 rounded-md border border-input bg-transparent px-2 text-sm"
          />
        </div>
        <p className="max-w-md text-xs text-muted-foreground">
          Ước tính khoảng <strong>{perLotEstimate.toLocaleString("vi-VN")}</strong> token cho mỗi lô 12 endpoint. Hết ngân sách hoặc hết quota ngày thì
          dừng và giữ phần đã làm. "Chạy thử" cho biết số lô và ước tính chính xác hơn, 0 token.
        </p>
      </div>

      <div className="mt-3 flex flex-col gap-2 text-xs">
        <Label className="items-start gap-2 font-normal">
          <Checkbox checked={allowInferred} onCheckedChange={(v) => setAllowInferred(v === true)} className="mt-0.5" />
          <span>Cho phép AI đề xuất cả luật <strong>không có trích dẫn</strong> (nhãn GIẢ ĐỊNH, tối đa 3 luật mỗi endpoint, bạn phải tự chọn mã lỗi khi duyệt)</span>
        </Label>
        <Label className="items-start gap-2 font-normal">
          <Checkbox checked={consent} onCheckedChange={(v) => setConsent(v === true)} className="mt-0.5" />
          <span>Tôi hiểu nội dung tài liệu và chỉ dẫn sẽ được gửi tới dịch vụ AI bên ngoài (chuỗi giống mật khẩu/khoá đã được che).</span>
        </Label>
      </div>

      {error && (
        <p role="alert" className="mt-3 text-sm text-destructive">
          {typeof error === "string" ? error : errorText(error)}
        </p>
      )}

      <div className="mt-3 flex flex-wrap gap-2">
        <Button type="button" size="sm" disabled={!hasInput || !consent || start.isPending} onClick={() => start.mutate(false)}>
          {start.isPending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Sparkles className="h-3.5 w-3.5" />}
          AI trích luật
        </Button>
        <Button type="button" size="sm" variant="outline" disabled={!hasInput || start.isPending} onClick={() => start.mutate(true)}>
          Chạy thử (0 token)
        </Button>
        {!hasInput && <span className="self-center text-xs text-muted-foreground">Chọn ít nhất 1 tài liệu hoặc nhập chỉ dẫn.</span>}
      </div>
    </section>
  )
}
