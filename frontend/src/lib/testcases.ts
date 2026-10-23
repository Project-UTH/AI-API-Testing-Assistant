import { apiFetch, apiFetchPaged, fetchAllPages, type PagedResult } from "@/lib/api"

export type TestCaseSource = "AI_GENERATED" | "MANUAL" | "SECURITY" | "BUSINESS"

export type TestCaseAuthOverride = "DEFAULT" | "NONE" | "INVALID"

export const TEST_CASE_SOURCE_LABEL: Record<TestCaseSource, string> = {
  AI_GENERATED: "AI",
  MANUAL: "Tự thêm",
  SECURITY: "Security",
  BUSINESS: "Nghiệp vụ (AI đoán)",
}

export const TEST_CASE_SOURCE_STYLES: Record<TestCaseSource, string> = {
  AI_GENERATED: "bg-violet-500/10 text-violet-600 dark:text-violet-400",
  MANUAL: "bg-slate-500/10 text-slate-600 dark:text-slate-400",
  SECURITY: "bg-rose-500/10 text-rose-600 dark:text-rose-400",
  BUSINESS: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
}

/**
 * Module 15h - nguồn gốc case AI sinh (15i: cả nhóm Security), do hệ thống CHẤM NỘI DUNG (không dựa lời AI tự khai):
 * AI_CHECKLIST = AI sinh, đạt mục checklist; AI_EXTRA = AI đề xuất thêm ngoài checklist;
 * SYSTEM_FILLED = hệ thống tự bù mục AI làm thiếu/sai.
 */
export type TestCaseGenerationOrigin = "AI_CHECKLIST" | "AI_EXTRA" | "SYSTEM_FILLED" | "AI_BUSINESS" | "AI_BASELINE" | "RULE_TEMPLATE"

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
  AI_BUSINESS: {
    label: "AI đoán nghiệp vụ",
    className: "bg-amber-500/10 text-amber-700 dark:text-amber-400",
    title: "Luật nghiệp vụ do AI đoán, không có trong spec. Là GIẢ ĐỊNH: cần bạn duyệt trước khi tin kết quả, không tính vào độ bao phủ",
  },
  AI_BASELINE: {
    label: "Test gốc",
    className: "bg-green-500/10 text-green-700 dark:text-green-400",
    title: "Test gốc hợp lệ do AI sinh và đã được chạy thật xác nhận Pass, dùng làm nền để hệ thống bổ sung checklist",
  },
  RULE_TEMPLATE: {
    label: "Từ luật nghiệp vụ",
    className: "bg-indigo-500/10 text-indigo-700 dark:text-indigo-400",
    title: "Sinh tất định từ luật nghiệp vụ bạn đã duyệt (kỳ vọng do bạn chịu trách nhiệm); không tính vào % EP/BVA",
  },
}

/** Giá trị nguồn gốc lạ (backend thêm mới mà frontend chưa biết) không được làm trắng cả trang. */
export function originDisplay(origin: TestCaseGenerationOrigin) {
  return GENERATION_ORIGIN_DISPLAY[origin] ?? { label: origin, className: "bg-muted text-muted-foreground", title: "Nguồn gốc test case" }
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
  /** 17q G1 - kỳ vọng ban đầu trước khi hệ thống tự đổi sau lần chạy thật test gốc (null = chưa đổi) + lý do. */
  originalExpectedStatus?: number | null
  expectationNote?: string | null
  /** 17q G3 - "REJECTED" = kỳ vọng "bị từ chối (4xx)"; ruleId = luật nghiệp vụ đã sinh test; sendTwice = gửi 2 lần. */
  expectedStatusClass?: string | null
  ruleId?: string | null
  sendTwice?: boolean
}

export interface ReliabilityDisplay {
  label: string
  tone: "reliable" | "needsReview"
  title: string
}

/** 17q G1 - hệ thống đã tự đổi kỳ vọng của test gốc sau lần chạy thật (giữ dấu vết, không được ẩn). */
export function expectationChanged(testCase: TestCase): boolean {
  return testCase.originalExpectedStatus != null && testCase.originalExpectedStatus !== testCase.expectedStatus
}

/**
 * 17q G2 - nhãn đối chiếu tất định với spec (Tier 1, 15b) đổi nghĩa thành "Kỳ vọng cần xem": CHỈ hiện khi có vấn đề (NEEDS_REVIEW) và
 * test không phải do hệ thống bù (test hệ thống sinh từ chính spec nên luôn khớp spec - nhãn xanh cũ không mang thông tin). Không
 * còn nhãn "Đáng tin" xanh và không còn phần AI xác nhận (Tier 2, luồng cũ đã bỏ). Độ tin của KẾT LUẬN xem ở Coverage/kết quả chạy.
 */
export function getReliabilityDisplay(testCase: TestCase): ReliabilityDisplay | null {
  if (testCase.reliabilityStatus !== "NEEDS_REVIEW" || testCase.generationOrigin === "SYSTEM_FILLED") {
    return null
  }
  return {
    label: "Kỳ vọng cần xem",
    tone: "needsReview",
    title: testCase.reliabilityNotes.length > 0 ? testCase.reliabilityNotes.join("; ") : "Giá trị/kỳ vọng lệch ràng buộc của spec",
  }
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
  /** Nhóm nghiệp vụ: AI đoán luật không có trong spec (mọi lý do đều là GIẢ ĐỊNH). */
  includeBusiness?: boolean
}

/** Báo cáo checklist của lần sinh nhóm Basic gần nhất - null nếu chưa có (Module 15h). */
export function getLatestGenerationPlan(projectId: string, endpointId: string) {
  return apiFetch<GenerationPlanSummary | null>(`/projects/${projectId}/endpoints/${endpointId}/generation-plan`)
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

/** Module 17 (G4) - bộ lọc + trang của danh sách test case ở server (giá trị rỗng/undefined = không lọc). */
export interface TestCaseListParams {
  page: number
  size: number
  q?: string
  method?: string
  endpointId?: string
  source?: string
  generationOrigin?: string
  /** "true" | "false" */
  locked?: string
  reliability?: string
}

function buildTestCaseQuery(params: Omit<TestCaseListParams, "page" | "size">): string {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value) query.set(key, String(value))
  }
  return query.toString()
}

/**
 * 1 trang test case, đã sắp theo endpoint (path, method) rồi thời gian tạo: các dòng cùng endpoint nằm liền nhau nên giao diện
 * nhóm được. Hàm cũ {@link listTestCases} vẫn trả TOÀN BỘ danh sách cho các nơi cần đủ (vd chọn nguồn dữ liệu).
 */
export function listTestCasesPage(projectId: string, params: TestCaseListParams): Promise<PagedResult<TestCase>> {
  const { page, size, ...filters } = params
  const filterQuery = buildTestCaseQuery(filters)
  return apiFetchPaged<TestCase>(
    `/projects/${projectId}/test-cases?page=${page}&size=${size}${filterQuery ? `&${filterQuery}` : ""}`
  )
}

/** Id của MỌI test case khớp bộ lọc (không phụ thuộc trang) - cho "Chọn tất cả N khớp bộ lọc". */
export async function listAllMatchingTestCaseIds(
  projectId: string,
  filters: Omit<TestCaseListParams, "page" | "size">
): Promise<string[]> {
  const filterQuery = buildTestCaseQuery(filters)
  const all = await fetchAllPages<TestCase>(
    (page) => `/projects/${projectId}/test-cases?page=${page}&size=200${filterQuery ? `&${filterQuery}` : ""}`
  )
  return all.data.map((testCase) => testCase.id)
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
