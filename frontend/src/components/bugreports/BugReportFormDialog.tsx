import { useEffect, useState } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { Download } from "lucide-react"

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
  BUG_STATUS_LABEL,
} from "@/components/shared/BugStatusBadge"
import {
  exportBugReport,
  updateBugReport,
  type BugFrequency,
  type BugPriority,
  type BugReport,
  type BugSeverity,
  type BugStatus,
} from "@/lib/bugReports"

interface BugReportFormDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: string
  bugReport: BugReport | null
}

export function BugReportFormDialog({ open, onOpenChange, projectId, bugReport }: BugReportFormDialogProps) {
  const queryClient = useQueryClient()
  const [status, setStatus] = useState<BugStatus>("NEW")
  const [severity, setSeverity] = useState<BugSeverity>("MINOR")
  const [frequency, setFrequency] = useState<BugFrequency>("SELDOM")
  const [priority, setPriority] = useState<BugPriority>("TRIVIAL")
  const [summary, setSummary] = useState("")
  // Gộp Môi trường/Các bước tái hiện/Kết quả mong đợi/Kết quả thực tế thành 1 Mô tả duy nhất,
  // giống CreateBugReportDialog - lưu vào field stepsToReproduce (không đổi tên field API).
  const [description, setDescription] = useState("")
  const [attachmentUrl, setAttachmentUrl] = useState("")
  const [build, setBuild] = useState("")
  const [note, setNote] = useState("")

  useEffect(() => {
    if (open && bugReport) {
      setStatus(bugReport.status)
      setSeverity(bugReport.severity)
      setFrequency(bugReport.frequency)
      setPriority(bugReport.priority)
      setSummary(bugReport.summary)
      setDescription(bugReport.stepsToReproduce ?? "")
      setAttachmentUrl(bugReport.attachmentUrl ?? "")
      setBuild(bugReport.build ?? "")
      setNote(bugReport.note ?? "")
    }
  }, [open, bugReport])

  const mutation = useMutation({
    mutationFn: () =>
      updateBugReport(projectId, bugReport!.id, {
        status,
        severity,
        frequency,
        priority,
        summary,
        // testEnvironment/actualResult/expectedResult không còn ô riêng trên form (đã gộp vào Mô
        // tả) - giữ nguyên giá trị cũ nếu bug này tạo từ trước khi gộp, tránh mất dữ liệu cũ.
        testEnvironment: bugReport!.testEnvironment,
        stepsToReproduce: description || null,
        actualResult: bugReport!.actualResult,
        expectedResult: bugReport!.expectedResult,
        attachmentUrl: attachmentUrl || null,
        build: build || null,
        note: note || null,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["bug-reports", projectId] })
      onOpenChange(false)
    },
  })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    mutation.mutate()
  }

  const exportMutation = useMutation({
    mutationFn: () => exportBugReport(projectId, bugReport!.id, `${bugReport!.bugId}.xlsx`),
  })

  if (!bugReport) return null

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-xl">
        <form onSubmit={handleSubmit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>Sửa Bug Report {bugReport.bugId}</DialogTitle>
            <DialogDescription>Test case: {bugReport.testCaseName}</DialogDescription>
          </DialogHeader>

          <div className="flex flex-col gap-2">
            <Label>Khu vực chức năng (Component)</Label>
            <div className="flex items-center gap-2 rounded-md border border-border bg-muted/40 px-2.5 py-1.5 text-sm">
              <span
                className={cn(
                  "w-14 shrink-0 rounded-md px-1.5 py-0.5 text-center text-xs font-semibold",
                  METHOD_STYLES[bugReport.component.endpointMethod] ?? "bg-muted text-muted-foreground"
                )}
              >
                {bugReport.component.endpointMethod}
              </span>
              <span className="min-w-0 flex-1 truncate font-mono text-xs">{bugReport.component.endpointPath}</span>
            </div>
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="bug-edit-status">Trạng thái</Label>
            <select
              id="bug-edit-status"
              className={selectClassName}
              value={status}
              onChange={(e) => setStatus(e.target.value as BugStatus)}
            >
              {(Object.keys(BUG_STATUS_LABEL) as BugStatus[]).map((v) => (
                <option key={v} value={v}>{BUG_STATUS_LABEL[v]}</option>
              ))}
            </select>
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="bug-edit-summary">Tiêu đề</Label>
            <Input id="bug-edit-summary" required value={summary} onChange={(e) => setSummary(e.target.value)} />
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="bug-edit-description">Mô tả</Label>
            <Textarea
              id="bug-edit-description"
              rows={12}
              className="font-mono text-xs"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <div className="flex flex-col gap-2">
              <Label htmlFor="bug-edit-severity">Mức độ nghiêm trọng</Label>
              <select
                id="bug-edit-severity"
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
              <Label htmlFor="bug-edit-frequency">Tần suất</Label>
              <select
                id="bug-edit-frequency"
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
              <Label htmlFor="bug-edit-priority">Độ ưu tiên</Label>
              <select
                id="bug-edit-priority"
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
              <Label htmlFor="bug-edit-attachment">Đính kèm (link/URL, tuỳ chọn)</Label>
              <Input id="bug-edit-attachment" value={attachmentUrl} onChange={(e) => setAttachmentUrl(e.target.value)} />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="bug-edit-build">Bản build</Label>
              <Input id="bug-edit-build" value={build} onChange={(e) => setBuild(e.target.value)} />
            </div>
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="bug-edit-note">Ghi chú</Label>
            <Textarea id="bug-edit-note" value={note} onChange={(e) => setNote(e.target.value)} />
          </div>

          {mutation.isError && (
            <p className="text-sm text-destructive">
              {mutation.error instanceof ApiError ? mutation.error.message : "Đã xảy ra lỗi, vui lòng thử lại"}
            </p>
          )}
          {exportMutation.isError && (
            <p className="text-sm text-destructive">
              {exportMutation.error instanceof ApiError ? exportMutation.error.message : "Không xuất được file, vui lòng thử lại"}
            </p>
          )}

          <DialogFooter className="sm:justify-between">
            <Button
              type="button"
              variant="outline"
              disabled={exportMutation.isPending}
              onClick={() => exportMutation.mutate()}
            >
              <Download className="h-4 w-4" />
              {exportMutation.isPending ? "Đang xuất..." : "Xuất file Excel"}
            </Button>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? "Đang lưu..." : "Lưu thay đổi"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
