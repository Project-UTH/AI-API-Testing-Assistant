import { useState } from "react"
import { useNavigate } from "react-router-dom"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { Loader2, RefreshCw, Shuffle } from "lucide-react"

import { Button } from "@/components/ui/button"
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
import { triggerExecution, type TestResult } from "@/lib/executions"

/** Nhãn nhỏ cạnh badge kết quả khi test KHÔNG ỔN ĐỊNH - áp cho cả Pass lẫn Fail (1 lần Pass của test chập chờn cũng không chắc chắn). */
export function UnstableBadge({ result }: { result: TestResult }) {
  const s = result.stability
  if (!s || s.state !== "FLAKY") return null
  return (
    <span
      className="shrink-0 rounded-full bg-orange-500/10 px-2 py-0.5 text-xs font-medium text-orange-600 dark:text-orange-400"
      title={`Cùng phiên bản test: đổi Pass/Fail ${s.flips} lần trong ${s.runs} lần chạy (Pass ${s.passes}/${s.runs}). Không tin 1 lần chạy riêng lẻ.`}
    >
      Không ổn định · đổi {s.flips}/{s.runs}
    </span>
  )
}

/**
 * Module 16 gđ2 - gợi ý chạy lại khi lịch sử cho thấy kết quả không nhất quán nhưng CHƯA đủ lần chạy để kết luận. Hệ thống
 * chỉ GỢI Ý, không tự chạy: người dùng bấm thì mới chạy lại đúng test case này (cùng chức năng chạy test có sẵn, mỗi lần
 * bấm = 1 lần chạy). Test có thể ghi dữ liệu thật (POST/PUT/PATCH/DELETE) phải qua hộp xác nhận.
 */
export function StabilityNote({ result, projectId }: { result: TestResult; projectId: string }) {
  const s = result.stability
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [confirmOpen, setConfirmOpen] = useState(false)

  const rerun = useMutation({
    mutationFn: () => triggerExecution(projectId, [result.testCaseId]),
    onSuccess: (execution) => {
      setConfirmOpen(false)
      queryClient.invalidateQueries({ queryKey: ["executions", projectId] })
      navigate(`/projects/${projectId}/executions/${execution.id}`)
    },
  })

  if (!s) return null

  if (s.state === "FLAKY") {
    return (
      <div className="rounded-md border border-orange-500/30 bg-orange-500/5 p-2.5 text-xs">
        <p className="flex flex-wrap items-center gap-1.5 font-medium text-orange-600 dark:text-orange-400">
          <Shuffle className="h-3.5 w-3.5" />
          Test này không ổn định: đổi Pass/Fail {s.flips} lần trong {s.runs} lần chạy cùng phiên bản (Pass {s.passes}/{s.runs})
        </p>
        <p className="mt-1 text-muted-foreground">
          Đừng tin 1 lần chạy riêng lẻ của test này, kể cả khi Pass. Hệ thống không tự mở lại hay đóng Bug Report dựa trên nó, và
          Coverage không tính nó là "đã kiểm". Nguyên nhân có thể ở chính test (phụ thuộc dữ liệu/thời gian) hoặc ở API đích -
          kiểm thử từ bên ngoài không phân biệt được.
        </p>
      </div>
    )
  }

  return (
    <div className="rounded-md border border-sky-500/30 bg-sky-500/5 p-2.5 text-xs">
      <p className="font-medium text-sky-600 dark:text-sky-400">Nên chạy lại test này để có thêm bằng chứng</p>
      <p className="mt-1 text-muted-foreground">{s.rerunReason}</p>
      <div className="mt-2 flex flex-wrap items-center gap-2">
        <Button
          size="xs"
          variant="outline"
          disabled={rerun.isPending}
          onClick={() => (s.mutatesData ? setConfirmOpen(true) : rerun.mutate())}
        >
          {rerun.isPending ? <Loader2 className="animate-spin" /> : <RefreshCw />}
          Chạy lại test này
        </Button>
        <span className="text-muted-foreground">Mỗi lần bấm = 1 lần chạy. Hệ thống không tự chạy lại.</span>
      </div>
      {rerun.isError && !confirmOpen && (
        <p className="mt-1.5 text-destructive">
          {rerun.error instanceof ApiError ? rerun.error.message : "Không chạy lại được, vui lòng thử lại"}
        </p>
      )}

      <AlertDialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Chạy lại "{result.testCaseName}"?</AlertDialogTitle>
            <AlertDialogDescription>
              Đây là yêu cầu {result.endpointMethod}: chạy lại sẽ GỌI THẬT vào hệ thống đích và có thể tạo, sửa hoặc xoá dữ
              liệu thật ở đó (vd mỗi lần chạy lại có thể tạo thêm 1 bản ghi). Chỉ tiếp tục nếu bạn chấp nhận điều này.
            </AlertDialogDescription>
          </AlertDialogHeader>
          {rerun.isError && (
            <p className="text-sm text-destructive">
              {rerun.error instanceof ApiError ? rerun.error.message : "Không chạy lại được, vui lòng thử lại"}
            </p>
          )}
          <AlertDialogFooter>
            <AlertDialogCancel disabled={rerun.isPending}>Huỷ</AlertDialogCancel>
            <AlertDialogAction disabled={rerun.isPending} onClick={() => rerun.mutate()}>
              {rerun.isPending ? "Đang chạy..." : "Vẫn chạy lại"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
