import { useEffect, useState } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { cn } from "@/lib/utils"
import { ApiError } from "@/lib/api"
import { updateApiConventions, updateTargetAuth } from "@/lib/projects"
import type { TargetAuthType } from "@/lib/endpoints"
import { AUTH_TYPE_OPTIONS, authValueHint, authValueLabel } from "@/lib/authTypes"

interface TargetAuthDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: string
  currentAuthType: TargetAuthType
  /** Mã API đích trả khi thiếu/sai token (403 hoặc null = 401). */
  currentUnauthenticatedStatus?: number | null
}

const selectClassName =
  "h-8 w-full min-w-0 rounded-lg border border-input bg-transparent px-2.5 py-1 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 md:text-sm dark:bg-input/30"

export function TargetAuthDialog({
  open,
  onOpenChange,
  projectId,
  currentAuthType,
  currentUnauthenticatedStatus = null,
}: TargetAuthDialogProps) {
  const queryClient = useQueryClient()
  const [authType, setAuthType] = useState<TargetAuthType>(currentAuthType)
  const [authValue, setAuthValue] = useState("")
  const [deniedStatus, setDeniedStatus] = useState<string>(currentUnauthenticatedStatus === 403 ? "403" : "401")

  useEffect(() => {
    if (open) {
      setAuthType(currentAuthType)
      setAuthValue("")
      setDeniedStatus(currentUnauthenticatedStatus === 403 ? "403" : "401")
      mutation.reset()
      conventions.reset()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, currentAuthType, currentUnauthenticatedStatus])

  const mutation = useMutation({
    mutationFn: () => updateTargetAuth(projectId, { authType, authValue: authType === "NONE" ? undefined : authValue }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["projects", projectId] })
      onOpenChange(false)
    },
  })

  const conventions = useMutation({
    mutationFn: () => updateApiConventions(projectId, { unauthenticatedStatus: deniedStatus === "403" ? 403 : null }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["projects", projectId] }),
  })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    mutation.mutate()
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <form onSubmit={handleSubmit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>Xác thực gọi API thật</DialogTitle>
            <DialogDescription>
              Dùng khi chạy test (Module 6) - độc lập với lúc import, đổi được bất cứ lúc nào mà
              không cần import lại endpoint.
            </DialogDescription>
          </DialogHeader>

          <div className="flex flex-col gap-2">
            <Label htmlFor="target-auth-type">Loại xác thực</Label>
            <select
              id="target-auth-type"
              className={cn(selectClassName)}
              value={authType}
              onChange={(e) => setAuthType(e.target.value as TargetAuthType)}
            >
              {AUTH_TYPE_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </div>

          {authType !== "NONE" && (
            <div className="flex flex-col gap-2">
              <Label htmlFor="target-auth-value">
                {authValueLabel(authType)}
              </Label>
              {authValueHint(authType) && <p className="text-xs text-muted-foreground">{authValueHint(authType)}</p>}
              <p className="text-xs text-muted-foreground">
                Bắt buộc nhập lại mỗi lần lưu, kể cả khi chỉ muốn giữ nguyên loại xác thực đang dùng
                (giá trị cũ không hiện lại vì đã mã hoá, không đọc ngược được).
              </p>
              <Input
                id="target-auth-value"
                type="password"
                value={authValue}
                onChange={(e) => setAuthValue(e.target.value)}
                required
              />
            </div>
          )}

          <div className="flex flex-col gap-2 rounded-md border border-border p-3">
            <Label htmlFor="unauth-status">Mã API trả khi thiếu hoặc sai token</Label>
            <p className="text-xs text-muted-foreground">
              Chuẩn là 401. Một số API (vd Spring Security mặc định) trả 403. Chọn đúng quy ước của API này để test Security "thiếu token / token sai" không bị Fail giả.
              Chỉ áp dụng cho test cố ý bỏ hoặc làm sai token; các test khác vẫn so khớp mã như thường. Lưu riêng, không cần nhập lại token.
            </p>
            <div className="flex items-center gap-2">
              <select id="unauth-status" className={cn(selectClassName, "max-w-48")} value={deniedStatus} onChange={(e) => setDeniedStatus(e.target.value)}>
                <option value="401">401 Unauthorized (chuẩn)</option>
                <option value="403">403 Forbidden</option>
              </select>
              <Button type="button" size="sm" variant="outline" disabled={conventions.isPending} onClick={() => conventions.mutate()}>
                {conventions.isPending ? "Đang lưu..." : conventions.isSuccess ? "Đã lưu" : "Lưu quy ước"}
              </Button>
            </div>
            {conventions.isError && <p className="text-sm text-destructive">{conventions.error instanceof ApiError ? conventions.error.message : "Đã xảy ra lỗi"}</p>}
          </div>

          {mutation.isError && (
            <p className="text-sm text-destructive">
              {mutation.error instanceof ApiError
                ? mutation.error.message
                : "Đã xảy ra lỗi, vui lòng thử lại"}
            </p>
          )}

          <DialogFooter>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? "Đang lưu..." : "Lưu"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
