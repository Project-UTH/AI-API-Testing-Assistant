import { apiFetch } from "@/lib/api"
import type { AiUsageResponse } from "@/lib/aiUsage"

export interface DashboardSummary {
  totalProjects: number
  totalEndpoints: number
  totalTestCases: number
  /** Số test case phân biệt đã chạy ít nhất 1 lần (khác totalTestResults - test case chạy nhiều
   *  lần vẫn chỉ tính 1). */
  executedTestCaseCount: number
  /** Tổng số lượt chạy, kể cả chạy lại - mẫu số của overallPassRate. */
  totalTestResults: number
  /** Số kết quả test PASS trong totalTestResults - tử số của overallPassRate. */
  passedTestResults: number
  /** Tỷ lệ pass = passedTestResults/totalTestResults * 100 - null nếu chưa từng chạy test nào. */
  overallPassRate: number | null
  /** Bug Report đang mở (khác Đã đóng) trên toàn bộ project. */
  totalOpenBugs: number
  /** Token AI CHÍNH user này đã dùng hôm nay (giờ UTC). */
  aiTokensToday: number
  /** Giới hạn token/ngày HIỆU LỰC cho CHÍNH user này (đã resolve sẵn ở server: ghi đè riêng nếu
   *  admin có đặt, không thì mặc định hệ thống). */
  aiDailyTokenLimit: number
}

export function getDashboardSummary(): Promise<DashboardSummary> {
  return apiFetch<DashboardSummary>("/dashboard/summary")
}

/** Usage token AI của CHÍNH user đang đăng nhập, 90 ngày gần nhất. */
export function getMyAiUsage(): Promise<AiUsageResponse> {
  return apiFetch<AiUsageResponse>("/dashboard/ai-usage")
}
