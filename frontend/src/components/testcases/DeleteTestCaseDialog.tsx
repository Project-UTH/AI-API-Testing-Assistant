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
import { deleteTestCase, type TestCase } from "@/lib/testcases"

interface DeleteTestCaseDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: string
  testCase: TestCase | null
}

export function DeleteTestCaseDialog({
  open,
  onOpenChange,
  projectId,
  testCase,
}: DeleteTestCaseDialogProps) {
  const queryClient = useQueryClient()

  const mutation = useMutation({
    mutationFn: () => {
      if (!testCase) {
        return Promise.resolve()
      }
      return deleteTestCase(projectId, testCase.endpointId, testCase.id)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["test-cases", projectId] })
      queryClient.invalidateQueries({ queryKey: ["endpoints", projectId] })
      onOpenChange(false)
    },
  })

  useEffect(() => {
    if (open) {
      mutation.reset()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, testCase?.id])

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Xoá "{testCase?.name}"?</AlertDialogTitle>
          <AlertDialogDescription>
            Hành động này không thể hoàn tác. Test case sẽ bị xoá vĩnh viễn.
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
