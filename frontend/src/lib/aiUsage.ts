export interface AiUsageDailyPoint {
  date: string // yyyy-MM-dd
  totalTokens: number
  callCount: number
}

export interface AiUsageResponse {
  daily: AiUsageDailyPoint[]
}
