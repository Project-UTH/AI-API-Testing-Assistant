import { cn } from "@/lib/utils"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import type { AssertionOperator, TestCase, TestCaseAuthOverride } from "@/lib/testcases"

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
}

interface TestCaseDetailDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  testCase: TestCase | null
}

/** Chỉ đọc - xem chi tiết 1 test case (dùng ở trang Admin xem dữ liệu user khác). Không có nút sửa/xoá. */
export function TestCaseDetailDialog({ open, onOpenChange, testCase }: TestCaseDetailDialogProps) {
  if (!testCase) return null

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{testCase.name}</DialogTitle>
          <DialogDescription>
            {testCase.endpointMethod} {testCase.endpointPath}
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4">
          <div className="flex flex-wrap items-center gap-2 text-xs">
            <span className="rounded-full bg-muted px-2 py-0.5 font-medium">
              {SOURCE_LABEL[testCase.source]}
            </span>
            <span className="rounded-full bg-muted px-2 py-0.5 font-medium">
              Kỳ vọng {testCase.expectedStatus}
            </span>
            {testCase.locked && (
              <span className="rounded-full bg-amber-500/10 px-2 py-0.5 font-medium text-amber-600 dark:text-amber-400">
                Đang khoá
              </span>
            )}
          </div>

          {testCase.description && (
            <Field label="Mô tả">
              <p className="text-sm">{testCase.description}</p>
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
