import { apiFetch } from "@/lib/api"
import type { TestResultStatus } from "@/lib/executions"

export type CoverageDimensionStatus = "COVERED" | "MISSING" | "NOT_APPLICABLE"

/** Module 15j - dòng đã có test thì các test đó đã chạy ra kết quả chưa (lần chạy mới nhất). */
export type CoverageRunStatus = "NOT_RUN" | "NO_VERDICT" | "PASSED" | "FAILED"

export interface CoverageTestCaseRef {
  id: string
  name: string
  /** Module 15j - kết quả lần chạy mới nhất, null khi chưa chạy lần nào. */
  latestResult: TestResultStatus | null
}

/** Module 15j - case "gần đạt" 1 mục nhưng không được tính, kèm lý do (bằng chứng cho khoảng trống). */
export interface CoverageNearMiss {
  testCaseId: string
  testCaseName: string
  reason: string
}

export interface CoverageDimension {
  /** POSITIVE/NEGATIVE/BOUNDARY/SECURITY (mục checklist), STATUS_CODE, RELATION, AUTH (403). */
  category: string
  label: string
  status: CoverageDimensionStatus
  detail: string | null
  coveringTestCases: CoverageTestCaseRef[]
  /** Tên ChecklistItemType khi dòng là 1 mục checklist EP/BVA, null với STATUS_CODE/RELATION/AUTH. */
  checklistType: string | null
  /** Có giá trị = hệ thống tự bù được mục này (gửi lại cho fillCoverageGaps). */
  itemKey: string | null
  whyNeeded: string | null
  runStatus: CoverageRunStatus | null
  nearMisses: CoverageNearMiss[]
  /** Chỉ true khi dòng MISSING này hệ thống bù được NGAY BÂY GIỜ (có case gốc hợp lệ để mượn, hoặc dựng thẳng từ đặc tả). */
  fillable: boolean
  /** Dòng MISSING có itemKey nhưng chưa bù được: vì sao + cần làm gì. */
  fillBlockedReason: string | null
}

export interface EndpointCoverage {
  endpointId: string
  path: string
  method: string
  testCaseCount: number
  coveragePercent: number
  /** % dòng vừa có test vừa đã chạy ra kết quả thật (PASSED/FAILED) - luôn nhỏ hơn hoặc bằng coveragePercent. */
  executedPercent: number
  schemaReadable: boolean
  dimensions: CoverageDimension[]
}

export interface ProjectCoverage {
  projectId: string
  overallCoveragePercent: number
  overallExecutedPercent: number
  endpoints: EndpointCoverage[]
}

export interface CoverageFillResult {
  createdCount: number
  created: CoverageTestCaseRef[]
  notFilled: { itemKey: string; label: string; reason: string }[]
  baselineTestCaseName: string | null
  /** Số case dựng thẳng từ đặc tả (endpoint không có đầu vào nào khác cần dữ liệu hợp lệ nên không cần case gốc) - nằm trong createdCount. */
  fromSpecCount: number
}

/** Module 15j - đếm theo đúng cách backend tính % (bỏ NOT_APPLICABLE khỏi mẫu số) - dùng chung cho mọi nơi hiển thị "X/Y mục". */
export function coverageCounts(endpoint: EndpointCoverage) {
  const relevant = endpoint.dimensions.filter((d) => d.status !== "NOT_APPLICABLE")
  const covered = relevant.filter((d) => d.status === "COVERED")
  const fillableRows = relevant.filter((d) => d.status === "MISSING" && d.itemKey && d.fillable)
  const blockedRows = relevant.filter((d) => d.status === "MISSING" && d.itemKey && !d.fillable)
  // Gom theo lý do để hiện 1 lần ("N mục: <lý do>") thay vì lặp lại cùng đoạn chữ dài ở từng dòng.
  const blockedReasons = new Map<string, number>()
  for (const row of blockedRows) {
    const reason = row.fillBlockedReason ?? "Hệ thống chưa bù tự động được mục này"
    blockedReasons.set(reason, (blockedReasons.get(reason) ?? 0) + 1)
  }
  return {
    relevant: relevant.length,
    covered: covered.length,
    executed: covered.filter((d) => d.runStatus === "PASSED" || d.runStatus === "FAILED").length,
    failing: covered.filter((d) => d.runStatus === "FAILED").length,
    missing: relevant.length - covered.length,
    /** Khoá các mục bù được ngay - gửi thẳng cho fillCoverageGaps. */
    fillableKeys: fillableRows.map((d) => d.itemKey as string),
    blockedCount: blockedRows.length,
    blockedReasons: [...blockedReasons.entries()].map(([reason, count]) => ({ reason, count })),
  }
}

/** Module 15c/15j - tất định (checklist EP/BVA + method/auth/dependency/lịch sử fail), không gọi AI. */
export function getProjectCoverage(projectId: string): Promise<ProjectCoverage> {
  return apiFetch<ProjectCoverage>(`/projects/${projectId}/coverage`)
}

export function getEndpointCoverage(projectId: string, endpointId: string): Promise<EndpointCoverage> {
  return apiFetch<EndpointCoverage>(`/projects/${projectId}/endpoints/${endpointId}/coverage`)
}

/** Module 15j - bù khoảng trống (tất định, 0 token). Không truyền itemKeys = bù tất cả mục còn thiếu. */
export function fillCoverageGaps(projectId: string, endpointId: string, itemKeys?: string[]): Promise<CoverageFillResult> {
  return apiFetch<CoverageFillResult>(`/projects/${projectId}/endpoints/${endpointId}/coverage/fill`, {
    method: "POST",
    body: JSON.stringify({ itemKeys: itemKeys ?? null }),
  })
}
