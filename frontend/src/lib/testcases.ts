import { apiFetch } from "@/lib/api"

export type TestCaseSource = "AI_GENERATED" | "MANUAL"

export interface TestCaseDependency {
  dependsOnTestCaseId: string
  dependsOnTestCaseName: string
  jsonPath: string
  placeholderName: string
}

export interface TestCaseDependencyInput {
  dependsOnTestCaseId: string
  jsonPath: string
  placeholderName: string
}

export interface DependencySuggestion {
  paramName: string
  sourceTestCaseId: string
  sourceLabel: string
  suggestedJsonPath: string
}

export interface TestCase {
  id: string
  endpointId: string
  endpointPath: string
  endpointMethod: string
  name: string
  description: string | null
  requestHeaders: string | null
  requestBody: string | null
  expectedStatus: number
  resolvedPath: string | null
  pathParamFallbacks: string | null
  source: TestCaseSource
  createdAt: string
  dependencies: TestCaseDependency[]
}

export interface TestCaseInput {
  name: string
  description?: string
  requestHeaders?: string
  requestBody?: string
  expectedStatus: number
  resolvedPath?: string
  pathParamFallbacks?: string
  dependencies?: TestCaseDependencyInput[]
}

export function generateTestCases(projectId: string, endpointId: string): Promise<TestCase[]> {
  return apiFetch<TestCase[]>(`/projects/${projectId}/endpoints/${endpointId}/generate-tests`, {
    method: "POST",
  })
}

export function listTestCases(projectId: string): Promise<TestCase[]> {
  return apiFetch<TestCase[]>(`/projects/${projectId}/test-cases`)
}

export function createTestCase(
  projectId: string,
  endpointId: string,
  input: TestCaseInput
): Promise<TestCase> {
  return apiFetch<TestCase>(`/projects/${projectId}/endpoints/${endpointId}/test-cases`, {
    method: "POST",
    body: JSON.stringify(input),
  })
}

export function updateTestCase(
  projectId: string,
  endpointId: string,
  testCaseId: string,
  input: TestCaseInput
): Promise<TestCase> {
  return apiFetch<TestCase>(`/projects/${projectId}/endpoints/${endpointId}/test-cases/${testCaseId}`, {
    method: "PUT",
    body: JSON.stringify(input),
  })
}

export function deleteTestCase(
  projectId: string,
  endpointId: string,
  testCaseId: string
): Promise<void> {
  return apiFetch<void>(`/projects/${projectId}/endpoints/${endpointId}/test-cases/${testCaseId}`, {
    method: "DELETE",
  })
}

export function getDependencySuggestions(
  projectId: string,
  endpointId: string,
  testCaseId: string
): Promise<DependencySuggestion[]> {
  return apiFetch<DependencySuggestion[]>(
    `/projects/${projectId}/endpoints/${endpointId}/test-cases/${testCaseId}/dependency-suggestions`
  )
}
