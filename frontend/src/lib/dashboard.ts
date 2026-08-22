import { apiFetch } from "@/lib/api"
import type { AiUsageResponse } from "@/lib/aiUsage"

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

/** Usage token AI của CHÍNH user đang đăng nhập, 90 ngày gần nhất. */
export function getMyAiUsage(): Promise<AiUsageResponse> {
  return apiFetch<AiUsageResponse>("/dashboard/ai-usage")
}
