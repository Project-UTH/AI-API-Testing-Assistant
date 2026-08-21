import { apiFetch } from "@/lib/api"

export interface DashboardSummary {
  totalProjects: number
  totalEndpoints: number
  totalTestCases: number
  totalTestResults: number
  /** Tỷ lệ pass toàn thời gian (0-100) - null nếu chưa từng chạy test nào. */
  overallPassRate: number | null
  /** Bug Report đang mở (khác Đã đóng) trên toàn bộ project. */
  totalOpenBugs: number
}

export function getDashboardSummary(): Promise<DashboardSummary> {
  return apiFetch<DashboardSummary>("/dashboard/summary")
}
