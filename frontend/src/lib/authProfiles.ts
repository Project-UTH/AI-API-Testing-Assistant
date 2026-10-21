import { apiFetch } from "@/lib/api"
import type { TargetAuthType } from "@/lib/endpoints"

/** Module 17 (3.1) - 1 profile xác thực (vai trò) của target API. KHÔNG BAO GIỜ có giá trị token - chỉ biết đã có hay chưa. */
export interface AuthProfile {
  id: string
  name: string
  roleKey: string
  authType: TargetAuthType
  hasValue: boolean
  authUpdatedAt: string | null
  createdAt: string
  /** Số test case đang dùng profile này (chặn xoá khi > 0). */
  testCaseCount: number
  /** Chế độ "Đăng nhập": token do hệ thống tự lấy (body đăng nhập không bao giờ trả lại). */
  loginMode: boolean
  loginPath: string | null
  tokenJsonPath: string | null
}

export interface AuthProfileInput {
  name: string
  /** Chỉ dùng lúc tạo (bỏ trống = suy từ tên); bất biến sau đó. */
  roleKey?: string
  authType: TargetAuthType
  /** Lúc sửa: bỏ trống = giữ token cũ. */
  authValue?: string
  /** Có loginPath = chế độ đăng nhập tự động (bỏ qua authValue). */
  loginPath?: string
  /** Lúc sửa: bỏ trống = giữ body đăng nhập cũ. */
  loginBody?: string
  tokenJsonPath?: string
}

export function listAuthProfiles(projectId: string): Promise<AuthProfile[]> {
  return apiFetch<AuthProfile[]>(`/projects/${projectId}/auth-profiles`)
}

export function createAuthProfile(projectId: string, input: AuthProfileInput): Promise<AuthProfile> {
  return apiFetch<AuthProfile>(`/projects/${projectId}/auth-profiles`, { method: "POST", body: JSON.stringify(input) })
}

export function updateAuthProfile(projectId: string, profileId: string, input: AuthProfileInput): Promise<AuthProfile> {
  return apiFetch<AuthProfile>(`/projects/${projectId}/auth-profiles/${profileId}`, { method: "PUT", body: JSON.stringify(input) })
}

export function deleteAuthProfile(projectId: string, profileId: string): Promise<void> {
  return apiFetch<void>(`/projects/${projectId}/auth-profiles/${profileId}`, { method: "DELETE" })
}
