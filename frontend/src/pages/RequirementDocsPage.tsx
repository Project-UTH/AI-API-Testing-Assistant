import { useMemo, useRef, useState } from "react"
import { useParams } from "react-router-dom"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { AlertTriangle, FileText, Loader2, Trash2, Upload } from "lucide-react"

import { Button } from "@/components/ui/button"
import { ApiError } from "@/lib/api"
import {
  deleteRequirementDoc,
  getRequirementDocContent,
  getRequirementDocUsage,
  setRequirementDocEndpoints,
  listRequirementDocs,
  MAX_DOC_BYTES,
  MAX_DOCS,
  uploadRequirementDoc,
  type RequirementDocument,
  type RequirementDocUsage,
} from "@/lib/requirementDocs"
import { listEndpoints } from "@/lib/endpoints"
import { cn } from "@/lib/utils"

function errorText(error: unknown): string {
  return error instanceof ApiError ? error.message : "Đã xảy ra lỗi, vui lòng thử lại"
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleString("vi-VN", { dateStyle: "short", timeStyle: "short" })
}

/**
 * Tab "Tài liệu": nơi LƯU và ĐỌC các tài liệu nghiệp vụ đã gửi cho project, kèm lịch sử mỗi tài liệu đã được gửi cho AI để lập checklist của
 * những endpoint nào (và ngược lại, mỗi endpoint đã dùng tài liệu nào). Chọn tài liệu riêng cho từng endpoint làm ở hộp thoại "Sinh test case".
 */
