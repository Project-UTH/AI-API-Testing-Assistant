import { apiFetch } from "@/lib/api"

/** AI sinh 1 test gốc hợp lệ, hệ thống CHẠY THẬT để xác nhận (có vòng AI sửa theo lỗi thật của API), rồi tuỳ chọn bổ sung checklist. */
export type BaselineStatus = "CONFIRMED" | "NEEDS_HUMAN" | "ALREADY_HAS_BASELINE"

export interface BaselineRequest {
  confirmWrite: boolean
  maxRepairRounds: number
  pathParamValues: Record<string, string>
  fillCoverage: boolean
  force?: boolean
}

export interface BaselineRound {
  round: number
  httpStatus: number | null
  passed: boolean
  tokens: number
  note: string | null
}

export interface BaselineResult {
  status: BaselineStatus
  /** Mã lý do khi NEEDS_HUMAN: RESOURCE_NOT_FOUND, AUTH, CREDENTIALS, SERVER_ERROR, MISSING_PARAM_VALUE, REPAIR_EXHAUSTED, TOKEN_BUDGET, EXECUTION_TIMEOUT, EXECUTION_ERROR. */
  reason: string | null
  message: string | null
  baselineTestCaseId: string | null
  rounds: BaselineRound[]
  tokensUsed: number
  filledCount: number
  lastResponseStatus: number | null
  lastResponseBodyMasked: string | null
}

export function generateBaseline(projectId: string, endpointId: string, body: BaselineRequest): Promise<BaselineResult> {
  return apiFetch<BaselineResult>(`/projects/${projectId}/endpoints/${endpointId}/baseline`, {
    method: "POST",
    body: JSON.stringify(body),
  })
}

/** Tên tham số đường dẫn OpenAPI, vd "/jobs/{jobId}/rubric/{criterionId}" -> ["jobId", "criterionId"]. */
export function pathParamNames(path: string): string[] {
  return Array.from(path.matchAll(/\{(\w+)\}/g)).map((m) => m[1])
}

export const WRITE_METHODS = ["POST", "PUT", "PATCH", "DELETE"]
