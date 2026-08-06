import { apiFetch } from "@/lib/api"

export interface TestCase {
  id: string
  endpointId: string
  name: string
  description: string | null
  requestHeaders: string | null
  requestBody: string | null
  expectedStatus: number
  createdAt: string
}

export function generateTestCases(projectId: string, endpointId: string): Promise<TestCase[]> {
  return apiFetch<TestCase[]>(`/projects/${projectId}/endpoints/${endpointId}/generate-tests`, {
    method: "POST",
  })
}
