import { useEffect } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { ApiError } from "@/lib/api"
import { deleteBugReport, type BugReport } from "@/lib/bugReports"

interface DeleteBugReportDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: string
  bugReport: BugReport | null
}

export function DeleteBugReportDialog({
  open,
  onOpenChange,
  projectId,
  bugReport,
}: DeleteBugReportDialogProps) {
  const queryClient = useQueryClient()

  const mutation = useMutation({
    mutationFn: () => {
      if (!bugReport) {
        return Promise.resolve()
      }
      return deleteBugReport(projectId, bugReport.id)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["bug-reports", projectId] })
      onOpenChange(false)
    },
  })

  useEffect(() => {
    if (open) {
      mutation.reset()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, bugReport?.id])

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Xoá bug "{bugReport?.bugId}"?</AlertDialogTitle>
          <AlertDialogDescription>
            Hành động này không thể hoàn tác. Bug report sẽ bị xoá vĩnh viễn, bạn có thể tạo lại bug
            mới từ lần chạy tương ứng nếu cần.
          </AlertDialogDescription>
        </AlertDialogHeader>
        {mutation.isError && (
          <p className="text-sm text-destructive">
            {mutation.error instanceof ApiError
              ? mutation.error.message
              : "Đã xảy ra lỗi, vui lòng thử lại"}
          </p>
        )}
        <AlertDialogFooter>
          <AlertDialogCancel disabled={mutation.isPending}>
            Huỷ
          </AlertDialogCancel>
          <AlertDialogAction
            variant="destructive"
            disabled={mutation.isPending}
            onClick={() => mutation.mutate()}
          >
            {mutation.isPending ? "Đang xoá..." : "Xoá"}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
