import { useEffect, useMemo, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Trash2 } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { ApiError } from "@/lib/api"
import {
  createTestCase,
  getDependencySuggestions,
  listTestCases,
  updateTestCase,
  type AssertionOperator,
  type TestCase,
  type TestCaseAssertionInput,
  type TestCaseAuthOverride,
  type TestCaseDependencyInput,
} from "@/lib/testcases"

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

interface TestCaseFormDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: string
  endpointId: string
  /** Path gốc của endpoint (có thể còn {tenThamSo} dạng OpenAPI) - dùng prefill resolvedPath khi tạo mới. */
  endpointPath: string
  testCase?: TestCase | null
}

interface LocalDependency extends TestCaseDependencyInput {
  /** Chỉ để hiển thị trong danh sách, không gửi lên backend. */
  label: string
}

const PLACEHOLDER_TOKEN = /\{\{(\w+)\}\}/g

function extractPlaceholders(text: string): string[] {
  const names = new Set<string>()
  for (const match of text.matchAll(PLACEHOLDER_TOKEN)) {
    names.add(match[1])
  }
  return Array.from(names)
}

function wouldCreateCycle(
  allTestCases: TestCase[],
  currentId: string,
  candidateSourceId: string,
  localDeps: LocalDependency[]
): boolean {
  const graph = new Map<string, string[]>()
  for (const tc of allTestCases) {
    graph.set(tc.id, tc.dependencies.map((d) => d.dependsOnTestCaseId))
  }
  graph.set(currentId, [...localDeps.map((d) => d.dependsOnTestCaseId), candidateSourceId])

  const visited = new Set<string>()
  function dfs(id: string): boolean {
    if (visited.has(id)) return false
    visited.add(id)
    for (const dep of graph.get(id) ?? []) {
      if (dep === currentId) return true
      if (dfs(dep)) return true
    }
    return false
  }
  return dfs(currentId)
}

