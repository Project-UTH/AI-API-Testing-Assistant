import { apiFetch, apiFetchPaged, type PagedResult } from "@/lib/api"
import type { UserRole } from "@/lib/auth"
import type { Project } from "@/lib/projects"
import type { Endpoint } from "@/lib/endpoints"
import type { TestCase } from "@/lib/testcases"
import type { BugReportPage, TestResultHistoryItem } from "@/lib/bugReports"
import type { AiUsageResponse } from "@/lib/aiUsage"

export interface AdminUser {
  id: string
  email: string
  role: UserRole
  enabled: boolean
  createdAt: string
  totalProjects: number
  totalTestCases: number
  totalBugReports: number
  /** Tổng token AI đã dùng HÔM NAY (giờ UTC) - đối chiếu với quota hiệu lực (aiDailyTokenLimitOverride hoặc mặc định hệ thống). */
  aiTokensToday: number
  aiCallsToday: number
  /** null = đang dùng mặc định hệ thống; có giá trị = admin đã ghi đè riêng cho user này. */
  aiDailyTokenLimitOverride: number | null
}

export interface AdminDashboardSummary {
  totalUsers: number
  totalProjects: number
  totalEndpoints: number
  totalTestCases: number
  /** Số test case phân biệt (mọi user) đã chạy ít nhất 1 lần. */
  executedTestCaseCount: number
  /** Tổng số lượt chạy (mọi user), kể cả chạy lại - mẫu số của overallPassRate. */
  totalTestResults: number
  /** Số kết quả test PASS trong totalTestResults - tử số của overallPassRate. */
  passedTestResults: number
  overallPassRate: number | null
  totalOpenBugs: number
  totalGenerationEvents: number
  totalAiTokensToday: number
  aiDailyTokenLimit: number
}

export type AdminAuditAction = "USER_LOCKED" | "USER_UNLOCKED" | "AI_QUOTA_CHANGED"

export interface AdminAuditEvent {
  id: string
  adminEmail: string
  targetEmail: string
  action: AdminAuditAction
  detail: string | null
  createdAt: string
}

export function listAdminAuditLog(page: number): Promise<PagedResult<AdminAuditEvent>> {
  return apiFetchPaged<AdminAuditEvent>(`/admin/audit-log?page=${page}&size=20`)
}

export function getAdminDashboardSummary(): Promise<AdminDashboardSummary> {
  return apiFetch<AdminDashboardSummary>("/admin/dashboard/summary")
}

export function listAdminUsers(page: number, search?: string): Promise<PagedResult<AdminUser>> {
  const searchParam = search ? `&search=${encodeURIComponent(search)}` : ""
  return apiFetchPaged<AdminUser>(`/admin/users?page=${page}&size=20${searchParam}`)
}

export function getAdminUser(userId: string): Promise<AdminUser> {
  return apiFetch<AdminUser>(`/admin/users/${userId}`)
}

export function setAdminUserEnabled(userId: string, enabled: boolean): Promise<AdminUser> {
  return apiFetch<AdminUser>(`/admin/users/${userId}/status`, {
    method: "PUT",
    body: JSON.stringify({ enabled }),
  })
}

/** dailyTokenLimit null = xoá ghi đè, quay lại dùng mặc định hệ thống. */
export function setAdminUserAiQuota(userId: string, dailyTokenLimit: number | null): Promise<AdminUser> {
  return apiFetch<AdminUser>(`/admin/users/${userId}/ai-quota`, {
    method: "PUT",
    body: JSON.stringify({ dailyTokenLimit }),
  })
}

/** Usage token AI TOÀN HỆ THỐNG (mọi user gộp lại). */
export function getAdminAiUsage(): Promise<AiUsageResponse> {
  return apiFetch<AiUsageResponse>("/admin/dashboard/ai-usage")
}

/** Usage token AI của ĐÚNG user này. */
export function getAdminUserAiUsage(userId: string): Promise<AiUsageResponse> {
  return apiFetch<AiUsageResponse>(`/admin/users/${userId}/ai-usage`)
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
