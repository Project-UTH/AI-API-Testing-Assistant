import { apiFetch, apiFetchPaged, type PagedResult } from "@/lib/api"
import type { UserRole } from "@/lib/auth"

export interface AdminUser {
  id: string
  email: string
  role: UserRole
  enabled: boolean
  createdAt: string
  totalProjects: number
  totalTestCases: number
  totalBugReports: number
}

export interface AdminDashboardSummary {
  totalUsers: number
  totalProjects: number
  totalEndpoints: number
  totalTestCases: number
  totalTestResults: number
  overallPassRate: number | null
  totalOpenBugs: number
  totalGenerationEvents: number
}

export function getAdminDashboardSummary(): Promise<AdminDashboardSummary> {
  return apiFetch<AdminDashboardSummary>("/admin/dashboard/summary")
}

export function listAdminUsers(page: number): Promise<PagedResult<AdminUser>> {
  return apiFetchPaged<AdminUser>(`/admin/users?page=${page}&size=20`)
}

export function setAdminUserEnabled(userId: string, enabled: boolean): Promise<AdminUser> {
  return apiFetch<AdminUser>(`/admin/users/${userId}/status`, {
    method: "PUT",
    body: JSON.stringify({ enabled }),
  })
}
