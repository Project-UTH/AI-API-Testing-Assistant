const API_BASE_URL = import.meta.env.VITE_API_BASE_URL as string

export interface ApiErrorResponse {
  code: string
  message: string
  timestamp: string
}

export class ApiError extends Error {
  code: string

  constructor(response: ApiErrorResponse) {
    super(response.message)
    this.code = response.code
  }
}

const AUTH_TOKEN_KEY = "auth_token"

export function getToken(): string | null {
  return localStorage.getItem(AUTH_TOKEN_KEY)
}

export function setToken(token: string): void {
  localStorage.setItem(AUTH_TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(AUTH_TOKEN_KEY)
}

async function rawFetch(path: string, options: RequestInit = {}): Promise<unknown> {
  const token = getToken()

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options.headers,
    },
  })

  if (response.status === 204) {
    return null
  }

  const body = await response.json().catch(() => null)

  if (!response.ok) {
    throw new ApiError(
      body ?? {
        code: "INTERNAL_ERROR",
        message: "Đã xảy ra lỗi hệ thống, vui lòng thử lại sau",
        timestamp: new Date().toISOString(),
      }
    )
  }

  return body
}

export async function apiFetch<T>(
  path: string,
  options: RequestInit = {}
): Promise<T> {
  const body = await rawFetch(path, options)
  if (body === null) {
    return undefined as T
  }
  return (body as { data: T }).data
}

export interface PagedResult<T> {
  data: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export async function apiFetchPaged<T>(
  path: string,
  options: RequestInit = {}
): Promise<PagedResult<T>> {
  const body = await rawFetch(path, options)
  return body as PagedResult<T>
}
