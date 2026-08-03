import { apiFetch, apiFetchPaged, type PagedResult } from "@/lib/api"

export interface Project {
  id: string
  name: string
  description: string | null
  createdAt: string
}

export interface ProjectInput {
  name: string
  description?: string
}

export function listProjects(): Promise<PagedResult<Project>> {
  return apiFetchPaged<Project>("/projects?page=0&size=20&sort=createdAt,desc")
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
