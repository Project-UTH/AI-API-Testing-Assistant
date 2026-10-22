import { useEffect, useState } from "react"
import { useMutation } from "@tanstack/react-query"
import { Loader2, Sparkles } from "lucide-react"

import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
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
import {
  confidenceCheck,
  expectationChanged,
  originDisplay,
  getReliabilityDisplay,
  type AssertionOperator,
  type ConfidenceCheckResponse,
  type TestCase,
  type TestCaseAuthOverride,
} from "@/lib/testcases"

const AGREEMENT_LABEL: Record<ConfidenceCheckResponse["agreementLevel"], string> = {
  HIGH: "Đồng thuận cao",
  PARTIAL: "Đồng thuận 1 phần",
  LOW: "Đồng thuận thấp",
}

const AGREEMENT_STYLES: Record<ConfidenceCheckResponse["agreementLevel"], string> = {
  HIGH: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400",
  PARTIAL: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  LOW: "bg-destructive/10 text-destructive",
}

const AUTH_OVERRIDE_LABEL: Record<TestCaseAuthOverride, string> = {
  DEFAULT: "Mặc định (dùng auth thật của Project)",
  NONE: "Không gửi auth (test case Security - thiếu token)",
  INVALID: "Gửi auth sai (test case Security - token sai)",
}

const ASSERTION_OPERATOR_LABEL: Record<AssertionOperator, string> = {
  EQUALS: "Bằng đúng (EQUALS)",
  CONTAINS: "Chứa chuỗi (CONTAINS)",
  EXISTS: "Có mặt field (EXISTS)",
  TYPE: "Đúng kiểu dữ liệu (TYPE)",
}

const SOURCE_LABEL: Record<TestCase["source"], string> = {
  AI_GENERATED: "AI",
  MANUAL: "Tự thêm",
  SECURITY: "Security",
  BUSINESS: "Nghiệp vụ (AI đoán)",
}

interface TestCaseDetailDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  testCase: TestCase | null
  /**
   * Chỉ truyền prop này ở luồng CHÍNH CHỦ xem test case của mình (vd TestCasesPage) - khi có,
   * dialog mới hiện nút "Đối chiếu độ tin cậy sâu hơn bằng AI" (Module 15e, tốn token thật).
   * KHÔNG truyền ở luồng Admin xem dữ liệu user khác (AdminProjectDataPage) - Admin chỉ đọc,
   * không được phép tốn token của người dùng khác.
   */
  projectId?: string
}

/**
 * Xem chi tiết 1 test case. Mặc định CHỈ ĐỌC (dùng ở trang Admin xem dữ liệu user khác, không
 * truyền projectId). Khi có projectId (luồng chính chủ), thêm nút gọi AI đối chiếu độ tin cậy sâu
 * hơn (Module 15e, Tier 3) - thủ công, người dùng tự bấm, không tự động.
 */
