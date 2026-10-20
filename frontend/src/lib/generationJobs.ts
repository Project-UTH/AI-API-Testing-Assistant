import { apiFetch } from "@/lib/api"

/** Job sinh test chạy nền: người dùng giám sát từng bước (hệ thống/AI), huỷ được và thu nhỏ khi vừa làm việc khác. */
export type JobStatus = "QUEUED" | "RUNNING" | "CANCELLING" | "SUCCEEDED" | "NEEDS_HUMAN" | "FAILED" | "CANCELLED" | "INTERRUPTED"
export type StepStatus = "RUNNING" | "OK" | "FAILED" | "SKIPPED"
export type StepActor = "SYSTEM" | "AI"
export type JobType = "FULL" | "SYSTEM" | "LEGACY"

export interface GenerationStep {
  seq: number
  endpointId: string | null
  endpointLabel: string | null
  phase: string
  actor: StepActor
  status: StepStatus
  title: string
  detail: string | null
  httpStatus: number | null
  tokens: number | null
  startedAt: string | null
  finishedAt: string | null
}

export interface JobEndpointResult {
  endpointId: string
  label: string
  /** CONFIRMED, ALREADY_HAS_BASELINE, NEEDS_HUMAN, FILLED, GENERATED, DRY_RUN, FAILED */
  status: string
  reason: string | null
  message: string | null
  tokens: number
  filled: number
}

export interface GenerationJob {
  id: string
  projectId: string
  type: JobType
  status: JobStatus
  dryRun: boolean
  tokenCap: number
  tokensUsed: number
  errorMessage: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  results: JobEndpointResult[]
  steps: GenerationStep[]
  lastSeq: number
}

export interface StartJobBody {
  endpointIds: string[]
  type?: JobType
  dryRun?: boolean
  tokenCap?: number
  confirmWrite?: boolean
  maxRepairRounds?: number
  pathParamValues?: Record<string, string>
  fillCoverage?: boolean
  includePositive?: boolean
  includeNegative?: boolean
  includeBoundary?: boolean
  includeSecurity?: boolean
  includeBusiness?: boolean
}

export function startJob(projectId: string, body: StartJobBody): Promise<GenerationJob> {
  return apiFetch<GenerationJob>(`/projects/${projectId}/generation-jobs`, { method: "POST", body: JSON.stringify(body) })
}

export function getJob(projectId: string, jobId: string, afterSeq: number): Promise<GenerationJob> {
  return apiFetch<GenerationJob>(`/projects/${projectId}/generation-jobs/${jobId}?afterSeq=${afterSeq}`)
}

export function cancelJob(projectId: string, jobId: string): Promise<GenerationJob> {
  return apiFetch<GenerationJob>(`/projects/${projectId}/generation-jobs/${jobId}/cancel`, { method: "POST" })
}

export function listActiveJobs(projectId: string): Promise<GenerationJob[]> {
  return apiFetch<GenerationJob[]>(`/projects/${projectId}/generation-jobs?active=true`)
}

export function isTerminal(status: JobStatus): boolean {
  return status !== "QUEUED" && status !== "RUNNING" && status !== "CANCELLING"
}

export const JOB_TYPE_LABEL: Record<JobType, string> = {
  FULL: "Sinh đầy đủ (AI sinh gốc + hệ thống bổ sung)",
  SYSTEM: "Bổ sung bằng hệ thống (0 token)",
  LEGACY: "Sinh Test Case (AI theo checklist)",
}

export const JOB_STATUS_LABEL: Record<JobStatus, string> = {
  QUEUED: "Đang xếp hàng",
  RUNNING: "Đang chạy",
  CANCELLING: "Đang huỷ...",
  SUCCEEDED: "Hoàn tất",
  NEEDS_HUMAN: "Cần xử lý thủ công",
  FAILED: "Thất bại",
  CANCELLED: "Đã huỷ",
  INTERRUPTED: "Bị gián đoạn",
}

/** Gợi ý việc nên làm cho từng lý do NEEDS_HUMAN (giữ từ hộp thoại cũ). */
export const REASON_HELP: Record<string, string> = {
  RESOURCE_NOT_FOUND: "Nhập id thật của tài nguyên (hoặc tạo trước 1 test POST tạo tài nguyên để hệ thống tự nối chuỗi) rồi thử lại.",
  MISSING_PARAM_VALUE: "Nhập giá trị thật cho tham số đường dẫn rồi thử lại.",
  AUTH: "Kiểm tra token/vai trò của project (nút Sửa xác thực hoặc Vai trò). AI không sửa được lỗi này.",
  SERVER_ERROR: "API đích lỗi với test hợp lệ: nhiều khả năng là lỗi phía API.",
  REPAIR_EXHAUSTED: "AI đã sửa nhưng chưa Pass: xem phản hồi của API và sửa tay test gốc ở trang Test case.",
  TOKEN_BUDGET: "Đã chạm trần token cho lần chạy này. Sửa tay test gốc rồi bấm Bổ sung.",
  EXECUTION_TIMEOUT: "Lần chạy thử quá thời gian chờ. Kiểm tra API đích có chạy không rồi thử lại.",
  EXECUTION_ERROR: "Không gọi được API đích: kiểm tra Target URL và mạng rồi thử lại.",
  NO_BASELINE: "Endpoint chưa có test gốc hợp lệ: bấm 'Sinh đầy đủ' để AI sinh, hoặc tự thêm 1 test đúng.",
}

/** Làm mới mọi dữ liệu bị ảnh hưởng khi job có thay đổi test case. */
export function jobQueryKeys(projectId: string): unknown[][] {
  return [["endpoints", projectId], ["project-coverage", projectId], ["test-cases", projectId], ["ai-usage"], ["generation-plan", projectId]]
}
