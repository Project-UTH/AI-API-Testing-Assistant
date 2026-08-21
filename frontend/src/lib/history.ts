import { apiFetch, apiFetchPaged, type PagedResult } from "@/lib/api"

export type HistoryEventType = "GENERATION" | "EXECUTION" | "BUG_REPORT_CREATED" | "BUG_REPORT_DELETED"

export interface GenerationSnapshotItem {
  name: string
  description: string | null
  expectedStatus: number
}

export interface HistoryEvent {
  id: string
  type: HistoryEventType
  occurredAt: string
  // Chỉ có giá trị khi type = "GENERATION"
  generatedCount?: number
  snapshotItems?: GenerationSnapshotItem[]
  // Chỉ có giá trị khi type = "EXECUTION"
  executionId?: string
  selectedCount?: number
  passCount?: number
  failCount?: number
  otherEndpointCount?: number
  // Chỉ có giá trị khi type = "BUG_REPORT_CREATED" | "BUG_REPORT_DELETED"
  bugId?: string
  bugSummary?: string
  // Chỉ có giá trị khi type = "BUG_REPORT_CREATED" (bug còn tồn tại, dùng để deep-link mở dialog sửa)
  bugReportId?: string
}

export interface EndpointHistory {
  endpointId: string
  endpointMethod: string
  endpointPath: string
  endpointSummary: string | null
  currentTestCaseCount: number
  totalRuns: number
  events: HistoryEvent[]
}

export function getProjectHistory(projectId: string): Promise<EndpointHistory[]> {
  return apiFetch<EndpointHistory[]>(`/projects/${projectId}/history`)
}

export type HistoryStatusFilter = "ALL" | "HAS_FAIL" | "ALL_PASS"

export interface HistoryFeedItem {
  id: string
  type: HistoryEventType
  occurredAt: string
  projectId: string
  projectName: string
  endpointId: string
  endpointMethod: string
  endpointPath: string
  // Chỉ có giá trị khi type = "GENERATION"
  generatedCount?: number
  snapshotItems?: GenerationSnapshotItem[]
  // Chỉ có giá trị khi type = "EXECUTION"
  executionId?: string
  selectedCount?: number
  passCount?: number
  failCount?: number
  otherEndpointCount?: number
  // Chỉ có giá trị khi type = "BUG_REPORT_CREATED" | "BUG_REPORT_DELETED"
  bugId?: string
  bugSummary?: string
  // Chỉ có giá trị khi type = "BUG_REPORT_CREATED" (bug còn tồn tại, dùng để deep-link mở dialog sửa)
  bugReportId?: string
}

export interface HistoryFeedFilters {
  page?: number
  size?: number
  projectId?: string
  endpointId?: string
  from?: string
  to?: string
  status?: HistoryStatusFilter
}

export function getHistoryFeed(filters: HistoryFeedFilters): Promise<PagedResult<HistoryFeedItem>> {
  const params = new URLSearchParams({ page: String(filters.page ?? 0), size: String(filters.size ?? 20) })
  if (filters.projectId) params.set("projectId", filters.projectId)
  if (filters.endpointId) params.set("endpointId", filters.endpointId)
  if (filters.from) params.set("from", filters.from)
  if (filters.to) params.set("to", filters.to)
  if (filters.status && filters.status !== "ALL") params.set("status", filters.status)
  return apiFetchPaged<HistoryFeedItem>(`/history?${params.toString()}`)
}
