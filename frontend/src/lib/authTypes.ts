import type { TargetAuthType } from "@/lib/endpoints"

/** Nhãn + gợi ý nhập giá trị cho từng kiểu xác thực của API đích (dùng chung mọi hộp thoại). */
export const AUTH_TYPE_OPTIONS: { value: TargetAuthType; label: string }[] = [
  { value: "NONE", label: "Không" },
  { value: "BEARER_TOKEN", label: "Bearer Token" },
  { value: "API_KEY", label: "API Key (header X-API-Key)" },
  { value: "BASIC_AUTH", label: "Basic Auth (tên:mật khẩu)" },
  { value: "CUSTOM_HEADER", label: "Header tuỳ ý (Tên: giá trị)" },
  { value: "QUERY_PARAM", label: "Khoá qua query string (tên=giá trị)" },
]

export const AUTH_TYPE_LABEL: Record<TargetAuthType, string> = {
  NONE: "Không",
  API_KEY: "API Key",
  BEARER_TOKEN: "Bearer Token",
  BASIC_AUTH: "Basic Auth",
  CUSTOM_HEADER: "Header tuỳ ý",
  QUERY_PARAM: "Khoá qua query string",
}

export function authValueLabel(type: TargetAuthType): string {
  switch (type) {
    case "API_KEY":
      return "Giá trị API Key"
    case "BASIC_AUTH":
      return "Tên đăng nhập và mật khẩu"
    case "CUSTOM_HEADER":
      return "Header xác thực"
    case "QUERY_PARAM":
      return "Tham số xác thực"
    default:
      return "Giá trị Bearer Token"
  }
}

export function authValueHint(type: TargetAuthType): string | null {
  switch (type) {
    case "BASIC_AUTH":
      return "Nhập dạng tên:mật khẩu, ví dụ admin:secret. Hệ thống tự mã hoá base64 khi gửi."
    case "CUSTOM_HEADER":
      return "Nhập dạng Tên-Header: giá trị, ví dụ X-Auth-Token: abc123 hoặc api-key: abc123."
    case "QUERY_PARAM":
      return "Nhập dạng tên_tham_số=giá trị, ví dụ api_key=abc123. Hệ thống gắn ?api_key=abc123 vào mọi request."
    default:
      return null
  }
}
