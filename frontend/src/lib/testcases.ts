import { apiFetch } from "@/lib/api"

export type TestCaseSource = "AI_GENERATED" | "MANUAL" | "SECURITY"

export type TestCaseAuthOverride = "DEFAULT" | "NONE" | "INVALID"

/**
 * Module 15h - nguồn gốc case AI sinh (15i: cả nhóm Security), do hệ thống CHẤM NỘI DUNG (không dựa lời AI tự khai):
 * AI_CHECKLIST = AI sinh, đạt mục checklist; AI_EXTRA = AI đề xuất thêm ngoài checklist;
 * SYSTEM_FILLED = hệ thống tự bù mục AI làm thiếu/sai.
 */
export type TestCaseGenerationOrigin = "AI_CHECKLIST" | "AI_EXTRA" | "SYSTEM_FILLED"

export const GENERATION_ORIGIN_DISPLAY: Record<TestCaseGenerationOrigin, { label: string; className: string; title: string }> = {
  AI_CHECKLIST: {
    label: "AI · theo checklist",
    className: "bg-sky-500/10 text-sky-600 dark:text-sky-400",
    title: "AI sinh và nội dung thật sự đạt 1 mục checklist EP/BVA tính từ spec",
  },
  AI_EXTRA: {
    label: "AI đề xuất thêm",
    className: "bg-fuchsia-500/10 text-fuchsia-600 dark:text-fuchsia-400",
    title: "AI tự đề xuất ngoài checklist bắt buộc (sai kiểu, null, tổ hợp, nghiệp vụ, dữ liệu đặc biệt)",
  },
  SYSTEM_FILLED: {
    label: "Hệ thống bổ sung",
    className: "bg-teal-500/10 text-teal-600 dark:text-teal-400",
    title: "AI làm thiếu/sai mục checklist này, hệ thống tự tạo từ 1 case hợp lệ AI đã sinh (sửa đúng 1 chỗ)",
  },
}

export type ChecklistItemStatus = "ALREADY_COVERED" | "AI_SATISFIED" | "SYSTEM_FILLED" | "NOT_FILLABLE" | "NOT_APPLICABLE"

export interface ChecklistItemResponse {
  type: string
  field: string | null
  description: string
  status: ChecklistItemStatus
  reason: string | null
  /** Tên các test case đạt mục này (1 mục có thể có nhiều case). */
  coveredBy: string[]
}

/** Module 15h - báo cáo 1 lần sinh; 15i: gộp cả nhóm Security, checklist chia loại theo tiền tố của type. */
export interface GenerationPlanSummary {
  checklistTotal: number
  alreadyCoveredCount: number
  aiAskedCount: number
  aiSatisfiedCount: number
  systemFilledCount: number
  notFillableCount: number
  notApplicableCount: number
  aiExtraCount: number
  /** Số case thuộc checklist (AI làm đạt + hệ thống bù) lúc sinh. */
  checklistCaseCount: number
  /** Tên các case AI đề xuất thêm lúc sinh - chụp lại để xem được sau khi tải lại trang. */
  extraCaseNames: string[]
  items: ChecklistItemResponse[]
}

export interface GenerateTestCasesResponse {
  testCases: TestCase[]
  /** Từ 15i luôn có (cả khi chỉ sinh Security); kiểu vẫn cho null để an toàn với phản hồi cũ. */
  planSummary: GenerationPlanSummary | null
}

/** Module 15b (Tier 1) - đối chiếu tất định với spec, chỉ áp dụng cho case kỳ vọng thành công. */
export type TestCaseReliability = "RELIABLE" | "NEEDS_REVIEW"

/** Module 15e (Tier 3) - mức đồng thuận giữa bản đã lưu và bản AI độc lập tự xây dựng lại. */
export type ConfidenceAgreementLevel = "HIGH" | "PARTIAL" | "LOW"

export interface ConfidenceCheckResponse {
  agreementLevel: ConfidenceAgreementLevel
  expectedStatusMatches: boolean
  requestBodyShapeMatches: boolean
  regeneratedExpectedStatus: number | null
  regeneratedRequestBody: string | null
  note: string | null
}

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
  /** Module 17 (3.1) - profile xác thực (vai trò) test case dùng; null = token mặc định của project. */
  authProfileId: string | null
  /** Module 15h - null cho case Security/MANUAL/dữ liệu tạo trước 15h. */
  generationOrigin: TestCaseGenerationOrigin | null
  /** Module 15h - mô tả mục checklist EP/BVA mà case này đạt (hệ thống chấm theo nội dung). */
  checklistItem: string | null
  locked: boolean
  createdAt: string
  dependencies: TestCaseDependency[]
  assertions: TestCaseAssertion[]
  /** Module 17 (17b-2) - "MASS_ASSIGNMENT" cho test probe bảo mật tự sinh; probeFields = tên field đã tiêm. */
  probeKind?: string | null
  probeFields?: string[]
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
  /** null = token mặc định của project. */
  authProfileId?: string | null
  dependencies?: TestCaseDependencyInput[]
  assertions?: TestCaseAssertionInput[]
}

export interface GenerateTestCasesOptions {
  includeSecurity?: boolean
  includePositive?: boolean
  includeNegative?: boolean
  includeBoundary?: boolean
}

/** Báo cáo checklist của lần sinh nhóm Basic gần nhất - null nếu chưa có (Module 15h). */
export function getLatestGenerationPlan(projectId: string, endpointId: string) {
  return apiFetch<GenerationPlanSummary | null>(`/projects/${projectId}/endpoints/${endpointId}/generation-plan`)
}

export function generateTestCases(
  projectId: string,
  endpointId: string,
  options?: GenerateTestCasesOptions
): Promise<GenerateTestCasesResponse> {
  return apiFetch<GenerateTestCasesResponse>(`/projects/${projectId}/endpoints/${endpointId}/generate-tests`, {
    method: "POST",
    body: JSON.stringify({
      includeSecurity: options?.includeSecurity ?? false,
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

/** Module 15e (Tier 3) - người dùng tự bấm, tốn thêm token thật. KHÔNG tự động gọi ở đâu khác. */
export function confidenceCheck(
  projectId: string,
  endpointId: string,
  testCaseId: string
): Promise<ConfidenceCheckResponse> {
  return apiFetch<ConfidenceCheckResponse>(
    `/projects/${projectId}/endpoints/${endpointId}/test-cases/${testCaseId}/confidence-check`,
    { method: "POST" }
  )
}
