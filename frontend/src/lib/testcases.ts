import { apiFetch } from "@/lib/api"

export type TestCaseSource = "AI_GENERATED" | "MANUAL" | "SECURITY"

export type TestCaseAuthOverride = "DEFAULT" | "NONE" | "INVALID"

/** Module 15b (Tier 1) - đối chiếu tất định với spec, chỉ áp dụng cho case kỳ vọng thành công. */
export type TestCaseReliability = "RELIABLE" | "NEEDS_REVIEW"

export type AssertionOperator = "EQUALS" | "CONTAINS" | "EXISTS" | "TYPE"

export interface TestCaseAssertion {
  jsonPath: string
  operator: AssertionOperator
  expectedValue: string | null
}

export interface TestCaseAssertionInput {
  jsonPath: string
  operator: AssertionOperator
  expectedValue: string | null
}

export interface TestCaseDependency {
  dependsOnTestCaseId: string
  dependsOnTestCaseName: string
  jsonPath: string
  placeholderName: string
}

export interface TestCaseDependencyInput {
  dependsOnTestCaseId: string
  jsonPath: string
  placeholderName: string
}

export interface DependencySuggestion {
  paramName: string
  sourceTestCaseId: string
  sourceLabel: string
  suggestedJsonPath: string
}

export interface TestCase {
  id: string
  endpointId: string
  endpointPath: string
  endpointMethod: string
  name: string
  description: string | null
  requestHeaders: string | null
  requestBody: string | null
  /** Module 15a - câu giải thích AI tự khai lúc sinh case này, null cho case MANUAL/dữ liệu cũ. */
  rationale: string | null
  /** Module 15b - null = không áp dụng (case MANUAL, dữ liệu cũ, hoặc case kỳ vọng lỗi). */
  reliabilityStatus: TestCaseReliability | null
  reliabilityNotes: string[]
  /** Module 15d (Tier 2) - null = chưa chạy (không bị gắn cờ, hoặc hết quota). true = AI xác nhận lỗi thật; false = AI cho là false positive. */
  reliabilityAiConfirmed: boolean | null
  reliabilityAiNote: string | null
  expectedStatus: number
  resolvedPath: string | null
  pathParamFallbacks: string | null
  source: TestCaseSource
  authOverride: TestCaseAuthOverride
  locked: boolean
  createdAt: string
  dependencies: TestCaseDependency[]
  assertions: TestCaseAssertion[]
}

export interface ReliabilityDisplay {
  label: string
  tone: "reliable" | "needsReview"
  title: string
}

/**
 * Gộp logic hiển thị Tier 1 (15b) + Tier 2 (15d) ở 1 chỗ, dùng chung TestCasesPage/TestCaseDetailDialog.
 * - RELIABLE (Tier 1) -> "Đáng tin" xanh.
 * - NEEDS_REVIEW (Tier 1) + reliabilityAiConfirmed=true (Tier 2 xác nhận lỗi thật) -> vẫn "Cần xem lại" vàng, kèm note AI.
 * - NEEDS_REVIEW (Tier 1) + reliabilityAiConfirmed=false (Tier 2 cho là false positive) -> đổi sang "Đáng tin (AI xác minh)" xanh nhạt.
 * - NEEDS_REVIEW (Tier 1) + reliabilityAiConfirmed=null (Tier 2 chưa chạy) -> giữ nguyên "Cần xem lại" theo Tier 1.
 */
export function getReliabilityDisplay(testCase: TestCase): ReliabilityDisplay | null {
  if (!testCase.reliabilityStatus) {
    return null
  }
  if (testCase.reliabilityStatus === "RELIABLE") {
    return {
      label: "Đáng tin",
      tone: "reliable",
      title: "Giá trị AI sinh khớp đúng ràng buộc (required/enum/min-max) trong spec",
    }
  }
  if (testCase.reliabilityAiConfirmed === true) {
    return {
      label: "Cần xem lại",
      tone: "needsReview",
      title: `AI xác nhận: ${testCase.reliabilityAiNote ?? testCase.reliabilityNotes.join("; ")}`,
    }
  }
  if (testCase.reliabilityAiConfirmed === false) {
    return {
      label: "Đáng tin (AI xác minh)",
      tone: "reliable",
      title: testCase.reliabilityAiNote ?? "AI xác nhận đây là false positive",
    }
  }
  return { label: "Cần xem lại", tone: "needsReview", title: testCase.reliabilityNotes.join("; ") }
}

export interface TestCaseInput {
  name: string
  description?: string
  requestHeaders?: string
  requestBody?: string
  expectedStatus: number
  resolvedPath?: string
  pathParamFallbacks?: string
  authOverride?: TestCaseAuthOverride
  dependencies?: TestCaseDependencyInput[]
  assertions?: TestCaseAssertionInput[]
}

export interface GenerateTestCasesOptions {
  includeSecurity?: boolean
  includeAssertions?: boolean
  includePositive?: boolean
  includeNegative?: boolean
  includeBoundary?: boolean
}

export function generateTestCases(
  projectId: string,
  endpointId: string,
  options?: GenerateTestCasesOptions
): Promise<TestCase[]> {
  return apiFetch<TestCase[]>(`/projects/${projectId}/endpoints/${endpointId}/generate-tests`, {
    method: "POST",
    body: JSON.stringify({
      includeSecurity: options?.includeSecurity ?? false,
      includeAssertions: options?.includeAssertions ?? false,
      includePositive: options?.includePositive ?? false,
      includeNegative: options?.includeNegative ?? false,
      includeBoundary: options?.includeBoundary ?? false,
    }),
  })
}

export function setTestCaseLocked(
  projectId: string,
  endpointId: string,
  testCaseId: string,
  locked: boolean
): Promise<TestCase> {
  return apiFetch<TestCase>(`/projects/${projectId}/endpoints/${endpointId}/test-cases/${testCaseId}/lock`, {
    method: "PUT",
    body: JSON.stringify({ locked }),
  })
}

export function listTestCases(projectId: string): Promise<TestCase[]> {
  return apiFetch<TestCase[]>(`/projects/${projectId}/test-cases`)
}

export function createTestCase(
  projectId: string,
  endpointId: string,
  input: TestCaseInput
): Promise<TestCase> {
  return apiFetch<TestCase>(`/projects/${projectId}/endpoints/${endpointId}/test-cases`, {
    method: "POST",
    body: JSON.stringify(input),
  })
}

export function updateTestCase(
  projectId: string,
  endpointId: string,
  testCaseId: string,
  input: TestCaseInput
): Promise<TestCase> {
  return apiFetch<TestCase>(`/projects/${projectId}/endpoints/${endpointId}/test-cases/${testCaseId}`, {
    method: "PUT",
    body: JSON.stringify(input),
  })
}

export function deleteTestCase(
  projectId: string,
  endpointId: string,
  testCaseId: string
): Promise<void> {
  return apiFetch<void>(`/projects/${projectId}/endpoints/${endpointId}/test-cases/${testCaseId}`, {
    method: "DELETE",
  })
}

export function getDependencySuggestions(
  projectId: string,
  endpointId: string,
  testCaseId: string
): Promise<DependencySuggestion[]> {
  return apiFetch<DependencySuggestion[]>(
    `/projects/${projectId}/endpoints/${endpointId}/test-cases/${testCaseId}/dependency-suggestions`
  )
}
