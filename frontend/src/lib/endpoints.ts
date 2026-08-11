import { apiFetch, apiFetchPaged, type PagedResult } from "@/lib/api"

export interface Endpoint {
  id: string
  path: string
  method: string
  summary: string | null
  createdAt: string
  testCaseCount: number
}

export type TargetAuthType = "NONE" | "API_KEY" | "BEARER_TOKEN"

export interface ImportOpenApiInput {
  url?: string
  file?: File
  authType?: TargetAuthType
  authValue?: string
  /** Base URL của API thật sẽ gọi lúc thực thi test - khác với `url` (vị trí tài liệu OpenAPI). */
  targetBaseUrl?: string
}

export function listEndpoints(projectId: string): Promise<PagedResult<Endpoint>> {
  return apiFetchPaged<Endpoint>(`/projects/${projectId}/endpoints?page=0&size=100&sort=createdAt,asc`)
}

export function importOpenApi(projectId: string, input: ImportOpenApiInput): Promise<Endpoint[]> {
  const formData = new FormData()
  if (input.url) formData.append("url", input.url)
  if (input.file) formData.append("file", input.file)
  if (input.authType && input.authType !== "NONE") {
    formData.append("authType", input.authType)
    if (input.authValue) formData.append("authValue", input.authValue)
  }
  if (input.targetBaseUrl) formData.append("targetBaseUrl", input.targetBaseUrl)

  return apiFetch<Endpoint[]>(`/projects/${projectId}/endpoints/import`, {
    method: "POST",
    body: formData,
  })
}
