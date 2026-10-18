import { useEffect, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { DataLossWarning, hasDataToLose } from "@/components/shared/DataLossWarning"
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
import { getImportImpact, importOpenApi, type TargetAuthType } from "@/lib/endpoints"

interface ImportOpenApiDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: string
}

type ImportMode = "url" | "file"

// Các màn hình hiển thị dữ liệu mà import sẽ xoá - phải tải lại, không được giữ số liệu cũ trong cache.
const INVALIDATE_ON_IMPORT = [
  "test-cases",
  "bug-reports",
  "bug-report-run-history",
  "bug-report-draft",
  "executions",
  "history",
  "history-feed",
  "generation-plan",
  "dashboard-trend",
  "dashboard-recent-activity",
]

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
  const [targetBaseUrl, setTargetBaseUrl] = useState("")
  const [confirmedDataLoss, setConfirmedDataLoss] = useState(false)

  // Số liệu dữ liệu sẽ bị xoá - tải mỗi lần mở dialog (không dùng cache cũ). Lỗi tải = "chưa biết", KHÔNG được coi
  // là "không có dữ liệu" (fail-open): khi đó vẫn bắt xác nhận với cảnh báo chung.
  const impactQuery = useQuery({
    queryKey: ["import-impact", projectId],
    queryFn: () => getImportImpact(projectId),
    enabled: open,
    gcTime: 0,
    staleTime: 0,
  })
  // Đóng dialog từ bên ngoài (không qua onOpenChange) vẫn phải bỏ tick xác nhận, lần mở sau xác nhận lại.
  useEffect(() => {
    if (!open) setConfirmedDataLoss(false)
  }, [open])
  const impact = impactQuery.data
  const impactUnknown = impactQuery.isError
  const needsConfirm = impactUnknown || (impact !== undefined && hasDataToLose(impact))

  function resetForm() {
    setConfirmedDataLoss(false)
    setMode("url")
    setUrl("")
    setFile(null)
    setAuthType("NONE")
    setAuthValue("")
    setTargetBaseUrl("")
  }

  const mutation = useMutation({
    mutationFn: () =>
      importOpenApi(projectId, {
        url: mode === "url" ? url : undefined,
        file: mode === "file" ? (file ?? undefined) : undefined,
        authType: mode === "file" ? "NONE" : authType,
        authValue: mode === "file" ? "" : authValue,
        targetBaseUrl,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["endpoints", projectId] })
      // Import xoá cả test case/kết quả/bug của project - các màn hình đó không được giữ số liệu cũ trong cache.
      queryClient.invalidateQueries({ queryKey: ["import-impact", projectId] })
      queryClient.invalidateQueries({ queryKey: ["project-coverage", projectId] })
      queryClient.invalidateQueries({ queryKey: ["dashboard-summary"] })
      INVALIDATE_ON_IMPORT.forEach((key) => queryClient.invalidateQueries({ queryKey: [key] }))
      // Import cũng cập nhật targetBaseUrl/targetAuthType của chính Project (suy ra từ servers[]
      // trong OpenAPI, hoặc auth vừa nhập) - thiếu dòng này khiến khối "Target Base URL"/"Xác thực"
      // ở đầu trang hiện giá trị CŨ (rỗng lúc mới tạo project) cho tới khi người dùng tự tải lại
      // trang, dù backend đã lưu đúng giá trị mới.
      queryClient.invalidateQueries({ queryKey: ["projects", projectId] })
      resetForm()
      onOpenChange(false)
    },
  })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    mutation.mutate()
  }

  const formFilled = mode === "url" ? url.trim().length > 0 : file !== null
  const impactReady = !impactQuery.isPending
  const canSubmit = formFilled && impactReady && (!needsConfirm || confirmedDataLoss)

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
              Nạp định nghĩa API từ URL hoặc file (OpenAPI/Swagger).
            </DialogDescription>
          </DialogHeader>

          {impactQuery.isPending && (
            <p className="text-sm text-muted-foreground">Đang kiểm tra dữ liệu hiện có của project...</p>
          )}
          {impactUnknown && (
            <div role="alert" className="rounded-lg border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive">
              Không kiểm tra được dữ liệu hiện có của project. Import sẽ XOÁ toàn bộ test case, kết quả chạy và bug
              report của project này (nếu có) và không khôi phục được.
            </div>
          )}
          {impact !== undefined && hasDataToLose(impact) && (
            <DataLossWarning impact={impact} title="Import sẽ xoá dữ liệu hiện có của project này" />
          )}

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

          <div className="flex flex-col gap-2">
            <Label htmlFor="target-base-url">Target Base URL (URL gọi API thật lúc chạy test)</Label>
            <p className="text-xs text-muted-foreground">
              Khác với URL/file phía trên (đó chỉ là nơi lấy tài liệu OpenAPI). Để trống nếu tài
              liệu có khai báo <code>servers</code>, hệ thống sẽ tự dùng giá trị đó.
            </p>
            <Input
              id="target-base-url"
              type="url"
              placeholder="https://api.example.com"
              value={targetBaseUrl}
              onChange={(e) => setTargetBaseUrl(e.target.value)}
            />
          </div>

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

          <DialogFooter className="sticky -bottom-4 z-10 bg-muted sm:flex-col sm:items-stretch sm:justify-start">
            {needsConfirm && (
              <label className="flex items-start gap-2 text-sm">
                <Checkbox
                  checked={confirmedDataLoss}
                  onCheckedChange={(checked) => setConfirmedDataLoss(checked === true)}
                  className="mt-0.5"
                />
                <span>Tôi hiểu các dữ liệu nêu ở trên sẽ bị xoá vĩnh viễn</span>
              </label>
            )}
            <Button
              type="submit"
              variant={needsConfirm ? "destructive" : "default"}
              className="sm:self-end"
              disabled={!canSubmit || mutation.isPending}
            >
              {mutation.isPending ? "Đang import..." : needsConfirm ? "Import và xoá dữ liệu cũ" : "Import"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
