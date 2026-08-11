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
import { updateTargetAuth } from "@/lib/projects"
import type { TargetAuthType } from "@/lib/endpoints"

interface TargetAuthDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: string
  currentAuthType: TargetAuthType
}

const selectClassName =
  "h-8 w-full min-w-0 rounded-lg border border-input bg-transparent px-2.5 py-1 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 md:text-sm dark:bg-input/30"

export function TargetAuthDialog({
  open,
  onOpenChange,
  projectId,
  currentAuthType,
}: TargetAuthDialogProps) {
  const queryClient = useQueryClient()
  const [authType, setAuthType] = useState<TargetAuthType>(currentAuthType)
  const [authValue, setAuthValue] = useState("")

  useEffect(() => {
    if (open) {
      setAuthType(currentAuthType)
      setAuthValue("")
      mutation.reset()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, currentAuthType])

  const mutation = useMutation({
    mutationFn: () => updateTargetAuth(projectId, { authType, authValue: authType === "NONE" ? undefined : authValue }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["projects", projectId] })
      onOpenChange(false)
    },
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
              <option value="NONE">Không</option>
              <option value="API_KEY">API Key</option>
              <option value="BEARER_TOKEN">Bearer Token</option>
            </select>
          </div>

          {authType !== "NONE" && (
            <div className="flex flex-col gap-2">
              <Label htmlFor="target-auth-value">
                {authType === "API_KEY" ? "Giá trị API Key" : "Giá trị Bearer Token"}
              </Label>
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