export function RequirementDocsPage() {
  const { id } = useParams<{ id: string }>()
  const projectId = id!
  const queryClient = useQueryClient()
  const fileInput = useRef<HTMLInputElement | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [view, setView] = useState<"byDoc" | "byEndpoint">("byDoc")
  const [localError, setLocalError] = useState<string | null>(null)

  const { data: docs = [], isLoading } = useQuery({ queryKey: ["requirement-docs", projectId], queryFn: () => listRequirementDocs(projectId) })
  const { data: usage = [] } = useQuery({ queryKey: ["requirement-doc-usage", projectId], queryFn: () => getRequirementDocUsage(projectId) })
  const current = docs.find((d) => d.id === selectedId) ?? docs[0] ?? null

  const { data: content, isLoading: loadingContent } = useQuery({
    queryKey: ["requirement-doc-content", projectId, current?.id],
    queryFn: () => getRequirementDocContent(projectId, current!.id),
    enabled: current !== null,
    staleTime: 60_000,
  })

  const { data: endpointsPage } = useQuery({ queryKey: ["endpoints", projectId], queryFn: () => listEndpoints(projectId) })
  const endpointKeys = useMemo(() => (endpointsPage?.data ?? []).map((e) => `${e.method.toUpperCase()} ${e.path}`), [endpointsPage])
  const [pickerOpen, setPickerOpen] = useState(false)
  const [pickerFilter, setPickerFilter] = useState("")
  const saveEndpoints = useMutation({
    mutationFn: (input: { docId: string; keys: string[] }) => setRequirementDocEndpoints(projectId, input.docId, input.keys),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["requirement-docs", projectId] }),
  })

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ["requirement-docs", projectId] })
    queryClient.invalidateQueries({ queryKey: ["requirement-doc-usage", projectId] })
    queryClient.invalidateQueries({ queryKey: ["rules", projectId] }) // thay bản cũ có thể làm mục cũ thành lỗi thời
  }
  const upload = useMutation({ mutationFn: (file: File) => uploadRequirementDoc(projectId, file), onSuccess: (doc) => { refresh(); setSelectedId(doc.id) } })
  const remove = useMutation({ mutationFn: (docId: string) => deleteRequirementDoc(projectId, docId), onSuccess: () => { refresh(); setSelectedId(null) } })

  const usageByDoc = useMemo(() => {
    const map = new Map<string, RequirementDocUsage[]>()
    for (const u of usage) map.set(u.documentId, [...(map.get(u.documentId) ?? []), u])
    return map
  }, [usage])
  const usageByEndpoint = useMemo(() => {
    const map = new Map<string, RequirementDocUsage[]>()
    for (const u of usage) map.set(u.endpointKey, [...(map.get(u.endpointKey) ?? []), u])
    return [...map.entries()].sort((a, b) => a[0].localeCompare(b[0]))
  }, [usage])

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

  function onDelete(doc: RequirementDocument) {
    const used = usageByDoc.get(doc.id)?.length ?? 0
    const warning = used > 0 ? ` Tài liệu đã được dùng cho ${used} endpoint; các mục checklist đã trích vẫn giữ đoạn trích của chúng.` : ""
    if (window.confirm(`Xoá tài liệu "${doc.fileName}"?${warning}`)) remove.mutate(doc.id)
  }

  const error = localError ?? [upload, remove].map((m) => m.error).find(Boolean)

  return (
    <div>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold">Tài liệu nghiệp vụ</h2>
          <p className="mt-1 max-w-2xl text-sm text-muted-foreground">
            Nơi lưu và đọc các tài liệu (.md / .txt) đã gửi cho project, kèm lịch sử mỗi tài liệu đã được dùng cho endpoint nào. Khi bấm "Sinh test case" và tick
            "AI bổ sung checklist nghiệp vụ", bạn chọn tài liệu có sẵn cho từng endpoint hoặc thêm tài liệu mới.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-xs text-muted-foreground">
            {docs.length}/{MAX_DOCS} tài liệu
          </span>
          <input ref={fileInput} type="file" accept=".md,.txt,text/markdown,text/plain" className="sr-only" id="docs-page-file" onChange={(e) => onFiles(e.target.files)} />
          <Button
            type="button"
            size="sm"
            disabled={upload.isPending || docs.length >= MAX_DOCS}
            title={docs.length >= MAX_DOCS ? `Tối đa ${MAX_DOCS} tài liệu - xoá bớt hoặc tải trùng tên để thay bản cũ` : "Tải trùng tên sẽ thay bản cũ"}
            onClick={() => fileInput.current?.click()}
          >
            {upload.isPending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Upload className="h-3.5 w-3.5" />}
            Tải tài liệu
          </Button>
        </div>
      </div>

      {error && (
        <p role="alert" className="mt-3 text-sm text-destructive">
          {typeof error === "string" ? error : errorText(error)}
        </p>
      )}
      {isLoading && <p className="mt-6 text-muted-foreground">Đang tải...</p>}

      {!isLoading && docs.length === 0 && (
        <div className="mt-6 rounded-md border border-dashed border-border p-6 text-sm text-muted-foreground">
          Chưa có tài liệu nào. Bấm "Tải tài liệu" để thêm file nghiệp vụ (SRS, quy định...). AI chỉ lập checklist từ tài liệu bạn gửi; mục có trích dẫn nguyên văn để bạn đối
          chiếu.
        </div>
      )}

      {docs.length > 0 && (
        <div className="mt-5 grid gap-4 lg:grid-cols-[20rem_1fr]">
          <ul className="flex flex-col gap-2" aria-label="Danh sách tài liệu">
            {docs.map((d) => {
              const used = usageByDoc.get(d.id) ?? []
              const active = current?.id === d.id
              return (
                <li key={d.id}>
                  <button
                    type="button"
                    onClick={() => setSelectedId(d.id)}
                    aria-current={active}
                    className={cn("w-full rounded-md border p-3 text-left text-sm transition-colors", active ? "border-primary bg-primary/5" : "border-border hover:bg-muted/40")}
                  >
                    <span className="flex items-center gap-1.5 font-medium">
                      <FileText className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
                      <span className="truncate">{d.fileName}</span>
                    </span>
                    <span className="mt-1 block text-xs text-muted-foreground">
                      {(d.sizeBytes / 1000).toFixed(1)} KB · {d.lineCount} dòng · tải {formatDate(d.createdAt)}
                    </span>
                    {d.forEndpoints.length > 0 && (
                      <span className="mt-1 block truncate text-xs text-muted-foreground" title={d.forEndpoints.join(", ")}>
                        Viết cho: {d.forEndpoints.length === 1 ? d.forEndpoints[0] : `${d.forEndpoints.length} endpoint`}
                      </span>
                    )}
                    <span className={cn("mt-1.5 inline-block rounded-full px-2 py-0.5 text-xs", used.length > 0 ? "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400" : "bg-muted text-muted-foreground")}>
                      {used.length > 0 ? `Đã dùng cho ${used.length} endpoint` : "Chưa dùng"}
                    </span>
                    {d.warnings.length > 0 && (
                      <span className="mt-1.5 flex items-start gap-1 text-xs text-amber-700 dark:text-amber-400">
                        <AlertTriangle className="mt-0.5 size-3 shrink-0" aria-hidden />
                        {d.warnings.length} chuỗi giống mật khẩu/khoá
                      </span>
                    )}
                  </button>
                </li>
              )
            })}
          </ul>

          <section aria-label="Nội dung tài liệu" className="min-w-0 rounded-md border border-border">
            {current && (
              <>
                <header className="flex flex-wrap items-center justify-between gap-2 border-b border-border px-3 py-2">
                  <h3 className="min-w-0 truncate text-sm font-medium">{current.fileName}</h3>
                  <Button type="button" size="xs" variant="ghost" disabled={remove.isPending} onClick={() => onDelete(current)}>
                    <Trash2 className="size-3.5" />
                    Xoá
                  </Button>
                </header>
                <div className="border-b border-border px-3 py-2 text-xs">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">Tài liệu này viết cho:</span>
                    {current.forEndpoints.length === 0 && <span className="text-muted-foreground">chưa khai báo (tài liệu chung, mặc định tick cho mọi endpoint)</span>}
                    {current.forEndpoints.map((k) => (
                      <span key={k} className="rounded-full bg-primary/10 px-2 py-0.5 font-mono text-primary">
                        {k}
                      </span>
                    ))}
                    <Button type="button" size="xs" variant="outline" aria-expanded={pickerOpen} onClick={() => { setPickerOpen(!pickerOpen); setPickerFilter("") }}>
                      {pickerOpen ? "Thu gọn" : "Chọn endpoint…"}
                    </Button>
                  </div>
                  <p className="mt-1 text-muted-foreground">Khi bạn sinh test cho endpoint đó, tài liệu này được tick sẵn; vẫn đổi được lúc sinh. Không ép buộc.</p>
                  {pickerOpen && (
                    <div className="mt-2 flex flex-col gap-1.5">
                      <input
                        aria-label="Lọc endpoint"
                        placeholder="Lọc endpoint…"
                        value={pickerFilter}
                        onChange={(e) => setPickerFilter(e.target.value)}
                        className="h-7 w-56 rounded-md border border-input bg-transparent px-2 text-xs"
                      />
                      <ul className="max-h-48 overflow-y-auto rounded-md border border-border p-1.5">
                        {endpointKeys
                          .filter((k) => k.toLowerCase().includes(pickerFilter.trim().toLowerCase()))
                          .map((k) => {
                            const on = current.forEndpoints.includes(k)
                            return (
                              <li key={k}>
                                <label className="flex items-center gap-2 py-0.5 font-mono">
                                  <input
                                    type="checkbox"
                                    checked={on}
                                    disabled={saveEndpoints.isPending}
                                    onChange={(e) =>
                                      saveEndpoints.mutate({ docId: current.id, keys: e.target.checked ? [...current.forEndpoints, k] : current.forEndpoints.filter((x) => x !== k) })
                                    }
                                  />
                                  {k}
                                </label>
                              </li>
                            )
                          })}
                      </ul>
                    </div>
                  )}
                </div>
                {loadingContent && <p className="p-3 text-sm text-muted-foreground">Đang tải nội dung...</p>}
                {content && (
                  <pre className="max-h-[60vh] overflow-auto p-3 font-sans text-sm leading-relaxed whitespace-pre-wrap">{content.content}</pre>
                )}
              </>
            )}
          </section>
        </div>
      )}

      {usage.length > 0 && (
        <section aria-label="Lịch sử sử dụng tài liệu" className="mt-6">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h3 className="text-sm font-semibold">Đã gửi cho AI để lập checklist</h3>
            <div role="tablist" aria-label="Cách xem lịch sử" className="flex gap-1.5">
              <Button type="button" role="tab" size="xs" variant={view === "byDoc" ? "default" : "outline"} aria-selected={view === "byDoc"} onClick={() => setView("byDoc")}>
                Theo tài liệu
              </Button>
              <Button type="button" role="tab" size="xs" variant={view === "byEndpoint" ? "default" : "outline"} aria-selected={view === "byEndpoint"} onClick={() => setView("byEndpoint")}>
                Theo endpoint
              </Button>
            </div>
          </div>
          <div className="mt-2 max-h-[45vh] overflow-auto rounded-md border border-border">
            <table className="w-full text-left text-xs">
              <thead className="sticky top-0 bg-muted/60 text-muted-foreground">
                <tr>
                  <th className="px-3 py-2 font-medium">{view === "byDoc" ? "Tài liệu" : "Endpoint"}</th>
                  <th className="px-3 py-2 font-medium">{view === "byDoc" ? "Endpoint" : "Tài liệu đã dùng"}</th>
                  <th className="px-3 py-2 font-medium">Lần gần nhất</th>
                  <th className="px-3 py-2 font-medium">Số lần</th>
                </tr>
              </thead>
              <tbody>
                {(view === "byDoc"
                  ? usage.slice().sort((a, b) => a.fileName.localeCompare(b.fileName) || a.endpointKey.localeCompare(b.endpointKey))
                  : usageByEndpoint.flatMap(([, rows]) => rows)
                ).map((u) => (
                  <tr key={`${u.documentId}-${u.endpointKey}`} className="border-t border-border">
                    <td className={cn("px-3 py-1.5", view === "byEndpoint" && "font-mono")}>
                      {view === "byDoc" ? u.fileName : u.endpointKey}
                      {view === "byDoc" && !u.documentActive && <span className="ml-1 text-muted-foreground">(đã xoá hoặc thay bản)</span>}
                    </td>
                    <td className={cn("px-3 py-1.5", view === "byDoc" && "font-mono")}>
                      {view === "byDoc" ? u.endpointKey : u.fileName}
                      {view === "byEndpoint" && !u.documentActive && <span className="ml-1 text-muted-foreground">(đã xoá hoặc thay bản)</span>}
                    </td>
                    <td className="px-3 py-1.5 text-muted-foreground">{formatDate(u.lastUsedAt)}</td>
                    <td className="px-3 py-1.5 text-muted-foreground">{u.timesUsed}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </div>
  )
}
