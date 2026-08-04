import { useState } from "react"
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
import { importOpenApi, type TargetAuthType } from "@/lib/endpoints"

interface ImportOpenApiDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: string
}

type ImportMode = "url" | "file"

const selectClassName =
  "h-8 w-full min-w-0 rounded-lg border border-input bg-transparent px-2.5 py-1 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 md:text-sm dark:bg-input/30"

export function ImportOpenApiDialog({
  open,
  onOpenChange,
  projectId,
}: ImportOpenApiDialogProps) {
  const queryClient = useQueryClient()
  const [mode, setMode] = useState<ImportMode>("url")
  const [url, setUrl] = useState("")
  const [file, setFile] = useState<File | null>(null)
  const [authType, setAuthType] = useState<TargetAuthType>("NONE")
  const [authValue, setAuthValue] = useState("")

  function resetForm() {
    setMode("url")
    setUrl("")
    setFile(null)
    setAuthType("NONE")
    setAuthValue("")
  }

  const mutation = useMutation({
    mutationFn: () =>
      importOpenApi(projectId, {
        url: mode === "url" ? url : undefined,
        file: mode === "file" ? (file ?? undefined) : undefined,
        authType: mode === "file" ? "NONE" : authType,
        authValue: mode === "file" ? "" : authValue,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["endpoints", projectId] })
      resetForm()
      onOpenChange(false)
    },
  })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    mutation.mutate()
  }

  const canSubmit = mode === "url" ? url.trim().length > 0 : file !== null

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) resetForm()
        onOpenChange(next)
      }}
    >
      <DialogContent>
        <form onSubmit={handleSubmit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>Import OpenAPI</DialogTitle>
            <DialogDescription>
              Nạp định nghĩa API từ URL hoặc file (OpenAPI/Swagger). Endpoint cũ của
              project sẽ được thay thế bằng danh sách mới.
            </DialogDescription>
          </DialogHeader>

          <div className="flex gap-2">
            <Button
              type="button"
              variant={mode === "url" ? "default" : "outline"}
              size="sm"
              onClick={() => setMode("url")}
            >
              Từ URL
            </Button>
            <Button
              type="button"
              variant={mode === "file" ? "default" : "outline"}
              size="sm"
              onClick={() => setMode("file")}
            >
              Từ file
            </Button>
          </div>

          {mode === "url" ? (
            <div key="url" className="flex flex-col gap-2">
              <Label htmlFor="openapi-url">URL OpenAPI/Swagger</Label>
              <Input
                id="openapi-url"
                type="url"
                placeholder="https://example.com/openapi.json"
                value={url}
                onChange={(e) => setUrl(e.target.value)}
              />
            </div>
          ) : (
            <div key="file" className="flex flex-col gap-2">
              <Label htmlFor="openapi-file">File OpenAPI/Swagger (JSON/YAML)</Label>
              <Input
                id="openapi-file"
                type="file"
                accept=".json,.yaml,.yml"
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              />
            </div>
          )}

          {mode === "url" ? (
            <div className="flex flex-col gap-2">
              <Label htmlFor="auth-type">Xác thực khi tải URL (tuỳ chọn)</Label>
              <p className="text-xs text-muted-foreground">
                Điền nếu URL này yêu cầu đăng nhập (API Key/Bearer Token) mới tải được.
              </p>
              <select
                id="auth-type"
                className={cn(selectClassName)}
                value={authType}
                onChange={(e) => setAuthType(e.target.value as TargetAuthType)}
              >
                <option value="NONE">Không</option>
                <option value="API_KEY">API Key</option>
                <option value="BEARER_TOKEN">Bearer Token</option>
              </select>

              {authType !== "NONE" && (
                <div className="flex flex-col gap-2">
                  <Label htmlFor="auth-value">
                    {authType === "API_KEY" ? "Giá trị API Key" : "Giá trị Bearer Token"}
                  </Label>
                  <Input
                    id="auth-value"
                    type="password"
                    value={authValue}
                    onChange={(e) => setAuthValue(e.target.value)}
                  />
                </div>
              )}
            </div>
          ) : (
            <p className="text-xs text-muted-foreground">
              Xác thực để gọi API thật sẽ được cấu hình khi thiết lập chạy test case.
            </p>
          )}

          {mutation.isError && (
            <p className="text-sm text-destructive">
              {mutation.error instanceof ApiError
                ? mutation.error.message
                : "Đã xảy ra lỗi, vui lòng thử lại"}
            </p>
          )}

          <DialogFooter>
            <Button type="submit" disabled={!canSubmit || mutation.isPending}>
              {mutation.isPending ? "Đang import..." : "Import"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
