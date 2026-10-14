import { apiFetch } from "@/lib/api"

export type CoverageDimensionStatus = "COVERED" | "MISSING" | "NOT_APPLICABLE"

export type RiskLevel = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL"

export interface CoverageDimension {
  category: string
  label: string
  status: CoverageDimensionStatus
  detail: string | null
}

export interface EndpointCoverage {
  endpointId: string
  path: string
  method: string
  coveragePercent: number
  riskScore: number
  riskLevel: RiskLevel
  riskReasons: string[]
  dimensions: CoverageDimension[]
}

export interface ProjectCoverage {
  projectId: string
  overallCoveragePercent: number
  overallRiskWeightedCoveragePercent: number
  endpoints: EndpointCoverage[]
}

/** Module 15c - tất định (method/auth/dependency/security exposure/lịch sử fail), không gọi AI. */
export function getProjectCoverage(projectId: string): Promise<ProjectCoverage> {
  return apiFetch<ProjectCoverage>(`/projects/${projectId}/coverage`)
}

export function getEndpointCoverage(projectId: string, endpointId: string): Promise<EndpointCoverage> {
  return apiFetch<EndpointCoverage>(`/projects/${projectId}/endpoints/${endpointId}/coverage`)
}
