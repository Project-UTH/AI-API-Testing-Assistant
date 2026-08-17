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

export interface TestResult {
  testCaseId: string
  testCaseName: string
  endpointId: string
  status: TestResultStatus
  expectedStatus: number
  responseStatus: number | null
  responseBody: string | null
  errorMessage: string | null
  assertionResults: AssertionResult[]
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
