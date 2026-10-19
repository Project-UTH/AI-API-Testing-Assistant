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
import { ImportDiffPreview } from "@/components/projects/ImportDiffPreview"
import {
  getImportImpact,
  importOpenApi,
  previewImport,
  type ImportApplyMode,
  type ImportDiff,
  type TargetAuthType,
} from "@/lib/endpoints"
import { AUTH_TYPE_OPTIONS, authValueHint, authValueLabel } from "@/lib/authTypes"

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
  // Module 17 (17c) - chế độ áp spec lên project đã có endpoint + bước xem trước thay đổi.
  const [applyMode, setApplyMode] = useState<ImportApplyMode>("DIFF")
  const [diffPreview, setDiffPreview] = useState<ImportDiff | null>(null)

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
  // Project đã có endpoint thì mới có lựa chọn chế độ; project trống thì import thẳng như cũ.
  const hasExistingEndpoints = (impact?.endpointCount ?? 0) > 0
  const replaceMode = !hasExistingEndpoints || applyMode === "REPLACE"
  // Chỉ chế độ THAY THẾ xoá dữ liệu nên chỉ khi đó mới cần cảnh báo/xác nhận (chế độ DIFF giữ test case, lịch sử, bug report).
  const needsConfirm = replaceMode && (impactUnknown || (impact !== undefined && hasDataToLose(impact)))

  function resetForm() {
    setConfirmedDataLoss(false)
    setMode("url")
    setUrl("")
    setFile(null)
    setAuthType("NONE")
    setAuthValue("")
    setTargetBaseUrl("")
    setApplyMode("DIFF")
    setDiffPreview(null)
  }

  function importInput() {
    return {
      url: mode === "url" ? url : undefined,
      file: mode === "file" ? (file ?? undefined) : undefined,
      authType: mode === "file" ? ("NONE" as const) : authType,
      authValue: mode === "file" ? "" : authValue,
      targetBaseUrl,
      mode: hasExistingEndpoints ? applyMode : undefined,
    }
  }

  const previewMutation = useMutation({
    mutationFn: () => previewImport(projectId, importInput()),
    onSuccess: (diff) => setDiffPreview(diff),
  })

  const mutation = useMutation({
    mutationFn: () => importOpenApi(projectId, importInput()),
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

  // Chế độ cập nhật theo thay đổi (project đã có endpoint): bấm lần 1 = xem trước diff, lần 2 (sau khi thấy) = import thật.
  const needsPreviewStep = hasExistingEndpoints && !replaceMode
  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (needsPreviewStep && diffPreview === null) {
      previewMutation.mutate()
      return
    }
    mutation.mutate()
  }

  const formFilled = mode === "url" ? url.trim().length > 0 : file !== null
  const impactReady = !impactQuery.isPending
  const canSubmit = formFilled && impactReady && (!needsConfirm || confirmedDataLoss) && !previewMutation.isPending

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
          {impactUnknown && replaceMode && (
            <div role="alert" className="rounded-lg border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive">
              Không kiểm tra được dữ liệu hiện có của project. Import sẽ XOÁ toàn bộ test case, kết quả chạy và bug
              report của project này (nếu có) và không khôi phục được.
            </div>
          )}
          {hasExistingEndpoints && (
            <fieldset className="flex flex-col gap-2" disabled={diffPreview !== null}>
              <legend className="mb-1 text-sm font-medium">Project đã có endpoint - cách áp dụng spec mới</legend>
              <label className={cn("flex cursor-pointer items-start gap-2 rounded-lg border p-2.5 text-sm", applyMode === "DIFF" ? "border-primary bg-primary/5" : "border-border")}>
                <input type="radio" name="apply-mode" className="mt-1" checked={applyMode === "DIFF"} onChange={() => { setApplyMode("DIFF"); setDiffPreview(null) }} />
                <span>
                  <span className="font-medium">Cập nhật theo thay đổi (khuyến nghị)</span>
                  <span className="block text-xs text-muted-foreground">
                    Giữ test case, lịch sử chạy, bug report và ma trận phân quyền của endpoint không đổi. Chỉ thêm endpoint mới, cập nhật endpoint
                    đổi spec và đánh dấu endpoint không còn trong spec.
                  </span>
                </span>
              </label>
              <label className={cn("flex cursor-pointer items-start gap-2 rounded-lg border p-2.5 text-sm", applyMode === "REPLACE" ? "border-destructive bg-destructive/5" : "border-border")}>
                <input type="radio" name="apply-mode" className="mt-1" checked={applyMode === "REPLACE"} onChange={() => { setApplyMode("REPLACE"); setDiffPreview(null) }} />
                <span>
                  <span className="font-medium text-destructive">Thay thế toàn bộ</span>
                  <span className="block text-xs text-muted-foreground">Xoá mọi endpoint, test case, lịch sử chạy và bug report rồi tạo lại từ spec. Không khôi phục được.</span>
                </span>
              </label>
            </fieldset>
          )}
          {replaceMode && impact !== undefined && hasDataToLose(impact) && (
            <DataLossWarning impact={impact} title="Import sẽ xoá dữ liệu hiện có của project này" />
          )}
          {previewMutation.isError && (
            <p className="text-sm text-destructive">
              {previewMutation.error instanceof ApiError ? previewMutation.error.message : "Không xem trước được, vui lòng thử lại"}
            </p>
          )}
          {diffPreview !== null && <ImportDiffPreview diff={diffPreview} />}

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
                {AUTH_TYPE_OPTIONS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>

              {authType !== "NONE" && (
                <div className="flex flex-col gap-2">
                  <Label htmlFor="auth-value">
                    {authValueLabel(authType)}
                  </Label>
                  {authValueHint(authType) && <p className="text-xs text-muted-foreground">{authValueHint(authType)}</p>}
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
              {mutation.isPending
                ? "Đang import..."
                : previewMutation.isPending
                  ? "Đang so sánh..."
                  : needsConfirm
                    ? "Import và xoá dữ liệu cũ"
                    : needsPreviewStep && diffPreview === null
                      ? "Xem trước thay đổi"
                      : needsPreviewStep
                        ? "Import (cập nhật theo thay đổi)"
                        : "Import"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