export function TestCaseDetailDialog({ open, onOpenChange, testCase, projectId }: TestCaseDetailDialogProps) {
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [result, setResult] = useState<ConfidenceCheckResponse | null>(null)

  const mutation = useMutation({
    mutationFn: () => {
      if (!testCase || !projectId) return Promise.reject(new Error("missing context"))
      return confidenceCheck(projectId, testCase.endpointId, testCase.id)
    },
    onSuccess: (data) => {
      setResult(data)
      setConfirmOpen(false)
    },
  })

  // Reset kết quả đối chiếu cũ khi đổi sang xem case khác - tránh hiện nhầm kết quả của case trước.
  useEffect(() => {
    setResult(null)
    mutation.reset()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [testCase?.id])

  if (!testCase) return null
  const reliability = getReliabilityDisplay(testCase)

  return (
    <>
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{testCase.name}</DialogTitle>
          <DialogDescription>
            {testCase.endpointMethod} {testCase.endpointPath}
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4">
          {testCase.probeKind === "MASS_ASSIGNMENT" && (
            <div role="note" className="rounded-md border border-destructive/30 bg-destructive/5 p-3 text-xs">
              <p className="font-medium text-destructive">Probe bảo mật - mass assignment</p>
              <p className="mt-1">
                Field được tiêm (hệ thống sinh, không nên sửa): {(testCase.probeFields ?? []).map((f) => <code key={f} className="mr-1">{f}</code>)}
              </p>
              <p className="mt-1 text-muted-foreground">
                Đạt = API bỏ qua hoặc từ chối (400/422). Fail = API nhận và trả lại field, hoặc lỗi 5xx. Test này gửi request GHI thật.
              </p>
            </div>
          )}
          {expectationChanged(testCase) && (
            <div role="note" className="rounded-md border border-amber-500/30 bg-amber-500/5 p-3 text-xs">
              <p className="font-medium text-amber-700 dark:text-amber-400">Kỳ vọng đã được hệ thống đổi</p>
              {testCase.expectationNote && <p className="mt-1">{testCase.expectationNote}</p>}
              <p className="mt-1 text-muted-foreground">
                Test gốc chỉ dùng làm nền nên được chỉnh để Pass; chênh lệch so với spec được ghi thành cảnh báo ở kết quả chạy.
              </p>
            </div>
          )}
          <div className="flex flex-wrap items-center gap-2 text-xs">
            <span className="rounded-full bg-muted px-2 py-0.5 font-medium">
              {SOURCE_LABEL[testCase.source]}
            </span>
            {testCase.probeKind === "MASS_ASSIGNMENT" && (
              <span className="rounded-full border border-destructive/40 px-2 py-0.5 font-medium text-destructive">Probe bảo mật</span>
            )}
            <span className="rounded-full bg-muted px-2 py-0.5 font-medium">
              Kỳ vọng {testCase.expectedStatus}
              {expectationChanged(testCase) && ` (đã đổi từ ${testCase.originalExpectedStatus})`}
            </span>
            {testCase.locked && (
              <span className="rounded-full bg-amber-500/10 px-2 py-0.5 font-medium text-amber-600 dark:text-amber-400">
                Đang khoá
              </span>
            )}
            {reliability && (
              <span
                title={reliability.title}
                className={cn(
                  "rounded-full px-2 py-0.5 font-medium",
                  reliability.tone === "reliable"
                    ? "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400"
                    : "bg-amber-500/10 text-amber-600 dark:text-amber-400"
                )}
              >
                {reliability.label}
              </span>
            )}
          </div>

          {testCase.description && (
            <Field label="Mô tả">
              <p className="text-sm">{testCase.description}</p>
            </Field>
          )}

          {testCase.generationOrigin && (
            <Field label="Nguồn gốc (checklist EP/BVA)">
              <p className="text-sm">
                <span
                  className={cn(
                    "mr-2 rounded-full px-2 py-0.5 text-xs font-medium",
                    originDisplay(testCase.generationOrigin).className
                  )}
                >
                  {originDisplay(testCase.generationOrigin).label}
                </span>
                <span className="text-muted-foreground">{originDisplay(testCase.generationOrigin).title}</span>
              </p>
              {testCase.checklistItem && (
                <p className="mt-1 text-sm text-muted-foreground">Mục checklist: {testCase.checklistItem}</p>
              )}
            </Field>
          )}

          {testCase.source !== "MANUAL" && testCase.rationale && (
            <Field
              label={
                testCase.generationOrigin === "SYSTEM_FILLED"
                  ? "Vì sao hệ thống tạo case này (viết từ spec, kiểm chứng được)"
                  : "Vì sao AI sinh case này"
              }
            >
              <p className="text-sm text-muted-foreground">{testCase.rationale}</p>
            </Field>
          )}

          {testCase.reliabilityStatus === "NEEDS_REVIEW" && testCase.generationOrigin !== "SYSTEM_FILLED" && testCase.reliabilityNotes.length > 0 && (
            <Field label="Kỳ vọng cần xem vì (đối chiếu tất định với spec)">
              <ul className="flex flex-col gap-1 text-sm text-amber-600 dark:text-amber-400">
                {testCase.reliabilityNotes.map((note, index) => (
                  <li key={index}>• {note}</li>
                ))}
              </ul>
            </Field>
          )}

          {/* Module 15e (Tier 3) - chỉ hiện khi có projectId (luồng chính chủ, không phải Admin xem hộ). */}
          {projectId && testCase.source !== "MANUAL" && (
            <Field label="Đối chiếu độ tin cậy sâu hơn">
              {!result ? (
                <Button
                  size="sm"
                  variant="outline"
                  disabled={mutation.isPending}
                  onClick={() => setConfirmOpen(true)}
                >
                  {mutation.isPending ? (
                    <Loader2 className="h-3.5 w-3.5 animate-spin" />
                  ) : (
                    <Sparkles className="h-3.5 w-3.5" />
                  )}
                  Đối chiếu độ tin cậy sâu hơn bằng AI (tốn thêm token)
                </Button>
              ) : (
                <div className="flex flex-col gap-2 rounded-md border border-border p-3 text-sm">
                  <span
                    className={cn(
                      "w-fit rounded-full px-2 py-0.5 text-xs font-medium",
                      AGREEMENT_STYLES[result.agreementLevel]
                    )}
                  >
                    {AGREEMENT_LABEL[result.agreementLevel]}
                  </span>
                  <p className="text-xs text-muted-foreground">
                    AI độc lập tự xây dựng lại (không xem bản đã lưu): kỳ vọng{" "}
                    {result.regeneratedExpectedStatus ?? "(không rõ)"}
                    {result.expectedStatusMatches ? " (khớp)" : " (khác bản đã lưu)"}; request body{" "}
                    {result.requestBodyShapeMatches ? "cùng tập field (khớp)" : "khác tập field so với bản đã lưu"}.
                  </p>
                  {result.note && <p className="text-xs text-muted-foreground">Ghi chú AI: {result.note}</p>}
                  {mutation.isError && (
                    <p className="text-xs text-destructive">
                      {mutation.error instanceof ApiError ? mutation.error.message : "Đã xảy ra lỗi, vui lòng thử lại"}
                    </p>
                  )}
                </div>
              )}
              {mutation.isError && !result && (
                <p className="mt-1 text-xs text-destructive">
                  {mutation.error instanceof ApiError ? mutation.error.message : "Đã xảy ra lỗi, vui lòng thử lại"}
                </p>
              )}
            </Field>
          )}

          <Field label="Xác thực khi gọi target API">
            <p className="text-sm">{AUTH_OVERRIDE_LABEL[testCase.authOverride]}</p>
          </Field>

          {testCase.resolvedPath && (
            <Field label="Đường dẫn thực thi">
              <code className="block rounded-md bg-muted px-2 py-1.5 text-xs">{testCase.resolvedPath}</code>
            </Field>
          )}

          {testCase.pathParamFallbacks && (
            <Field label="Giá trị dự phòng cho tham số path/query">
              <CodeBlock value={testCase.pathParamFallbacks} />
            </Field>
          )}

          {testCase.requestHeaders && (
            <Field label="Request Headers">
              <CodeBlock value={testCase.requestHeaders} />
            </Field>
          )}

          {testCase.requestBody && (
            <Field label="Request Body">
              <CodeBlock value={testCase.requestBody} />
            </Field>
          )}

          {testCase.dependencies.length > 0 && (
            <Field label="Phụ thuộc dữ liệu (Test Data Chaining)">
              <ul className="flex flex-col gap-1.5">
                {testCase.dependencies.map((dep) => (
                  <li
                    key={dep.placeholderName}
                    className="flex items-center gap-2 rounded-md border border-border p-2 text-xs"
                  >
                    <code className="shrink-0 font-semibold">{`{{${dep.placeholderName}}}`}</code>
                    <span className="min-w-0 flex-1 truncate text-muted-foreground">
                      ← {dep.dependsOnTestCaseName} ({dep.jsonPath})
                    </span>
                  </li>
                ))}
              </ul>
            </Field>
          )}

          {testCase.assertions.length > 0 && (
            <Field label="Assertion">
              <ul className="flex flex-col gap-1.5">
                {testCase.assertions.map((a, index) => (
                  <li
                    key={`${a.jsonPath}-${a.operator}-${index}`}
                    className="flex items-center gap-2 rounded-md border border-border p-2 text-xs"
                  >
                    <code className="shrink-0 font-semibold">{a.jsonPath}</code>
                    <span className="min-w-0 flex-1 truncate text-muted-foreground">
                      {ASSERTION_OPERATOR_LABEL[a.operator]}
                      {a.operator !== "EXISTS" && a.expectedValue ? ` — "${a.expectedValue}"` : ""}
                    </span>
                  </li>
                ))}
              </ul>
            </Field>
          )}
        </div>
      </DialogContent>
    </Dialog>

    {/* Module 15e - cảnh báo tốn token thật TRƯỚC khi gọi, không tự động. */}
    <AlertDialog open={confirmOpen} onOpenChange={setConfirmOpen}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Đối chiếu độ tin cậy sâu hơn bằng AI?</AlertDialogTitle>
          <AlertDialogDescription>
            Hành động này gọi thêm 1 lần AI thật, tốn thêm token thật ngoài lượt sinh test case ban đầu. AI sẽ độc
            lập tự xây dựng lại kịch bản này (chỉ dựa vào tên/mô tả, không xem dữ liệu đã lưu) để so sánh mức đồng
            thuận. Kết quả không ghi đè độ tin cậy đã có ở trên.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={mutation.isPending}>Huỷ</AlertDialogCancel>
          <AlertDialogAction disabled={mutation.isPending} onClick={() => mutation.mutate()}>
            {mutation.isPending ? "Đang gọi AI..." : "Tiếp tục (tốn token)"}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
    </>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <span className="text-xs font-medium text-muted-foreground">{label}</span>
      {children}
    </div>
  )
}

function CodeBlock({ value }: { value: string }) {
  return (
    <pre className={cn("max-h-60 overflow-auto rounded-md bg-muted p-2 text-xs whitespace-pre-wrap break-all")}>
      {value}
    </pre>
  )
}
