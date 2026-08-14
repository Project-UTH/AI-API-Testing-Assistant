import { apiFetch } from "@/lib/api"

export interface DashboardSummary {
  totalProjects: number
  totalEndpoints: number
  totalTestCases: number
  totalTestResults: number
  /** Tỷ lệ pass toàn thời gian (0-100) - null nếu chưa từng chạy test nào. */
  overallPassRate: number | null
}

export function getDashboardSummary(): Promise<DashboardSummary> {
  return apiFetch<DashboardSummary>("/dashboard/summary")
}
