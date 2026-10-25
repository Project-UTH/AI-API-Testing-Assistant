import { apiFetch, apiFetchPaged, type PagedResult } from "@/lib/api"
import type { TargetAuthType } from "@/lib/endpoints"

export interface Project {
  id: string
  name: string
  description: string | null
  createdAt: string
  targetBaseUrl: string | null
  targetAuthType: TargetAuthType
  /** Mã API đích trả khi thiếu/sai token: 403 hoặc null (= 401, chuẩn). */
  unauthenticatedStatus: number | null
}

export interface TargetAuthUpdateInput {
  authType: TargetAuthType
  authValue?: string
}

export interface ProjectInput {
  name: string
  description?: string
}

export function listProjects(size = 20): Promise<PagedResult<Project>> {
  return apiFetchPaged<Project>(`/projects?page=0&size=${size}&sort=createdAt,desc`)
}

export function getProject(id: string): Promise<Project> {
  return apiFetch<Project>(`/projects/${id}`)
}

export function createProject(input: ProjectInput): Promise<Project> {
  return apiFetch<Project>("/projects", {
    method: "POST",
    body: JSON.stringify(input),
  })
}

export function updateProject(id: string, input: ProjectInput): Promise<Project> {
  return apiFetch<Project>(`/projects/${id}`, {
    method: "PUT",
    body: JSON.stringify(input),
  })
}

export function deleteProject(id: string): Promise<void> {
  return apiFetch<void>(`/projects/${id}`, {
    method: "DELETE",
  })
}

export function updateApiConventions(id: string, input: { unauthenticatedStatus: number | null }): Promise<Project> {
  return apiFetch<Project>(`/projects/${id}/api-conventions`, { method: "PUT", body: JSON.stringify(input) })
}

export function updateTargetAuth(id: string, input: TargetAuthUpdateInput): Promise<Project> {
  return apiFetch<Project>(`/projects/${id}/target-auth`, {
    method: "PUT",
    body: JSON.stringify(input),
  })
}
