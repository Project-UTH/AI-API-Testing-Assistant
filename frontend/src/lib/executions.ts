import { apiFetch, downloadFile, type PagedResult } from "@/lib/api"

export type ExecutionStatus = "PENDING" | "RUNNING" | "COMPLETED" | "FAILED"
export type TestResultStatus = "PASSED" | "FAILED" | "ERROR" | "BLOCKED" | "SKIPPED"

export type AssertionOperator = "EQUALS" | "CONTAINS" | "EXISTS" | "TYPE"

export interface AssertionResult {
  jsonPath: string
  operator: AssertionOperator
  expectedValue: string | null
  actualValue: string | null
  passed: boolean
}

export type ErrorKind = "TIMEOUT" | "CONNECTION" | "DNS" | "OTHER"

/** Module 16 - nguyên nhân NGHI NGỜ của kết quả FAILED/ERROR (chấm tất định lúc chạy, không phải AI, không khẳng định). */
export type FailureCauseCode =
  | "TEST_SETUP"
  | "ENVIRONMENT"
  | "AUTH_CONFIG"
  | "FLAKY"
  | "ASSERTION_FAILED"
  | "DATA_STATE"
  | "STABLE_FAILURE"
  | "RESOURCE_NOT_FOUND"
  | "EXPECTATION_MISMATCH"
  | "UNDETERMINED"
  | "STATE_CONFLICT"
  | "AUTHZ_VIOLATION"
  | "TEST_DATA_SUSPECT"
  | "MASS_ASSIGNMENT"
  | "TOKEN_FORBIDDEN"
  | "API_WRONG_STATUS"

/** Nhãn tiếng Việt cho từng nguyên nhân (khớp FailureCause backend) - dùng cho bộ lọc; mọi mã backend phải có ở đây. */
export const FAILURE_CAUSE_LABEL: Record<FailureCauseCode, string> = {
  TEST_SETUP: "Lỗi cấu hình test",
  ENVIRONMENT: "Nghi lỗi môi trường",
  AUTH_CONFIG: "Nghi token/xác thực",
  FLAKY: "Không ổn định",
  ASSERTION_FAILED: "Assertion sai",
  DATA_STATE: "Nghi dữ liệu/trạng thái đổi",
  STABLE_FAILURE: "Lỗi ổn định",
  RESOURCE_NOT_FOUND: "Có thể id không tồn tại",
  EXPECTATION_MISMATCH: "Kỳ vọng có thể quá chặt",
  UNDETERMINED: "Chưa đủ dữ liệu",
  STATE_CONFLICT: "Xung đột trạng thái dữ liệu",
  AUTHZ_VIOLATION: "Sai lệch phân quyền",
  TEST_DATA_SUSPECT: "Nghi dữ liệu test không hợp lệ",
  MASS_ASSIGNMENT: "Nhận field không khai báo (mass assignment)",
  TOKEN_FORBIDDEN: "Nghi token thiếu quyền cho endpoint này",
  API_WRONG_STATUS: "API trả sai mã lỗi (401 thay vì 400)",
}

export interface FailureCause {
  code: FailureCauseCode
  label: string
  /** Bằng chứng mạnh rằng fail KHÔNG phải do API đang test (môi trường/token/cấu hình test). */
  notApiFault: boolean
  evidence: string[]
}

/**
 * Module 16 gđ2 - độ ổn định KẾT QUẢ của test theo lịch sử các lần chạy cùng phiên bản test (tình trạng hiện tại, tính lúc
 * xem). FLAKY = đổi Pass/Fail xen kẽ đủ nhiều lần; NEEDS_MORE_RUNS = có dấu hiệu nhưng chưa đủ lần chạy để kết luận.
 * Chỉ là nhận định về tính tất định của kết quả, không khẳng định nguyên nhân (nằm ở test hay API đích).
 */
export type StabilityState = "FLAKY" | "NEEDS_MORE_RUNS"

export interface Stability {
  state: StabilityState
  runs: number
  passes: number
  fails: number
  /** Số lần kết quả đổi giữa Pass và Fail. */
  flips: number
  passRate: number
  /** Chỉ có khi NEEDS_MORE_RUNS: vì sao nên chạy lại. */
  rerunReason: string | null
  /** Method có thể tạo/sửa/xoá dữ liệu thật của hệ thống đích - chạy lại cần xác nhận. */
  mutatesData: boolean
}

