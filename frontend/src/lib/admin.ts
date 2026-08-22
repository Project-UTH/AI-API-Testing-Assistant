import { apiFetch, apiFetchPaged, type PagedResult } from "@/lib/api"
import type { UserRole } from "@/lib/auth"
import type { Project } from "@/lib/projects"
import type { Endpoint } from "@/lib/endpoints"
import type { TestCase } from "@/lib/testcases"
import type { BugReportPage, TestResultHistoryItem } from "@/lib/bugReports"

export interface AdminUser {
  id: string
  email: string
  role: UserRole
  enabled: boolean
  createdAt: string
  totalProjects: number
  totalTestCases: number
  totalBugReports: number
  /** Tổng token AI đã dùng HÔM NAY (giờ UTC) - đối chiếu với AdminDashboardSummary.aiDailyTokenLimit. */
  aiTokensToday: number
  aiCallsToday: number
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
  totalAiTokensToday: number
  aiDailyTokenLimit: number
}

export type AdminAuditAction = "USER_LOCKED" | "USER_UNLOCKED"

export interface AdminAuditEvent {
  id: string
  adminEmail: string
  targetEmail: string
  action: AdminAuditAction
  createdAt: string
}

export function listAdminAuditLog(page: number): Promise<PagedResult<AdminAuditEvent>> {
  return apiFetchPaged<AdminAuditEvent>(`/admin/audit-log?page=${page}&size=20`)
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

// Xem (chỉ đọc) dữ liệu Project/Endpoint/TestCase của 1 user cụ thể khác - phục vụ hỗ trợ/điều
// tra. Tái dùng nguyên type Project/Endpoint/TestCase (response backend khớp 100%, xem
// AdminUserDataService) - không định nghĩa type riêng trùng lặp.

export function listAdminUserProjects(userId: string, page: number): Promise<PagedResult<Project>> {
  return apiFetchPaged<Project>(`/admin/users/${userId}/projects?page=${page}&size=20`)
}

export function getAdminUserProject(userId: string, projectId: string): Promise<Project> {
  return apiFetch<Project>(`/admin/users/${userId}/projects/${projectId}`)
}

export function listAdminUserEndpoints(userId: string, projectId: string): Promise<PagedResult<Endpoint>> {
  return apiFetchPaged<Endpoint>(`/admin/users/${userId}/projects/${projectId}/endpoints?page=0&size=100`)
}

export function listAdminUserTestCases(userId: string, projectId: string): Promise<TestCase[]> {
  return apiFetch<TestCase[]>(`/admin/users/${userId}/projects/${projectId}/test-cases`)
}

export function getAdminUserBugReports(userId: string, projectId: string): Promise<BugReportPage> {
  return apiFetch<BugReportPage>(`/admin/users/${userId}/projects/${projectId}/bug-reports`)
}

export function getAdminUserRunHistory(
  userId: string,
  projectId: string,
  testCaseId: string
): Promise<TestResultHistoryItem[]> {
  return apiFetch<TestResultHistoryItem[]>(
    `/admin/users/${userId}/projects/${projectId}/bug-reports/test-cases/${testCaseId}/run-history`
  )
}
