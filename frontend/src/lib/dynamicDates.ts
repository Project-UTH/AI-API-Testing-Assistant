/** Placeholder ngày/giờ động (backend thay lúc chạy, UTC): {{today}}, {{today+30d}}, {{now+1h}}... Xem DynamicPlaceholderResolver. */
export const DATE_PLACEHOLDER_RE = /^(today|now)([+-]\d{1,4}[dhm])?$/

export const DATE_PRESETS: { token: string; hint: string }[] = [
  { token: "{{today}}", hint: "hôm nay (yyyy-MM-dd)" },
  { token: "{{today+30d}}", hint: "30 ngày sau - hạn/ngày hiệu lực hợp lệ" },
  { token: "{{today-1d}}", hint: "hôm qua - test ngày quá khứ" },
  { token: "{{now}}", hint: "giờ hiện tại (ISO, UTC)" },
  { token: "{{now+1h}}", hint: "1 giờ sau (ISO, UTC)" },
]

/** Tên (bên trong {{ }}) có phải ngày động HỢP LỆ không. today chỉ nhận đơn vị d. */
export function isDatePlaceholder(name: string): boolean {
  const m = DATE_PLACEHOLDER_RE.exec(name)
  return m !== null && !(m[1] === "today" && m[2] !== undefined && !m[2].endsWith("d"))
}

function distance(a: string, b: string): number {
  const dp = Array.from({ length: a.length + 1 }, (_, i) => [i, ...Array(b.length).fill(0)])
  for (let j = 1; j <= b.length; j++) dp[0][j] = j
  for (let i = 1; i <= a.length; i++)
    for (let j = 1; j <= b.length; j++)
      dp[i][j] = Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1))
  return dp[a.length][b.length]
}

/**
 * Cảnh báo gõ sai placeholder ngày: token bắt đầu today/now nhưng sai cú pháp ({{today+5x}}), hoặc tên rất giống today/now ({{todya}}).
 * Trả về danh sách { token, suggestion }. Không chặn lưu (tên lạ có thể là tham số hợp lệ); backend vẫn chặn cú pháp today/now sai.
 */
export function findDatePlaceholderTypos(text: string, knownNames: Set<string> = new Set()): { token: string; suggestion: string }[] {
  const result: { token: string; suggestion: string }[] = []
  for (const match of text.matchAll(/\{\{([^{}]+)\}\}/g)) {
    const name = match[1].trim()
    if (isDatePlaceholder(name) || knownNames.has(name)) continue
    if (/^(today|now)[+-]/.test(name)) {
      result.push({ token: match[0], suggestion: "{{today+30d}}" })
      continue
    }
    const base = /^\w+$/.test(name) ? name.toLowerCase() : ""
    const near = ["today", "now"].find((p) => base !== "" && base !== p && base.length >= 3 && distance(base, p) <= 2)
    if (near) result.push({ token: match[0], suggestion: `{{${near}}}` })
  }
  return result
}
