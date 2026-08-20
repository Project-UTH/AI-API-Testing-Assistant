import { apiFetch, apiFetchBlob } from "@/lib/api"
import type { AssertionResult, TestResultStatus } from "@/lib/executions"

export type BugStatus =
  | "NEW"
  | "NEED_REVISE"
  | "READY_TO_SUBMIT"
  | "POSTED"
  | "PENDING"
  | "CANNOT_REPRODUCE"
  | "REOPENED"
  | "CLOSED"

export type BugSeverity = "MINOR" | "SERIOUS" | "CRITICAL"
export type BugFrequency = "SELDOM" | "OFTEN" | "ALWAYS"
export type BugPriority = "TRIVIAL" | "MINOR" | "MAJOR" | "CRITICAL" | "BLOCKER"

export interface ComponentRef {
  endpointId: string
  endpointMethod: string
  endpointPath: string
}

export interface BugReport {
  id: string
  bugId: string
  status: BugStatus
  severity: BugSeverity
  priority: BugPriority
  frequency: BugFrequency
  summary: string
  testEnvironment: string | null
  stepsToReproduce: string | null
  actualResult: string | null
  expectedResult: string | null
  attachmentUrl: string | null
  build: string | null
  component: ComponentRef
  testCaseId: string
  testCaseName: string
  sourceTestResultId: string
  sourceOccurredAt: string
  reporterEmail: string
  pendingCloseSuggestion: boolean
  note: string | null
  createdAt: string
  updatedAt: string
}

export interface TestCaseBugSummary {
  testCaseId: string
  testCaseName: string
  bugs: BugReport[]
}

export interface EndpointBugSummary {
  endpointId: string
  endpointMethod: string
  endpointPath: string
  endpointSummary: string | null
  testCases: TestCaseBugSummary[]
}

export interface ComponentBugCount {
  endpointId: string
  endpointMethod: string
  endpointPath: string
  count: number
}

export interface BugDashboardSummary {
  countByPriority: Partial<Record<BugPriority, number>>
  countByComponent: ComponentBugCount[]
  openCount: number
  closedCount: number
  totalCount: number
}

export interface BugReportPage {
  summary: BugDashboardSummary
  endpoints: EndpointBugSummary[]
}

export interface TestResultHistoryItem {
  testResultId: string
  executionId: string
  occurredAt: string
  status: TestResultStatus
  expectedStatus: number
  responseStatus: number | null
  responseBody: string | null
  errorMessage: string | null
  assertionResults: AssertionResult[]
}

export interface BugReportDraft {
  testCaseId: string
  testCaseName: string
  sourceTestResultId: string
  component: ComponentRef
  summary: string
  stepsToReproduce: string | null
  actualResult: string | null
  expectedResult: string | null
  defaultBuild: string
}

export interface CreateBugReportInput {
  sourceTestResultId: string
  summary: string
  testEnvironment: string | null
  stepsToReproduce: string | null
  actualResult: string | null
  expectedResult: string | null
  severity: BugSeverity
  frequency: BugFrequency
  priority: BugPriority
  attachmentUrl: string | null
  build: string | null
}

export interface UpdateBugReportInput {
  status: BugStatus
  severity: BugSeverity
  frequency: BugFrequency
  priority: BugPriority
  summary: string
  testEnvironment: string | null
  stepsToReproduce: string | null
  actualResult: string | null
  expectedResult: string | null
  attachmentUrl: string | null
  build: string | null
  note: string | null
}

export function getBugReports(projectId: string): Promise<BugReportPage> {
  return apiFetch<BugReportPage>(`/projects/${projectId}/bug-reports`)
}

export function getRunHistory(projectId: string, testCaseId: string): Promise<TestResultHistoryItem[]> {
  return apiFetch<TestResultHistoryItem[]>(`/projects/${projectId}/bug-reports/test-cases/${testCaseId}/run-history`)
}

export function getBugReportDraft(projectId: string, testResultId: string): Promise<BugReportDraft> {
  return apiFetch<BugReportDraft>(`/projects/${projectId}/bug-reports/draft?testResultId=${testResultId}`)
}

export function createBugReport(projectId: string, input: CreateBugReportInput): Promise<BugReport> {
  return apiFetch<BugReport>(`/projects/${projectId}/bug-reports`, {
    method: "POST",
    body: JSON.stringify(input),
  })
}

export function updateBugReport(projectId: string, bugReportId: string, input: UpdateBugReportInput): Promise<BugReport> {
  return apiFetch<BugReport>(`/projects/${projectId}/bug-reports/${bugReportId}`, {
    method: "PUT",
    body: JSON.stringify(input),
  })
}

export function confirmCloseBugReport(projectId: string, bugReportId: string): Promise<BugReport> {
  return apiFetch<BugReport>(`/projects/${projectId}/bug-reports/${bugReportId}/confirm-close`, {
    method: "POST",
  })
}

export function dismissCloseSuggestion(projectId: string, bugReportId: string): Promise<BugReport> {
  return apiFetch<BugReport>(`/projects/${projectId}/bug-reports/${bugReportId}/dismiss-close-suggestion`, {
    method: "POST",
  })
}

export function deleteBugReport(projectId: string, bugReportId: string): Promise<void> {
  return apiFetch<void>(`/projects/${projectId}/bug-reports/${bugReportId}`, {
    method: "DELETE",
  })
}

/** Tải file .xlsx của đúng 1 bug report về máy - kích hoạt download qua thẻ <a> ẩn, không mở tab mới. */
export async function exportBugReport(projectId: string, bugReportId: string, fallbackFilename: string): Promise<void> {
  await downloadFile(`/projects/${projectId}/bug-reports/${bugReportId}/export`, fallbackFilename)
}

/** Tải file .xlsx gộp TOÀN BỘ bug report của project (nhiều dòng, 1 bug = 1 dòng). */
export async function exportAllBugReports(projectId: string, fallbackFilename: string): Promise<void> {
  await downloadFile(`/projects/${projectId}/bug-reports/export`, fallbackFilename)
}

async function downloadFile(path: string, fallbackFilename: string): Promise<void> {
  const { blob, filename } = await apiFetchBlob(path)
  const url = URL.createObjectURL(blob)
  const link = document.createElement("a")
  link.href = url
  link.download = filename ?? fallbackFilename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}
