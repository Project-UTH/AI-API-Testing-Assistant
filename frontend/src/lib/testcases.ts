import { apiFetch } from "@/lib/api"

export type TestCaseSource = "AI_GENERATED" | "MANUAL" | "SECURITY"

export type TestCaseAuthOverride = "DEFAULT" | "NONE" | "INVALID"

export type AssertionOperator = "EQUALS" | "CONTAINS" | "EXISTS" | "TYPE"

export interface TestCaseAssertion {
  jsonPath: string
  operator: AssertionOperator
  expectedValue: string | null
}

export interface TestCaseAssertionInput {
  jsonPath: string
  operator: AssertionOperator
  expectedValue: string | null
}

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
  authOverride: TestCaseAuthOverride
  createdAt: string
  dependencies: TestCaseDependency[]
  assertions: TestCaseAssertion[]
}

export interface TestCaseInput {
  name: string
  description?: string
  requestHeaders?: string
  requestBody?: string
  expectedStatus: number
  resolvedPath?: string
  pathParamFallbacks?: string
  authOverride?: TestCaseAuthOverride
  dependencies?: TestCaseDependencyInput[]
  assertions?: TestCaseAssertionInput[]
}

export interface GenerateTestCasesOptions {
  includeSecurity?: boolean
  includeAssertions?: boolean
}

export function generateTestCases(
  projectId: string,
  endpointId: string,
  options?: GenerateTestCasesOptions
): Promise<TestCase[]> {
  return apiFetch<TestCase[]>(`/projects/${projectId}/endpoints/${endpointId}/generate-tests`, {
    method: "POST",
    body: JSON.stringify({
      includeSecurity: options?.includeSecurity ?? false,
      includeAssertions: options?.includeAssertions ?? false,
    }),
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
