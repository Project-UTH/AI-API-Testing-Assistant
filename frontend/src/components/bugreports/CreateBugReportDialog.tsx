import { useEffect, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { cn, METHOD_STYLES, selectClassName } from "@/lib/utils"
import { ApiError } from "@/lib/api"
import {
  BUG_FREQUENCY_LABEL,
  BUG_PRIORITY_LABEL,
  BUG_SEVERITY_LABEL,
} from "@/components/shared/BugStatusBadge"
import {
  createBugReport,
  getBugReportDraft,
  type BugFrequency,
  type BugPriority,
  type BugSeverity,
} from "@/lib/bugReports"

interface CreateBugReportDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: string
  /** null khi dialog đang đóng - useQuery chỉ enabled khi có giá trị thật. */
  sourceTestResultId: string | null
  /** Gọi thêm sau khi tạo thành công, ngoài việc tự invalidate ["bug-reports", projectId] - dùng khi
   *  nơi gọi (VD TestExecutionPage) cần tự invalidate thêm query riêng của nó (VD execution, để dòng
   *  vừa tạo bug cập nhật ngay `existingBugReportId` mà không cần F5). */
  onCreated?: () => void
}

export function CreateBugReportDialog({ open, onOpenChange, projectId, sourceTestResultId, onCreated }: CreateBugReportDialogProps) {
  const queryClient = useQueryClient()
  const [summary, setSummary] = useState("")
  // Gộp Môi trường/Các bước tái hiện/Kết quả mong đợi/Kết quả thực tế thành 1 Mô tả duy nhất
  // (theo mẫu QA thực tế) - backend đã tự sinh sẵn nội dung chi tiết dựa trên response body thật.
  const [description, setDescription] = useState("")
  const [severity, setSeverity] = useState<BugSeverity>("MINOR")
  const [frequency, setFrequency] = useState<BugFrequency>("SELDOM")
  const [priority, setPriority] = useState<BugPriority>("TRIVIAL")
  const [attachmentUrl, setAttachmentUrl] = useState("")
  const [build, setBuild] = useState("")

  const { data: draft, isLoading: isDraftLoading } = useQuery({
    queryKey: ["bug-report-draft", projectId, sourceTestResultId],
    queryFn: () => getBugReportDraft(projectId, sourceTestResultId!),
    enabled: open && Boolean(sourceTestResultId),
  })

  useEffect(() => {
    if (open && draft) {
      setSummary(draft.summary)
      setDescription(draft.stepsToReproduce ?? "")
      setSeverity("MINOR")
      setFrequency("SELDOM")
      setPriority("TRIVIAL")
      setAttachmentUrl("")
      setBuild(draft.defaultBuild)
    }
  }, [open, draft])

  const mutation = useMutation({
    mutationFn: () =>
      createBugReport(projectId, {
        sourceTestResultId: sourceTestResultId!,
        summary,
        testEnvironment: null,
        stepsToReproduce: description || null,
        actualResult: null,
        expectedResult: null,
        severity,
        frequency,
        priority,
        attachmentUrl: attachmentUrl || null,
        build: build || null,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["bug-reports", projectId] })
      onCreated?.()
      onOpenChange(false)
    },
  })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    mutation.mutate()
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-xl">
        <form onSubmit={handleSubmit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>Tạo Bug Report</DialogTitle>
            <DialogDescription>
              Sinh từ lần chạy Fail - kiểm tra lại thông tin gợi ý bên dưới trước khi lưu.
            </DialogDescription>
          </DialogHeader>

          {isDraftLoading && <p className="text-sm text-muted-foreground">Đang tải gợi ý...</p>}

          {draft && (
            <>
              <div className="flex flex-col gap-2">
                <Label>Khu vực chức năng (Component)</Label>
                <div className="flex items-center gap-2 rounded-md border border-border bg-muted/40 px-2.5 py-1.5 text-sm">
                  <span
                    className={cn(
                      "w-14 shrink-0 rounded-md px-1.5 py-0.5 text-center text-xs font-semibold",
                      METHOD_STYLES[draft.component.endpointMethod] ?? "bg-muted text-muted-foreground"
                    )}
                  >
                    {draft.component.endpointMethod}
                  </span>
                  <span className="min-w-0 flex-1 truncate font-mono text-xs">{draft.component.endpointPath}</span>
                </div>
              </div>

              <div className="flex flex-col gap-2">
                <Label htmlFor="bug-summary">Tiêu đề</Label>
                <Input id="bug-summary" required value={summary} onChange={(e) => setSummary(e.target.value)} />
              </div>

              <div className="flex flex-col gap-2">
                <Label htmlFor="bug-description">Mô tả</Label>
                <p className="text-xs text-muted-foreground">
                  Gồm môi trường kiểm thử, các bước tái hiện, kết quả mong đợi và kết quả thực tế
                  (đã tự điền sẵn dựa trên response body thật của lần chạy này) - sửa lại nếu cần.
                </p>
                <Textarea
                  id="bug-description"
                  rows={12}
                  className="font-mono text-xs"
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                />
              </div>

              <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                <div className="flex flex-col gap-2">
                  <Label htmlFor="bug-severity">Mức độ nghiêm trọng</Label>
                  <select
                    id="bug-severity"
                    className={selectClassName}
                    value={severity}
                    onChange={(e) => setSeverity(e.target.value as BugSeverity)}
                  >
                    {(Object.keys(BUG_SEVERITY_LABEL) as BugSeverity[]).map((v) => (
                      <option key={v} value={v}>{BUG_SEVERITY_LABEL[v]}</option>
                    ))}
                  </select>
                </div>
                <div className="flex flex-col gap-2">
                  <Label htmlFor="bug-frequency">Tần suất</Label>
                  <select
                    id="bug-frequency"
                    className={selectClassName}
                    value={frequency}
                    onChange={(e) => setFrequency(e.target.value as BugFrequency)}
                  >
                    {(Object.keys(BUG_FREQUENCY_LABEL) as BugFrequency[]).map((v) => (
                      <option key={v} value={v}>{BUG_FREQUENCY_LABEL[v]}</option>
                    ))}
                  </select>
                </div>
                <div className="flex flex-col gap-2">
                  <Label htmlFor="bug-priority">Độ ưu tiên</Label>
                  <select
                    id="bug-priority"
                    className={selectClassName}
                    value={priority}
                    onChange={(e) => setPriority(e.target.value as BugPriority)}
                  >
                    {(Object.keys(BUG_PRIORITY_LABEL) as BugPriority[]).map((v) => (
                      <option key={v} value={v}>{BUG_PRIORITY_LABEL[v]}</option>
                    ))}
                  </select>
                </div>
              </div>

              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <div className="flex flex-col gap-2">
                  <Label htmlFor="bug-attachment">Đính kèm (link/URL, tuỳ chọn)</Label>
                  <Input id="bug-attachment" value={attachmentUrl} onChange={(e) => setAttachmentUrl(e.target.value)} />
                </div>
                <div className="flex flex-col gap-2">
                  <Label htmlFor="bug-build">Bản build</Label>
                  <Input id="bug-build" value={build} onChange={(e) => setBuild(e.target.value)} />
                </div>
              </div>
            </>
          )}

          {mutation.isError && (
            <p className="text-sm text-destructive">
              {mutation.error instanceof ApiError ? mutation.error.message : "Đã xảy ra lỗi, vui lòng thử lại"}
            </p>
          )}

          <DialogFooter>
            <Button type="submit" disabled={!draft || mutation.isPending}>
              {mutation.isPending ? "Đang lưu..." : "Tạo Bug Report"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
