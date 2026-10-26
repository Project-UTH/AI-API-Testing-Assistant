import { useRef, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { AlertTriangle, FileText, Loader2, Trash2, Upload } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { ApiError } from "@/lib/api"
import { deleteRequirementDoc, listRequirementDocs, MAX_DOC_BYTES, MAX_DOCS, uploadRequirementDoc } from "@/lib/requirementDocs"

export const DEFAULT_RULES_BUDGET = 60_000
export const MIN_RULES_BUDGET = 5_000
export const MAX_RULES_BUDGET = 1_000_000
export const MAX_INSTRUCTION = 4_000

/** Trạng thái nhập của khối "AI lập checklist nghiệp vụ" (do hộp thoại cha giữ để gửi khi bấm Bắt đầu). */
export interface ChecklistInput {
  excluded: Set<string>
  instruction: string
  suggest: boolean
  budget: string
  consent: boolean
  /**
   * Phân công tài liệu theo endpoint: null = mọi tài liệu đang tick dùng cho MỌI endpoint (mặc định); có giá trị = tài liệu -> các endpoint dùng nó
   * (tài liệu không có khoá = dùng cho mọi endpoint, nên tài liệu mới tải lên mặc định áp dụng cho tất cả endpoint đang chọn).
   */
  assign: Record<string, string[]> | null
}

export const EMPTY_CHECKLIST_INPUT: ChecklistInput = {
  excluded: new Set(),
  instruction: "",
  suggest: false,
  budget: String(DEFAULT_RULES_BUDGET),
  consent: false,
  assign: null,
}

export interface EndpointRef {
  id: string
  method: string
  path: string
}

/** Tài liệu nào dùng cho endpoint nào, theo phân công hiện tại (chỉ tính tài liệu đang tick). */
export function docsForEndpoint(input: ChecklistInput, docIds: string[], endpointId: string): string[] {
  return docIds.filter((docId) => !input.excluded.has(docId) && (input.assign === null || input.assign[docId] === undefined || input.assign[docId].includes(endpointId)))
}

/** endpointId -> tài liệu riêng của endpoint; undefined khi người dùng không tuỳ chỉnh (backend dùng chung documentIds). */
export function buildDocumentsByEndpoint(input: ChecklistInput, docIds: string[], endpoints: EndpointRef[]): Record<string, string[]> | undefined {
  if (input.assign === null) return undefined
  return Object.fromEntries(endpoints.map((e) => [e.id, docsForEndpoint(input, docIds, e.id)]))
}

export function budgetOf(input: ChecklistInput): number {
  return Math.min(MAX_RULES_BUDGET, Math.max(MIN_RULES_BUDGET, Math.floor(Number(input.budget)) || DEFAULT_RULES_BUDGET))
}

function errorText(error: unknown): string {
  return error instanceof ApiError ? error.message : "Đã xảy ra lỗi, vui lòng thử lại"
}

interface Props {
  projectId: string
  value: ChecklistInput
  onChange: (next: ChecklistInput) => void
  /** Các endpoint đang được sinh test: để chọn tài liệu riêng cho từng endpoint. */
  endpoints: EndpointRef[]
}

/**
 * Khối nhập cho "AI lập checklist nghiệp vụ": tài liệu (.md/.txt), chỉ dẫn thêm, nút "AI gợi ý thêm" và ngân sách token.
 * Không có tài liệu lẫn chỉ dẫn thì "AI gợi ý thêm" tự bật và bị khoá (AI chỉ còn cách gợi ý từ spec). AI chỉ ĐỀ XUẤT mục checklist; bạn duyệt rồi
 * hệ thống mới sinh test (tất định, 0 token).
 */
export function BusinessChecklistFields({ projectId, value, onChange, endpoints }: Props) {
  const queryClient = useQueryClient()
  const fileInput = useRef<HTMLInputElement | null>(null)
  const [localError, setLocalError] = useState<string | null>(null)
  const [openDoc, setOpenDoc] = useState<string | null>(null)
  const [filter, setFilter] = useState("")

  const { data: docs = [], isLoading } = useQuery({ queryKey: ["requirement-docs", projectId], queryFn: () => listRequirementDocs(projectId) })
  const selected = docs.filter((d) => !value.excluded.has(d.id))
  const docIds = docs.map((d) => d.id)
  const withoutDocs = endpoints.filter((e) => docsForEndpoint(value, docIds, e.id).length === 0)
  const hasInput = selected.length > 0 || value.instruction.trim().length > 0
  const effectiveSuggest = !hasInput || value.suggest
  const budget = budgetOf(value)

  const upload = useMutation({
    mutationFn: (file: File) => uploadRequirementDoc(projectId, file),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["requirement-docs", projectId] })
      queryClient.invalidateQueries({ queryKey: ["rules", projectId] }) // thay bản cũ có thể làm mục cũ thành lỗi thời
    },
  })
  const remove = useMutation({
    mutationFn: (id: string) => deleteRequirementDoc(projectId, id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["requirement-docs", projectId] }),
  })

  function onFiles(files: FileList | null) {
    upload.reset()
    remove.reset()
    const file = files?.[0]
    if (fileInput.current) fileInput.current.value = ""
    setLocalError(null)
    if (!file) return
    if (!/\.(md|txt)$/i.test(file.name)) return setLocalError("Chỉ nhận tệp .md hoặc .txt")
    if (file.size > MAX_DOC_BYTES) return setLocalError(`Tệp quá lớn (tối đa ${MAX_DOC_BYTES / 1000} KB)`)
    upload.mutate(file)
  }

  const error = localError ?? [upload, remove].map((m) => m.error).find(Boolean)

  return (
    <div className="mt-2 flex flex-col gap-3 rounded-md border border-border p-3">
      <p className="text-xs text-muted-foreground">
        AI đọc tài liệu và chỉ dẫn của bạn rồi lập <strong>checklist nghiệp vụ</strong> để bạn duyệt. Chưa duyệt thì chưa có test nào. Mục có trích dẫn
        nguyên văn được hệ thống kiểm lại từng chữ.
      </p>

      <div className="flex flex-wrap items-center gap-2">
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
          {docs.length}/{MAX_DOCS} · tối đa {MAX_DOC_BYTES / 1000} KB mỗi tệp · lưu vào project ngay khi tải; tải trùng tên sẽ thay bản cũ
        </span>
      </div>

      {isLoading && <p className="text-xs text-muted-foreground">Đang tải danh sách tài liệu...</p>}
      {docs.length > 0 && (
        <ul className="flex flex-col gap-1.5">
          {docs.map((d) => (
            <li key={d.id} className="rounded-md border border-border px-2.5 py-1.5 text-xs">
              <div className="flex items-center gap-2">
                <Checkbox
                  id={`doc-${d.id}`}
                  checked={!value.excluded.has(d.id)}
                  onCheckedChange={(checked) => {
                    const next = new Set(value.excluded)
                    if (checked) next.delete(d.id)
                    else next.add(d.id)
                    onChange({ ...value, excluded: next })
                  }}
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
                  onClick={() => window.confirm(`Xoá tài liệu "${d.fileName}"? Mục checklist đã trích vẫn giữ đoạn trích của chúng.`) && remove.mutate(d.id)}
                >
                  <Trash2 className="size-3.5" />
                </Button>
              </div>
              {d.warnings.length > 0 && (
                <p className="mt-1 flex items-start gap-1 pl-6 text-amber-700 dark:text-amber-400">
                  <AlertTriangle className="mt-0.5 size-3 shrink-0" aria-hidden />
                  Có {d.warnings.length} chuỗi giống mật khẩu/khoá (dòng {d.warnings.map((w) => w.line).join(", ")}). Chúng được che khi gửi AI, nhưng nên xoá
                  khỏi tài liệu.
                </p>
              )}
            </li>
          ))}
        </ul>
      )}

      {selected.length > 0 && endpoints.length > 0 && (
        <div className="flex flex-col gap-2 rounded-md border border-border p-2.5">
          <Label className="items-start gap-2 text-xs font-normal">
            <Checkbox
              checked={value.assign !== null}
              onCheckedChange={(c) => onChange({ ...value, assign: c === true ? {} : null })}
              className="mt-0.5"
            />
            <span>
              <span className="font-medium">Chọn tài liệu riêng cho từng endpoint</span>
              <span className="block text-muted-foreground">
                Mặc định mọi tài liệu đã tick dùng cho cả {endpoints.length} endpoint. Bật để mỗi tài liệu chỉ dùng cho các endpoint bạn chọn (tài liệu mới tải lên mặc định áp dụng cho tất cả).
              </span>
            </span>
          </Label>
          {value.assign !== null && (
            <ul className="flex flex-col gap-1.5">
              {selected.map((d) => {
                const chosen = value.assign?.[d.id] ?? endpoints.map((e) => e.id)
                const open = openDoc === d.id
                const shown = endpoints.filter((e) => `${e.method} ${e.path}`.toLowerCase().includes(filter.trim().toLowerCase()))
                const setChosen = (ids: string[]) => onChange({ ...value, assign: { ...(value.assign ?? {}), [d.id]: ids } })
                return (
                  <li key={d.id} className="rounded-md border border-border px-2.5 py-1.5 text-xs">
                    <div className="flex flex-wrap items-center gap-2">
                      <FileText className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
                      <span className="min-w-0 flex-1 truncate font-medium">{d.fileName}</span>
                      <span className="text-muted-foreground">
                        Áp dụng cho {chosen.length}/{endpoints.length} endpoint
                      </span>
                      <Button type="button" size="xs" variant="outline" aria-expanded={open} onClick={() => { setOpenDoc(open ? null : d.id); setFilter("") }}>
                        {open ? "Thu gọn" : "Chọn endpoint…"}
                      </Button>
                    </div>
                    {open && (
                      <div className="mt-2 flex flex-col gap-1.5">
                        <div className="flex flex-wrap items-center gap-2">
                          <input
                            aria-label="Lọc endpoint"
                            placeholder="Lọc endpoint…"
                            value={filter}
                            onChange={(e) => setFilter(e.target.value)}
                            className="h-7 w-48 rounded-md border border-input bg-transparent px-2 text-xs"
                          />
                          <Button type="button" size="xs" variant="ghost" onClick={() => setChosen([...new Set([...chosen, ...shown.map((e) => e.id)])])}>
                            Chọn các endpoint đang hiện
                          </Button>
                          <Button type="button" size="xs" variant="ghost" onClick={() => setChosen(chosen.filter((id) => !shown.some((e) => e.id === id)))}>
                            Bỏ các endpoint đang hiện
                          </Button>
                        </div>
                        <ul className="max-h-40 overflow-y-auto rounded-md border border-border p-1.5">
                          {shown.map((e) => (
                            <li key={e.id}>
                              <Label className="items-center gap-2 py-0.5 text-xs font-normal">
                                <Checkbox
                                  checked={chosen.includes(e.id)}
                                  onCheckedChange={(c) => setChosen(c === true ? [...chosen, e.id] : chosen.filter((id) => id !== e.id))}
                                />
                                <span className="font-mono">
                                  {e.method} {e.path}
                                </span>
                              </Label>
                            </li>
                          ))}
                          {shown.length === 0 && <li className="text-muted-foreground">Không có endpoint nào khớp.</li>}
                        </ul>
                      </div>
                    )}
                  </li>
                )
              })}
            </ul>
          )}
          {value.assign !== null && withoutDocs.length > 0 && (
            <p className="rounded-md border border-amber-500/30 bg-amber-500/5 p-2 text-xs">
              {withoutDocs.length} endpoint không được gán tài liệu nào
              {value.instruction.trim() ? " (chỉ dùng chỉ dẫn của bạn)" : value.suggest ? ": chỉ chạy làn AI gợi ý (mục GIẢ ĐỊNH)" : ": sẽ không có mục nào trừ khi bật AI gợi ý thêm"}.
            </p>
          )}
        </div>
      )}

      <div className="flex flex-col gap-1">
        <Label htmlFor="rule-instruction" className="text-xs">
          Chỉ dẫn thêm cho AI (tuỳ chọn)
        </Label>
        <Textarea
          id="rule-instruction"
          rows={3}
          maxLength={MAX_INSTRUCTION}
          value={value.instruction}
          onChange={(e) => onChange({ ...value, instruction: e.target.value })}
          placeholder='Ví dụ: "Chú ý các quy định về ngày hết hạn và lương". Có thể dán trực tiếp đoạn quy định nghiệp vụ; nếu AI hỏi lại chỗ tài liệu thiếu, trả lời vào đây rồi chạy lại.'
        />
        <span className="self-end text-[11px] text-muted-foreground">
          {value.instruction.length}/{MAX_INSTRUCTION}
        </span>
      </div>

      <Label className="items-start gap-2 font-normal" title="AI nêu thêm các mục nghiệp vụ nó nghĩ API nên có nhưng tài liệu không nói. Là GIẢ ĐỊNH: bạn quyết định giữ hay loại.">
        <Checkbox
          checked={effectiveSuggest}
          disabled={!hasInput}
          onCheckedChange={(c) => onChange({ ...value, suggest: c === true })}
          className="mt-0.5"
          aria-describedby="suggest-hint"
        />
        <span className="text-xs">
          <strong>AI gợi ý thêm</strong> (ngoài tài liệu · nhãn GIẢ ĐỊNH, không có trích dẫn)
          <span id="suggest-hint" className="block text-muted-foreground">
            {hasInput
              ? "Tắt thì AI chỉ lập mục dựa trên tài liệu và chỉ dẫn của bạn."
              : "Chưa có tài liệu hay chỉ dẫn nên AI chỉ có thể gợi ý từ spec: mọi mục sẽ là GIẢ ĐỊNH để bạn duyệt."}
          </span>
        </span>
      </Label>

      <div className="flex flex-wrap items-end gap-x-6 gap-y-2">
        <div className="flex flex-col gap-1">
          <Label htmlFor="rule-budget" className="text-xs">
            Ngân sách token cho phần checklist (trần cứng)
          </Label>
          <input
            id="rule-budget"
            type="number"
            min={MIN_RULES_BUDGET}
            max={MAX_RULES_BUDGET}
            step={5000}
            value={value.budget}
            onChange={(e) => onChange({ ...value, budget: e.target.value })}
            className="h-8 w-36 rounded-md border border-input bg-transparent px-2 text-sm"
          />
          {String(budget) !== value.budget && (
            <span className="text-[11px] text-amber-700 dark:text-amber-400">Sẽ dùng {budget.toLocaleString("vi-VN")} (trong khoảng 5.000 đến 1.000.000)</span>
          )}
        </div>
        <p className="max-w-sm text-xs text-muted-foreground">
          Không giới hạn số mục: <strong>ngân sách này là giới hạn duy nhất</strong>. Hết thì dừng và giữ phần đã làm. Tính riêng, không lấy từ phần sinh test gốc.
        </p>
      </div>

      <Label className="items-start gap-2 font-normal">
        <Checkbox checked={value.consent} onCheckedChange={(v) => onChange({ ...value, consent: v === true })} className="mt-0.5" />
        <span className="text-xs">Tôi đồng ý gửi tài liệu và chỉ dẫn tới dịch vụ AI bên ngoài (chuỗi giống mật khẩu/khoá đã được che).</span>
      </Label>

      {error && (
        <p role="alert" className="text-xs text-destructive">
          {typeof error === "string" ? error : errorText(error)}
        </p>
      )}
    </div>
  )
}

/** Ước tính token của phần checklist: lô 12 endpoint, mỗi lô đọc cả tài liệu (tối đa 40.000 ký tự) + tối đa 4.000 token trả lời; làn gợi ý thêm 1 lệnh gọi mỗi lô. */
export function estimateChecklistTokens(endpointCount: number, docChars: number, suggest: boolean, hasInput: boolean): number {
  const lots = Math.max(1, Math.ceil(endpointCount / 12))
  const perDocLot = hasInput ? Math.ceil((Math.min(40_000, docChars) + 6_000) * 0.4) + 4_000 : 0
  const perSuggestLot = suggest ? 3_000 + 4_000 : 0
  return lots * (perDocLot + perSuggestLot)
}
