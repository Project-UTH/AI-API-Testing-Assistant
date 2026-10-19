import { apiFetch } from "@/lib/api"
import type { FailureCauseCode, TestResultStatus, ExecutionStatus } from "@/lib/executions"

/** Module 17 (17c) - nhóm kết quả khi so sánh 2 lượt chạy; thứ tự = thứ tự ưu tiên hiển thị (hồi quy trước). */
export type ExecutionDiffCategory =
  | "REGRESSED"
  | "FIXED"
  | "STILL_FAILING"
  | "CHANGED_TEST"
  | "UNRELIABLE"
  | "INCONCLUSIVE"
  | "ONLY_IN_TARGET"
  | "ONLY_IN_BASE"
  | "STILL_PASSING"

export interface ExecutionSummary {
  id: string
  startedAt: string
  finishedAt: string | null
  status: ExecutionStatus
  totalCount: number
  passedCount: number
  failedCount: number
}

export interface ExecutionDiffItem {
  testCaseId: string
  testCaseName: string
  endpointId: string
  endpointMethod: string
  endpointPath: string
  category: ExecutionDiffCategory
  baseStatus: TestResultStatus | null
  targetStatus: TestResultStatus | null
  baseResponseStatus: number | null
  targetResponseStatus: number | null
  baseFailureCause: FailureCauseCode | null
  targetFailureCause: FailureCauseCode | null
  note: string | null
}

export interface ExecutionCompare {
  base: { id: string; startedAt: string; status: ExecutionStatus; targetBaseUrl: string | null }
  target: { id: string; startedAt: string; status: ExecutionStatus; targetBaseUrl: string | null }
  summary: {
    regressed: number
    fixed: number
    stillFailing: number
    changedTest: number
    unreliable: number
    inconclusive: number
    onlyInTarget: number
    onlyInBase: number
    stillPassing: number
    /** Target Base URL hoặc mốc đổi token khác nhau giữa 2 lượt. */
    environmentChanged: boolean
  }
  items: { data: ExecutionDiffItem[]; page: number; size: number; totalElements: number; totalPages: number }
}

export function listExecutions(projectId: string): Promise<ExecutionSummary[]> {
  return apiFetch<ExecutionSummary[]>(`/projects/${projectId}/executions`)
}

export function compareExecutions(
  projectId: string,
  baseId: string,
  targetId: string,
  category: ExecutionDiffCategory | null,
  page: number
): Promise<ExecutionCompare> {
  const params = new URLSearchParams({ baseId, targetId, page: String(page), size: "25" })
  if (category) params.set("category", category)
  return apiFetch<ExecutionCompare>(`/projects/${projectId}/executions/compare?${params.toString()}`)
}
