import { apiFetch } from "@/lib/api"

export type AuthzExpectation = "ALLOW" | "DENY"

export interface RoleMatrix {
  profiles: { id: string; name: string; roleKey: string }[]
  endpoints: { endpointId: string; method: string; path: string; rules: Record<string, AuthzExpectation> }[]
}

export interface RoleRuleInput {
  endpointIds?: string[]
  pathPrefix?: string
  method?: string
  profileId: string
  /** null = xoá khai báo. */
  expectation: AuthzExpectation | null
}

/** Module 17 (S5) - kết quả xem trước (không ghi gì) khi áp quy tắc: mỗi quy tắc khớp bao nhiêu endpoint, ghi đè gì. */
export interface RoleRuleReport {
  ruleIndex: number
  profileId: string
  profileName: string
  expectation: AuthzExpectation | null
  matchedEndpoints: number
  samplePaths: string[]
  overridesEarlier: number
  overridesExisting: number
  matchedNothing: boolean
}

export interface RoleRulesPreview {
  cellsToCreate: number
  cellsToChange: number
  cellsToDelete: number
  totalCells: number
  rules: RoleRuleReport[]
}

export function previewRoleMatrix(projectId: string, rules: RoleRuleInput[]): Promise<RoleRulesPreview> {
  return apiFetch<RoleRulesPreview>(`/projects/${projectId}/role-matrix/preview`, {
    method: "POST",
    body: JSON.stringify({ rules }),
  })
}

export function getRoleMatrix(projectId: string): Promise<RoleMatrix> {
  return apiFetch<RoleMatrix>(`/projects/${projectId}/role-matrix`)
}

export function updateRoleMatrix(projectId: string, rules: RoleRuleInput[]): Promise<{ updated: number }> {
  return apiFetch<{ updated: number }>(`/projects/${projectId}/role-matrix`, {
    method: "PUT",
    body: JSON.stringify({ rules }),
  })
}
