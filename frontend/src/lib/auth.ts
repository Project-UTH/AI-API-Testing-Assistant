import { apiFetch } from "@/lib/api"

export type UserRole = "USER" | "ADMIN"

export interface AuthResponse {
  token: string
  email: string
  role: UserRole
}

export interface UserInfo {
  email: string
  role: UserRole
}

export function registerUser(email: string, password: string) {
  return apiFetch<AuthResponse>("/auth/register", {
    method: "POST",
    body: JSON.stringify({ email, password }),
  })
}

export function loginUser(email: string, password: string) {
  return apiFetch<AuthResponse>("/auth/login", {
    method: "POST",
    body: JSON.stringify({ email, password }),
  })
}

/**
 * Đọc role hiện tại - luôn gọi API (không đọc từ JWT, JWT không mang role) vì role có thể vừa bị
 * đổi qua SQL trực tiếp trong lúc phiên đăng nhập vẫn còn hiệu lực (xem skill api-contract mục 4d).
 */
export function getCurrentUserInfo(): Promise<UserInfo> {
  return apiFetch<UserInfo>("/auth/me")
}
