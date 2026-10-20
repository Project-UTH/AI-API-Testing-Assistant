import { apiFetch } from "@/lib/api"

/** Module 17 (17d) - token API cho CI. Danh sách KHÔNG bao giờ có giá trị token; chỉ phản hồi tạo có `value` (1 lần). */
export interface ApiToken {
  id: string
  name: string
  prefix: string
  createdAt: string
  expiresAt: string | null
  lastUsedAt: string | null
  revokedAt: string | null
  status: "ACTIVE" | "EXPIRED" | "REVOKED"
}

export interface CreatedApiToken {
  token: ApiToken
  /** Token thô - hiển thị đúng 1 lần, không lưu ở đâu (không cache, không localStorage). */
  value: string
}

export function listApiTokens(projectId: string): Promise<ApiToken[]> {
  return apiFetch<ApiToken[]>(`/projects/${projectId}/api-tokens`)
}

export function createApiToken(projectId: string, input: { name: string; expiresInDays: number | null }): Promise<CreatedApiToken> {
  return apiFetch<CreatedApiToken>(`/projects/${projectId}/api-tokens`, { method: "POST", body: JSON.stringify(input) })
}

export function revokeApiToken(projectId: string, tokenId: string): Promise<ApiToken> {
  return apiFetch<ApiToken>(`/projects/${projectId}/api-tokens/${tokenId}`, { method: "DELETE" })
}