export function TestCaseFormDialog({
  open,
  onOpenChange,
  projectId,
  endpointId,
  endpointPath,
  testCase,
}: TestCaseFormDialogProps) {
  const queryClient = useQueryClient()
  const isEdit = Boolean(testCase)
  const [name, setName] = useState("")
  const [description, setDescription] = useState("")
  const [requestHeaders, setRequestHeaders] = useState("")
  const [requestBody, setRequestBody] = useState("")
  const [expectedStatus, setExpectedStatus] = useState("200")
  const [authOverride, setAuthOverride] = useState<TestCaseAuthOverride>("DEFAULT")
  const [resolvedPath, setResolvedPath] = useState("")
  const [pathParamFallbacks, setPathParamFallbacks] = useState("")
  const [dependencies, setDependencies] = useState<LocalDependency[]>([])
  const [depFormError, setDepFormError] = useState<string | null>(null)

  const [newDepSourceId, setNewDepSourceId] = useState("")
  const [newDepJsonPath, setNewDepJsonPath] = useState("$.id")
  const [newDepPlaceholder, setNewDepPlaceholder] = useState("")

  const [assertions, setAssertions] = useState<TestCaseAssertionInput[]>([])
  const [assertFormError, setAssertFormError] = useState<string | null>(null)
  const [newAssertJsonPath, setNewAssertJsonPath] = useState("")
  const [newAssertOperator, setNewAssertOperator] = useState<AssertionOperator>("EQUALS")
  const [newAssertExpectedValue, setNewAssertExpectedValue] = useState("")

  useEffect(() => {
    if (open) {
      setName(testCase?.name ?? "")
      setDescription(testCase?.description ?? "")
      setRequestHeaders(testCase?.requestHeaders ?? "")
      setRequestBody(testCase?.requestBody ?? "")
      setExpectedStatus(testCase ? String(testCase.expectedStatus) : "200")
      setAuthOverride(testCase?.authOverride ?? "DEFAULT")
      setResolvedPath(testCase?.resolvedPath ?? endpointPath)
      setPathParamFallbacks(testCase?.pathParamFallbacks ?? "")
      setDependencies(
        (testCase?.dependencies ?? []).map((d) => ({
          dependsOnTestCaseId: d.dependsOnTestCaseId,
          jsonPath: d.jsonPath,
          placeholderName: d.placeholderName,
          label: d.dependsOnTestCaseName,
        }))
      )
      setNewDepSourceId("")
      setNewDepJsonPath("$.id")
      setNewDepPlaceholder("")
      setDepFormError(null)
      setAssertions(
        (testCase?.assertions ?? []).map((a) => ({
          jsonPath: a.jsonPath,
          operator: a.operator,
          expectedValue: a.expectedValue,
        }))
      )
      setNewAssertJsonPath("")
      setNewAssertOperator("EQUALS")
      setNewAssertExpectedValue("")
      setAssertFormError(null)
    }
  }, [open, testCase, endpointPath])

  // Toàn bộ test case trong project - dùng làm danh sách chọn nguồn (không giới hạn theo endpoint
  // hiện tại) và để chặn phụ thuộc vòng phía client.
  const { data: allTestCases } = useQuery({
    queryKey: ["test-cases", projectId],
    queryFn: () => listTestCases(projectId),
    enabled: open,
  })
  const candidateSources = (allTestCases ?? []).filter((tc) => tc.id !== testCase?.id)

  // Gợi ý tự động chỉ có ý nghĩa khi sửa (đã có resolvedPath để phân tích) - không gọi lúc tạo mới.
  const { data: suggestions } = useQuery({
    queryKey: ["dependency-suggestions", projectId, endpointId, testCase?.id],
    queryFn: () => getDependencySuggestions(projectId, endpointId, testCase!.id),
    enabled: open && isEdit,
  })
  const coveredPlaceholders = new Set(dependencies.map((d) => d.placeholderName))
  const visibleSuggestions = (suggestions ?? []).filter((s) => !coveredPlaceholders.has(s.paramName))

  const availablePlaceholders = useMemo(
    () => [
      ...extractPlaceholders(resolvedPath),
      ...extractPlaceholders(requestBody),
      ...extractPlaceholders(requestHeaders),
    ],
    [resolvedPath, requestBody, requestHeaders]
  )

  const mutation = useMutation({
    mutationFn: () => {
      const input = {
        name,
        description,
        requestHeaders,
        requestBody,
        expectedStatus: Number(expectedStatus),
        authOverride,
        resolvedPath,
        pathParamFallbacks,
        dependencies: dependencies.map(({ dependsOnTestCaseId, jsonPath, placeholderName }) => ({
          dependsOnTestCaseId,
          jsonPath,
          placeholderName,
        })),
        assertions,
      }
      return testCase
        ? updateTestCase(projectId, endpointId, testCase.id, input)
        : createTestCase(projectId, endpointId, input)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["test-cases", projectId] })
      queryClient.invalidateQueries({ queryKey: ["endpoints", projectId] })
      onOpenChange(false)
    },
  })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    mutation.mutate()
  }

  function applySuggestion(paramName: string, sourceTestCaseId: string, sourceLabel: string, jsonPath: string) {
    setDependencies((prev) => {
      // Chặn double-click/áp dụng lại 1 gợi ý đã có - server chỉ cho phép đúng 1 dependency mỗi placeholder.
      if (prev.some((d) => d.placeholderName === paramName)) {
        return prev
      }
      return [...prev, { dependsOnTestCaseId: sourceTestCaseId, jsonPath, placeholderName: paramName, label: sourceLabel }]
    })
  }

  function addManualDependency() {
    setDepFormError(null)
    if (!newDepSourceId) {
      setDepFormError("Vui lòng chọn Test case nguồn.")
      return
    }
    if (!newDepJsonPath.trim() || !newDepPlaceholder.trim()) {
      setDepFormError("Vui lòng nhập đủ JSONPath và Placeholder.")
      return
    }
    const placeholderName = newDepPlaceholder.trim()
    if (!availablePlaceholders.includes(placeholderName)) {
      setDepFormError(
        availablePlaceholders.length > 0
          ? `Placeholder "${placeholderName}" không khớp {{...}} nào trong resolvedPath/body/headers - dùng đúng tên: ${availablePlaceholders.join(", ")}.`
          : `Placeholder "${placeholderName}" không khớp {{...}} nào trong resolvedPath/body/headers.`
      )
      return
    }
    if (dependencies.some((d) => d.placeholderName === placeholderName)) {
      setDepFormError(`Placeholder "${placeholderName}" đã có phụ thuộc gán sẵn - xoá dòng cũ trước nếu muốn đổi nguồn khác.`)
      return
    }
    const currentId = testCase?.id ?? "__new__"
    if (wouldCreateCycle(allTestCases ?? [], currentId, newDepSourceId, dependencies)) {
      setDepFormError("Không thể thêm - sẽ tạo ra phụ thuộc vòng giữa các test case.")
      return
    }
    const source = candidateSources.find((tc) => tc.id === newDepSourceId)
    setDependencies((prev) => [
      ...prev,
      {
        dependsOnTestCaseId: newDepSourceId,
        jsonPath: newDepJsonPath.trim(),
        placeholderName: newDepPlaceholder.trim(),
        label: source ? `${source.endpointMethod} ${source.endpointPath} — ${source.name}` : newDepSourceId,
      },
    ])
    setNewDepSourceId("")
    setNewDepJsonPath("$.id")
    setNewDepPlaceholder("")
  }

  function removeDependency(placeholderName: string) {
    setDependencies((prev) => prev.filter((d) => d.placeholderName !== placeholderName))
  }

  function addAssertion() {
    setAssertFormError(null)
    if (!newAssertJsonPath.trim()) {
      setAssertFormError("Vui lòng nhập JSONPath.")
      return
    }
    if (newAssertOperator !== "EXISTS" && !newAssertExpectedValue.trim()) {
      setAssertFormError("Operator này bắt buộc phải có giá trị kỳ vọng (trừ EXISTS).")
      return
    }
    setAssertions((prev) => [
      ...prev,
      {
        jsonPath: newAssertJsonPath.trim(),
        operator: newAssertOperator,
        expectedValue: newAssertOperator === "EXISTS" ? null : newAssertExpectedValue.trim(),
      },
    ])
    setNewAssertJsonPath("")
    setNewAssertOperator("EQUALS")
    setNewAssertExpectedValue("")
  }

  function removeAssertion(index: number) {
    setAssertions((prev) => prev.filter((_, i) => i !== index))
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-2xl">
        <form onSubmit={handleSubmit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{isEdit ? "Sửa Test Case" : "Thêm Test Case"}</DialogTitle>
            <DialogDescription>
              {isEdit
                ? "Cập nhật thông tin test case"
                : "Nhập thông tin test case mới cho endpoint này"}
            </DialogDescription>
          </DialogHeader>

          <div className="flex flex-col gap-2">
            <Label htmlFor="test-case-name">Tên</Label>
            <Input
              id="test-case-name"
              required
              maxLength={255}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="test-case-description">Mô tả</Label>
            <Textarea
              id="test-case-description"
              maxLength={2000}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="test-case-expected-status">Mã trạng thái kỳ vọng</Label>
            <Input
              id="test-case-expected-status"
              type="number"
              required
              min={100}
              max={599}
              value={expectedStatus}
              onChange={(e) => setExpectedStatus(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="test-case-auth-override">Xác thực khi gọi target API</Label>
            <p className="text-xs text-muted-foreground">
              Chỉ cần đổi khi test case này cố ý kiểm tra thiếu/sai auth (nhóm Security) - bình
              thường để mặc định.
            </p>
            <select
              id="test-case-auth-override"
              className="h-8 w-full min-w-0 rounded-lg border border-input bg-transparent px-2.5 text-sm outline-none dark:bg-input/30"
              value={authOverride}
              onChange={(e) => setAuthOverride(e.target.value as TestCaseAuthOverride)}
            >
              {(Object.keys(AUTH_OVERRIDE_LABEL) as TestCaseAuthOverride[]).map((value) => (
                <option key={value} value={value}>
                  {AUTH_OVERRIDE_LABEL[value]}
                </option>
              ))}
            </select>
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="test-case-resolved-path">Đường dẫn thực thi (resolvedPath)</Label>
            <p className="text-xs text-muted-foreground">
              Dùng <code>{"{{tenThamSo}}"}</code> cho tham số cần giá trị thật lúc chạy (vd{" "}
              <code>/pet/{"{{petId}}"}</code>), hoặc giá trị cụ thể nếu test này cố ý dùng ID
              sai/biên. Tham số query cũng dùng chung cú pháp này, gắn thẳng vào cuối path, vd{" "}
              <code>{"/pet/{{petId}}?name={{name}}"}</code>.
            </p>
            <Input
              id="test-case-resolved-path"
              value={resolvedPath}
              onChange={(e) => setResolvedPath(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="test-case-fallbacks">Giá trị dự phòng cho tham số path/query (JSON, tuỳ chọn)</Label>
            <p className="text-xs text-muted-foreground">
              Dùng khi chưa gán dữ liệu thật (Test Data Chaining), vd <code>{'{"petId": "1"}'}</code>.
            </p>
            <Textarea
              id="test-case-fallbacks"
              placeholder='{"petId": "1"}'
              value={pathParamFallbacks}
              onChange={(e) => setPathParamFallbacks(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="test-case-headers">Request Headers (JSON, tuỳ chọn)</Label>
            <Textarea
              id="test-case-headers"
              placeholder='{"Content-Type": "application/json"}'
              value={requestHeaders}
              onChange={(e) => setRequestHeaders(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="test-case-body">Request Body (JSON, tuỳ chọn)</Label>
            <Textarea
              id="test-case-body"
              value={requestBody}
              onChange={(e) => setRequestBody(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-2 rounded-lg border border-border p-3">
            <Label>Phụ thuộc dữ liệu (Test Data Chaining)</Label>
            <p className="text-xs text-muted-foreground">
              Lấy giá trị thật từ response của 1 test case khác thay vì giá trị dự phòng. Chỉ áp
              dụng cho tham số dạng <code>{"{{tenThamSo}}"}</code>
              {availablePlaceholders.length > 0 && (
                <> — hiện có: {availablePlaceholders.map((p) => <code key={p} className="ml-1">{p}</code>)}</>
              )}
              .
            </p>

            {visibleSuggestions.map((s) => (
              <div
                key={s.paramName}
                className="flex flex-wrap items-center gap-2 rounded-md bg-muted/50 p-2 text-xs"
              >
                <span className="min-w-0 flex-1">
                  Phát hiện <code>{s.sourceLabel}</code> có thể dùng làm nguồn dữ liệu thật cho{" "}
                  <code>{s.paramName}</code>.
                </span>
                <Button
                  type="button"
                  size="xs"
                  variant="outline"
                  onClick={() => applySuggestion(s.paramName, s.sourceTestCaseId, s.sourceLabel, s.suggestedJsonPath)}
                >
                  Áp dụng
                </Button>
              </div>
            ))}

            {dependencies.length > 0 && (
              <ul className="flex flex-col gap-1.5">
                {dependencies.map((dep) => (
                  <li
                    key={dep.placeholderName}
                    className="flex items-center gap-2 rounded-md border border-border p-2 text-xs"
                  >
                    <code className="shrink-0 font-semibold">{`{{${dep.placeholderName}}}`}</code>
                    <span className="min-w-0 flex-1 truncate text-muted-foreground">
                      ← {dep.label} ({dep.jsonPath})
                    </span>
                    <Button
                      type="button"
                      size="icon-xs"
                      variant="ghost"
                      onClick={() => removeDependency(dep.placeholderName)}
                    >
                      <Trash2 className="h-3 w-3" />
                    </Button>
                  </li>
                ))}
              </ul>
            )}

            {candidateSources.length === 0 ? (
              <p className="text-xs text-muted-foreground">
                Chưa có test case nào khác trong project để chọn làm nguồn - tạo test case cho
                endpoint nguồn trước.
              </p>
            ) : (
              <div className="flex flex-col gap-1.5 sm:flex-row sm:items-end">
                <div className="flex min-w-0 flex-1 flex-col gap-1">
                  <Label htmlFor="dep-source" className="text-xs">Test case nguồn</Label>
                  <select
                    id="dep-source"
                    className="h-8 w-full min-w-0 rounded-lg border border-input bg-transparent px-2.5 text-xs outline-none dark:bg-input/30"
                    value={newDepSourceId}
                    onChange={(e) => setNewDepSourceId(e.target.value)}
                  >
                    <option value="">Chọn...</option>
                    {candidateSources.map((tc) => (
                      <option key={tc.id} value={tc.id}>
                        {tc.endpointMethod} {tc.endpointPath} — {tc.name}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="flex w-24 flex-col gap-1">
                  <Label htmlFor="dep-jsonpath" className="text-xs">JSONPath</Label>
                  <Input
                    id="dep-jsonpath"
                    className="h-8 text-xs"
                    value={newDepJsonPath}
                    onChange={(e) => setNewDepJsonPath(e.target.value)}
                  />
                </div>
                <div className="flex w-28 flex-col gap-1">
                  <Label htmlFor="dep-placeholder" className="text-xs">Tên placeholder</Label>
                  <select
                    id="dep-placeholder"
                    className="h-8 w-full min-w-0 rounded-lg border border-input bg-transparent px-2.5 text-xs outline-none dark:bg-input/30"
                    value={newDepPlaceholder}
                    onChange={(e) => setNewDepPlaceholder(e.target.value)}
                  >
                    <option value="">Chọn...</option>
                    {availablePlaceholders.map((p) => (
                      <option key={p} value={p}>{p}</option>
                    ))}
                  </select>
                </div>
                <Button type="button" size="sm" variant="outline" onClick={addManualDependency}>
                  Thêm
                </Button>
              </div>
            )}

            {depFormError && <p className="text-xs text-destructive">{depFormError}</p>}
          </div>

          <div className="flex flex-col gap-2 rounded-lg border border-border p-3">
            <Label>Assertion</Label>
            <p className="text-xs text-muted-foreground">
              Kiểm tra 1 field cụ thể trong response sau khi gọi target API - status PASSED chỉ khi
              status code lẫn mọi assertion đều đúng.
            </p>

            {assertions.length > 0 && (
              <ul className="flex flex-col gap-1.5">
                {assertions.map((a, index) => (
                  <li
                    key={`${a.jsonPath}-${a.operator}-${index}`}
                    className="flex items-center gap-2 rounded-md border border-border p-2 text-xs"
                  >
                    <code className="shrink-0 font-semibold">{a.jsonPath}</code>
                    <span className="min-w-0 flex-1 truncate text-muted-foreground">
                      {ASSERTION_OPERATOR_LABEL[a.operator]}
                      {a.operator !== "EXISTS" && a.expectedValue ? ` — "${a.expectedValue}"` : ""}
                    </span>
                    <Button
                      type="button"
                      size="icon-xs"
                      variant="ghost"
                      onClick={() => removeAssertion(index)}
                    >
                      <Trash2 className="h-3 w-3" />
                    </Button>
                  </li>
                ))}
              </ul>
            )}

            <div className="flex flex-col gap-1.5 sm:flex-row sm:items-end">
              <div className="flex w-28 flex-col gap-1">
                <Label htmlFor="assert-jsonpath" className="text-xs">JSONPath</Label>
                <Input
                  id="assert-jsonpath"
                  className="h-8 text-xs"
                  placeholder="price"
                  value={newAssertJsonPath}
                  onChange={(e) => setNewAssertJsonPath(e.target.value)}
                />
              </div>
              <div className="flex min-w-0 flex-1 flex-col gap-1">
                <Label htmlFor="assert-operator" className="text-xs">Operator</Label>
                <select
                  id="assert-operator"
                  className="h-8 w-full min-w-0 rounded-lg border border-input bg-transparent px-2.5 text-xs outline-none dark:bg-input/30"
                  value={newAssertOperator}
                  onChange={(e) => setNewAssertOperator(e.target.value as AssertionOperator)}
                >
                  {(Object.keys(ASSERTION_OPERATOR_LABEL) as AssertionOperator[]).map((value) => (
                    <option key={value} value={value}>
                      {ASSERTION_OPERATOR_LABEL[value]}
                    </option>
                  ))}
                </select>
              </div>
              <div className="flex w-28 flex-col gap-1">
                <Label htmlFor="assert-expected" className="text-xs">Giá trị kỳ vọng</Label>
                <Input
                  id="assert-expected"
                  className="h-8 text-xs"
                  disabled={newAssertOperator === "EXISTS"}
                  value={newAssertExpectedValue}
                  onChange={(e) => setNewAssertExpectedValue(e.target.value)}
                />
              </div>
              <Button type="button" size="sm" variant="outline" onClick={addAssertion}>
                Thêm
              </Button>
            </div>

            {assertFormError && <p className="text-xs text-destructive">{assertFormError}</p>}
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
              {mutation.isPending
                ? "Đang lưu..."
                : isEdit
                  ? "Lưu thay đổi"
                  : "Thêm Test Case"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
