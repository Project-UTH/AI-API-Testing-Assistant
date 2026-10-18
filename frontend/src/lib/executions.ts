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
  | "ASSERTION_FAILED"
  | "DATA_STATE"
  | "STABLE_FAILURE"
  | "UNDETERMINED"

export interface FailureCause {
  code: FailureCauseCode
  label: string
  /** Bằng chứng mạnh rằng fail KHÔNG phải do API đang test (môi trường/token/cấu hình test). */
  notApiFault: boolean
  evidence: string[]
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
