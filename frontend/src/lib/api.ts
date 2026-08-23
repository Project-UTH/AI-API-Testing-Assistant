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

/** Đọc email người dùng từ payload JWT hiện có, không cần gọi API riêng. */
export function getCurrentUserEmail(): string | null {
  const token = getToken()
  if (!token) return null
  try {
    const payload = token.split(".")[1]
    const decoded = JSON.parse(atob(payload.replace(/-/g, "+").replace(/_/g, "/")))
    return typeof decoded.sub === "string" ? decoded.sub : null
  } catch {
    return null
  }
}

async function rawFetch(path: string, options: RequestInit = {}): Promise<unknown> {
  const token = getToken()
  const isFormData = options.body instanceof FormData

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers: {
      // FormData: để browser tự set Content-Type kèm boundary, không set thủ công.
      ...(isFormData ? {} : { "Content-Type": "application/json" }),
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

/** Tải file nhị phân (.xlsx export) - không dùng apiFetch vì response không phải JSON {data, meta}. */
export async function apiFetchBlob(path: string): Promise<{ blob: Blob; filename: string | null }> {
  const token = getToken()
  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })

  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new ApiError(
      body ?? {
        code: "INTERNAL_ERROR",
        message: "Đã xảy ra lỗi hệ thống, vui lòng thử lại sau",
        timestamp: new Date().toISOString(),
      }
    )
  }

  const disposition = response.headers.get("Content-Disposition")
  const filenameMatch = disposition?.match(/filename="([^"]+)"/)
  return { blob: await response.blob(), filename: filenameMatch?.[1] ?? null }
}

/** Kích hoạt tải 1 blob về máy qua thẻ <a> ẩn - dùng chung cho mọi nút "Xuất Excel" trong app. */
export async function downloadFile(path: string, fallbackFilename: string): Promise<void> {
  const { blob, filename } = await apiFetchBlob(path)
  const url = URL.createObjectURL(blob)
  const link = document.createElement("a")
  link.href = url
  link.download = filename ?? fallbackFilename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
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