export interface TestResult {
  id: string
  testCaseId: string
  testCaseName: string
  endpointId: string
  status: TestResultStatus
  expectedStatus: number
  responseStatus: number | null
  responseBody: string | null
  errorMessage: string | null
  assertionResults: AssertionResult[]
  /** Bug Report đã tồn tại từ lần chạy này (nếu có) - null nếu chưa ai tạo. */
  existingBugReportId: string | null
  existingBugId: string | null
  /** Module 16 - thời gian gọi target (ms); null khi không gọi target hoặc kết quả cũ. */
  durationMs: number | null
  errorKind: ErrorKind | null
  /** null cho PASSED/BLOCKED và kết quả trước Module 16. */
  failureCause: FailureCause | null
  endpointMethod: string
  /** null khi không đáng chú ý (ổn định/chưa đủ dữ liệu) hoặc lượt chạy chưa xong. */
  stability: Stability | null
  /** Cảnh báo phản hồi thật lệch schema response của spec (kiểu/trường bắt buộc); rỗng = khớp hoặc không kiểm. Không đổi Pass/Fail. */
  schemaViolations: string[]
  /** Module 17 (17b) - cảnh báo dữ liệu nhạy cảm trong response (chỉ đường dẫn + quy tắc, không có giá trị). Không đổi Pass/Fail. */
  securityFindings?: SecurityFinding[]
}

export interface SecurityFinding {
  path: string
  rule: string
  severity: "HIGH" | "MEDIUM" | "LOW"
  detail: string
}

export interface TestExecution {
  id: string
  status: ExecutionStatus
  startedAt: string
  finishedAt: string | null
  results: TestResult[]
  autoIncludedTestCaseIds: string[]
}

export function triggerExecution(projectId: string, testCaseIds: string[], cleanupCreated = false): Promise<TestExecution> {
  return apiFetch<TestExecution>(`/projects/${projectId}/executions`, {
    method: "POST",
    body: JSON.stringify({ testCaseIds, cleanupCreated }),
  })
}

export type ExecutionReportFormat = "junit" | "html"

/**
 * Module 17 (17d) - tải báo cáo 1 lượt chạy (JUnit XML cho CI / HTML để mở-in). Tải bằng fetch + blob để gắn JWT qua header
 * (thẻ <a download> không gắn được header, và không đưa token lên URL). Báo cáo chỉ gồm dữ liệu đã làm sạch ở backend.
 */
export function exportExecution(projectId: string, executionId: string, format: ExecutionReportFormat): Promise<void> {
  const extension = format === "junit" ? "xml" : "html"
  return downloadFile(
    `/projects/${projectId}/executions/${executionId}/report?format=${format}`,
    `execution-${executionId.slice(0, 8)}.${extension}`
  )
}

export function getExecution(projectId: string, executionId: string): Promise<TestExecution> {
  return apiFetch<TestExecution>(`/projects/${projectId}/executions/${executionId}`)
}

/** Tổng hợp trên TOÀN BỘ kết quả của lượt chạy (không đổi theo bộ lọc/trang). */
export interface ExecutionResultsSummary {
  total: number
  statusCounts: Partial<Record<TestResultStatus, number>>
  failureCauseCounts: Partial<Record<FailureCauseCode, number>>
  unstableCount: number
  /** Số kết quả FAILED chưa có Bug Report. */
  failedWithoutBugCount: number
  /** Id các kết quả của lượt chạy này đã có Bug Report (trang chỉ tải từng phần nên cần biết trước). */
  bugResultIds: string[]
}

/** Module 17 (G4) - lượt chạy với kết quả theo trang. */
export interface TestExecutionPage {
  id: string
  status: ExecutionStatus
  startedAt: string
  finishedAt: string | null
  results: PagedResult<TestResult>
  summary: ExecutionResultsSummary
  autoIncludedTestCaseIds: string[]
}

export interface ExecutionResultsParams {
  page: number
  size: number
  status?: string
  failureCause?: string
  endpointId?: string
  q?: string
}

export function getExecutionPage(projectId: string, executionId: string, params: ExecutionResultsParams): Promise<TestExecutionPage> {
  const query = new URLSearchParams({ page: String(params.page), size: String(params.size) })
  if (params.status) query.set("status", params.status)
  if (params.failureCause) query.set("failureCause", params.failureCause)
  if (params.endpointId) query.set("endpointId", params.endpointId)
  if (params.q) query.set("q", params.q)
  return apiFetch<TestExecutionPage>(`/projects/${projectId}/executions/${executionId}?${query.toString()}`)
}
