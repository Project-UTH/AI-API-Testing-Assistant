import { useEffect, useSyncExternalStore, type RefObject } from "react"

import { apiFetch } from "@/lib/api"

/**
 * 17q G3.5 - đo thời gian duyệt luật cho thực nghiệm E2. MẶC ĐỊNH TẮT: chỉ đo khi người dùng chủ động bật (công tắc hiện công khai ở tab Nghiệp vụ).
 * Chỉ đo thời gian luật nằm trong khung nhìn khi tab đang hiện và cửa sổ đang có focus; KHÔNG ghi chuột/bàn phím/nội dung. Gửi 1 lần khi có
 * quyết định (duyệt, loại, sửa). Dữ liệu chỉ nằm ở máy chủ của chính project này và xuất ra ẩn danh.
 */
const STORAGE_KEY = "review-timing-enabled-v1"

export type TimingDecision = "APPROVED" | "REJECTED" | "EDITED"

export interface ReviewTimingRow {
  ruleSource: string
  template: string
  hadQuote: boolean
  decision: TimingDecision
  focusedMs: number
  decidedAt: string
}

interface Entry {
  ms: number
  since: number | null
  inView: boolean
}

const listeners = new Set<() => void>()
const entries = new Map<string, Entry>()

export function isTimingEnabled(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) === "1"
  } catch {
    return false
  }
}

export function setTimingEnabled(on: boolean): void {
  try {
    localStorage.setItem(STORAGE_KEY, on ? "1" : "0")
  } catch {
    // trình duyệt chặn lưu trữ: công tắc chỉ có tác dụng trong phiên này (không đo gì khi tắt)
  }
  if (!on) entries.clear()
  listeners.forEach((l) => l())
}

export function useTimingEnabled(): boolean {
  return useSyncExternalStore(
    (cb) => {
      listeners.add(cb)
      return () => listeners.delete(cb)
    },
    isTimingEnabled,
    () => false
  )
}

function focused(): boolean {
  return document.visibilityState === "visible" && document.hasFocus()
}

function refresh(entry: Entry, now: number): void {
  const active = entry.inView && focused()
  if (active && entry.since === null) entry.since = now
  if (!active && entry.since !== null) {
    entry.ms += now - entry.since
    entry.since = null
  }
}

function setRuleInView(ruleId: string, inView: boolean): void {
  if (!isTimingEnabled()) return
  const entry = entries.get(ruleId) ?? { ms: 0, since: null, inView: false }
  entry.inView = inView
  refresh(entry, performance.now())
  entries.set(ruleId, entry)
}

// Tab ẩn hoặc cửa sổ mất focus: dừng đồng hồ của mọi luật đang chạy (và chạy lại khi quay về).
function refreshAll(): void {
  const now = performance.now()
  entries.forEach((entry) => refresh(entry, now))
}
if (typeof document !== "undefined") {
  document.addEventListener("visibilitychange", refreshAll)
  window.addEventListener("focus", refreshAll)
  window.addEventListener("blur", refreshAll)
}

/** Lấy (và xoá) thời gian có focus đã tích luỹ của luật; null nếu không đo. */
function takeFocusedMs(ruleId: string): number | null {
  if (!isTimingEnabled()) return null
  const entry = entries.get(ruleId)
  entries.delete(ruleId)
  if (!entry) return null
  refresh({ ...entry, inView: false }, performance.now())
  const running = entry.since === null ? 0 : performance.now() - entry.since
  return Math.round(entry.ms + running)
}

/** Gọi sau khi quyết định thành công. Lỗi gửi chỉ bỏ qua: việc đo không được làm hỏng thao tác duyệt. */
export function reportReviewTiming(projectId: string, ruleId: string, decision: TimingDecision): void {
  const focusedMs = takeFocusedMs(ruleId)
  if (focusedMs === null) return
  apiFetch<void>(`/projects/${projectId}/business-rules/${ruleId}/review-timing`, {
    method: "POST",
    body: JSON.stringify({ decision, focusedMs: Math.min(focusedMs, 3_600_000) }),
  })
    .then(() => window.dispatchEvent(new Event(TIMING_SAVED_EVENT)))
    .catch(() => undefined)
}

/** Phát sau khi máy chủ đã LƯU 1 bản ghi đo, để banner làm mới số liệu đúng lúc (không làm mới trước khi lưu xong). */
export const TIMING_SAVED_EVENT = "review-timing-saved"

export function fetchReviewTiming(projectId: string): Promise<ReviewTimingRow[]> {
  return apiFetch<ReviewTimingRow[]>(`/projects/${projectId}/business-rules/review-timing`)
}

/** Đặt vào phần tử của 1 luật: đồng hồ chạy khi luật hiện ≥ 50% trong khung nhìn. */
export function useRuleTimer(ref: RefObject<Element | null>, ruleId: string): void {
  const enabled = useTimingEnabled()
  useEffect(() => {
    const el = ref.current
    if (!enabled || !el || typeof IntersectionObserver === "undefined") return
    const observer = new IntersectionObserver((records) => setRuleInView(ruleId, records[records.length - 1].isIntersecting), { threshold: 0.5 })
    observer.observe(el)
    return () => {
      observer.disconnect()
      setRuleInView(ruleId, false)
    }
  }, [enabled, ref, ruleId])
}

export function medianSeconds(rows: ReviewTimingRow[]): number | null {
  if (rows.length === 0) return null
  const sorted = rows.map((r) => r.focusedMs).sort((a, b) => a - b)
  const mid = Math.floor(sorted.length / 2)
  const ms = sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2
  return Math.round(ms / 100) / 10
}
