import { apiFetch } from "@/lib/api"

/** Job sinh test chạy nền: người dùng giám sát từng bước (hệ thống/AI), huỷ được và thu nhỏ khi vừa làm việc khác. */
export type JobStatus = "QUEUED" | "RUNNING" | "CANCELLING" | "SUCCEEDED" | "NEEDS_HUMAN" | "FAILED" | "CANCELLED" | "INTERRUPTED"
export type StepStatus = "RUNNING" | "OK" | "FAILED" | "SKIPPED"
export type StepActor = "SYSTEM" | "AI"
export type JobType = "FULL" | "SYSTEM" | "LEGACY" | "RULES"

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

/** Kết quả cuối của job trích mục (type RULES); mục vẫn ở trạng thái chờ duyệt. */
export interface RulesJobResult {
  endpointCount: number
  lotsPlanned: number
  lotsDone: number
  estimatedTokens: number
  fromDocument: number
  fromInstruction: number
  inferred: number
  rejectedAsDuplicateOfSpec: number
  conflicts: number
  skippedExisting: number
  skippedOverCap: number
  skippedInferred: number
  dropped: Record<string, number>
  /** BUDGET_STOPPED | DAILY_QUOTA | AI_ERROR | null */
  stoppedReason: string | null
  tokenBudget: number
  /** Chỗ tài liệu còn thiếu mà AI nêu thành câu hỏi (không đoán). Job cũ có thể không có. */
  questions?: string[] | null
  /** Số mục làn "AI gợi ý thêm" đã lưu (đã tính trong inferred). */
  suggested?: number
  /** Lý do dừng riêng của làn gợi ý. */
  suggestStoppedReason?: string | null
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
  rules: RulesJobResult | null
}

export interface StartJobBody {
  endpointIds?: string[]
  documentIds?: string[]
  instruction?: string
  allowInferred?: boolean
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
  includeOwasp?: boolean
  includeBusiness?: boolean
  /** FULL/SYSTEM: sau khi sinh test gốc, AI lập checklist nghiệp vụ để bạn duyệt. */
  businessChecklist?: boolean
  /** Chỉ lập lại checklist cho endpoint đã có test gốc Pass (dùng khi trả lời câu hỏi của AI): không sinh/chạy lại test gốc. */
  checklistOnly?: boolean
  /** Bật làn "AI gợi ý thêm" (GIẢ ĐỊNH); backend tự bật khi không có tài liệu lẫn chỉ dẫn. */
  suggest?: boolean
  /** Gợi ý RỘNG thay vì gợi ý thường (chỉ khi suggest bật). */
  suggestBroad?: boolean
  rulesTokenBudget?: number
  /** endpointId -> id các tài liệu dùng RIÊNG cho endpoint đó (rỗng = không dùng tài liệu). Không gửi = mọi endpoint dùng chung documentIds. */
  documentsByEndpoint?: Record<string, string[]>
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

export function listRecentJobs(projectId: string): Promise<GenerationJob[]> {
  return apiFetch<GenerationJob[]>(`/projects/${projectId}/generation-jobs`)
}

export function listActiveJobs(projectId: string): Promise<GenerationJob[]> {
  return apiFetch<GenerationJob[]>(`/projects/${projectId}/generation-jobs?active=true`)
}

export function isTerminal(status: JobStatus): boolean {
  return status !== "QUEUED" && status !== "RUNNING" && status !== "CANCELLING"
}

export const JOB_TYPE_LABEL: Record<JobType, string> = {
  FULL: "Sinh test case (AI sinh test gốc + hệ thống bổ sung)",
  SYSTEM: "Sinh test case: chỉ bổ sung từ test gốc có sẵn (0 token)",
  LEGACY: "Sinh toàn bộ bằng AI (chức năng cũ đã bỏ)",
  RULES: "AI lập checklist nghiệp vụ (mục chờ bạn duyệt)",
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
  CREDENTIALS: "Email/mật khẩu trong test gốc chưa đúng tài khoản thật. Tự thêm 1 test gốc với tài khoản thật (đã Pass) rồi sinh lại ở chế độ 0 token ('Chỉ bổ sung từ test gốc').",
  AUTH: "Kiểm tra token/vai trò của project (nút Sửa xác thực hoặc Vai trò). AI không sửa được lỗi này.",
  SERVER_ERROR: "API đích lỗi với test hợp lệ: nhiều khả năng là lỗi phía API.",
  STATE_CONFLICT: "API đích báo xung đột dữ liệu (409) lặp lại, AI không sửa được: dọn hoặc đổi dữ liệu ở môi trường test (vd bản ghi đã tồn tại) rồi thử lại.",
  REPAIR_EXHAUSTED: "AI đã sửa nhưng chưa Pass: xem phản hồi của API và sửa tay test gốc ở trang Test case.",
  TOKEN_BUDGET: "Đã chạm trần token cho lần chạy này. Sửa tay test gốc rồi bấm Bổ sung.",
  EXECUTION_TIMEOUT: "Lần chạy thử quá thời gian chờ. Kiểm tra API đích có chạy không rồi thử lại.",
  PREREQUISITE_MISSING: "API báo thiếu dữ liệu tiên quyết (404 *_NOT_FOUND, vd tài khoản HR chưa tạo công ty). AI không sửa được: tạo dữ liệu đó trước (thường bằng test POST tạo tài nguyên tương ứng) rồi chạy lại.",
  SOURCE_NOT_PASSED: "Test POST tạo tài nguyên dùng làm nguồn id chưa Pass (vd test nháp còn sót). Mở test đó ở tab Test case, sửa hoặc chạy lại cho Pass, rồi chạy lại; hoặc nhập id thật vào ô giá trị.",
  EXECUTION_ERROR: "Không gọi được API đích: kiểm tra Target URL và mạng rồi thử lại.",
  NO_BASELINE: "Endpoint chưa có test gốc hợp lệ: bấm 'Sinh test case' để AI sinh, hoặc tự thêm 1 test đúng.",
}

/** Làm mới mọi dữ liệu bị ảnh hưởng khi job có thay đổi test case. */
export function jobQueryKeys(projectId: string): unknown[][] {
  return [["endpoints", projectId], ["project-coverage", projectId], ["test-cases", projectId], ["ai-usage"], ["generation-plan", projectId], ["rules", projectId], ["requirement-docs", projectId]]
}
