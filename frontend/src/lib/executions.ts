import { apiFetch } from "@/lib/api"

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
}

export interface TestExecution {
  id: string
  status: ExecutionStatus
  startedAt: string
  finishedAt: string | null
  results: TestResult[]
  autoIncludedTestCaseIds: string[]
}

export function triggerExecution(projectId: string, testCaseIds: string[]): Promise<TestExecution> {
  return apiFetch<TestExecution>(`/projects/${projectId}/executions`, {
    method: "POST",
    body: JSON.stringify({ testCaseIds }),
  })
}

export function getExecution(projectId: string, executionId: string): Promise<TestExecution> {
  return apiFetch<TestExecution>(`/projects/${projectId}/executions/${executionId}`)
}
