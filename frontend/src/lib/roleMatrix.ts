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

export function getRoleMatrix(projectId: string): Promise<RoleMatrix> {
  return apiFetch<RoleMatrix>(`/projects/${projectId}/role-matrix`)
}

export function updateRoleMatrix(projectId: string, rules: RoleRuleInput[]): Promise<{ updated: number }> {
  return apiFetch<{ updated: number }>(`/projects/${projectId}/role-matrix`, {
    method: "PUT",
    body: JSON.stringify({ rules }),
  })
}
