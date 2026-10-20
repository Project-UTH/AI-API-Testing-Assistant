import { apiFetch, apiFetchPaged, type PagedResult } from "@/lib/api"

export interface Endpoint {
  id: string
  path: string
  method: string
  summary: string | null
  createdAt: string
  testCaseCount: number
  /** Module 17 (1.3) - giá trị đang dùng: khai báo thủ công nếu có, không thì suy từ spec. */
  requiresAuth: boolean
  requiresAuthSource: "SPEC" | "MANUAL"
  /** Module 17 (17c) - lần import gần nhất đã ĐỔI spec của endpoint này (test case của nó có thể lỗi thời); null = chưa đổi. */
  schemaChangedAt: string | null
  /** Module 17 (17c) - endpoint không còn trong spec ở lần import gần nhất (vẫn giữ test case/lịch sử). */
  missingFromSpec: boolean
}

/** DIFF = giữ test case/lịch sử của endpoint không đổi (mặc định); REPLACE = xoá hết rồi tạo lại. */
export type ImportApplyMode = "DIFF" | "REPLACE"

/** Kết quả so sánh spec mới với endpoint hiện có (POST .../endpoints/import-preview) - KHÔNG ghi gì. */
export interface ImportDiff {
  addedCount: number
  changedCount: number
  removedCount: number
  unchangedCount: number
  /** Số test case của các endpoint ĐÃ ĐỔI spec (có thể lỗi thời). */
  affectedTestCaseCount: number
  addedEndpoints: string[]
  changedEndpoints: string[]
  removedEndpoints: string[]
}

export type TargetAuthType = "NONE" | "API_KEY" | "BEARER_TOKEN" | "BASIC_AUTH" | "CUSTOM_HEADER" | "QUERY_PARAM"

export interface ImportOpenApiInput {
  url?: string
  file?: File
  authType?: TargetAuthType
  authValue?: string
  /** Base URL của API thật sẽ gọi lúc thực thi test - khác với `url` (vị trí tài liệu OpenAPI). */
  targetBaseUrl?: string
  /** Mặc định DIFF ở backend. */
  mode?: ImportApplyMode
}

/** Dữ liệu của project sẽ bị xoá khi import lại OpenAPI (GET .../endpoints/import-impact). */
export interface ImportImpact {
  endpointCount: number
  testCaseCount: number
  manualTestCaseCount: number
  lockedTestCaseCount: number
  executionCount: number
  testResultCount: number
  bugReportCount: number
  openBugReportCount: number
  generationEventCount: number
  /** Kết quả chạy FAILED - chỉ để giao diện biết project có test fail không (banner "Bước tiếp theo"). */
  failedResultCount: number
}

export function getImportImpact(projectId: string): Promise<ImportImpact> {
  return apiFetch<ImportImpact>(`/projects/${projectId}/endpoints/import-impact`)
}

export function listEndpoints(projectId: string): Promise<PagedResult<Endpoint>> {
  return apiFetchPaged<Endpoint>(`/projects/${projectId}/endpoints?page=0&size=100&sort=createdAt,asc`)
}

function buildImportForm(input: ImportOpenApiInput): FormData {
  const formData = new FormData()
  if (input.url) formData.append("url", input.url)
  if (input.file) formData.append("file", input.file)
  if (input.authType && input.authType !== "NONE") {
    formData.append("authType", input.authType)
    if (input.authValue) formData.append("authValue", input.authValue)
  }
  if (input.targetBaseUrl) formData.append("targetBaseUrl", input.targetBaseUrl)
  if (input.mode) formData.append("mode", input.mode)
  return formData
}

export function importOpenApi(projectId: string, input: ImportOpenApiInput): Promise<Endpoint[]> {
  return apiFetch<Endpoint[]>(`/projects/${projectId}/endpoints/import`, {
    method: "POST",
    body: buildImportForm(input),
  })
}

/** Xem trước import: so sánh spec mới với endpoint hiện có mà không ghi gì (Module 17 - 17c). */
export function previewImport(projectId: string, input: ImportOpenApiInput): Promise<ImportDiff> {
  return apiFetch<ImportDiff>(`/projects/${projectId}/endpoints/import-preview`, {
    method: "POST",
    body: buildImportForm(input),
  })
}
