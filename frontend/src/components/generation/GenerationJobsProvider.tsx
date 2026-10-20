import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react"

import { JobItem } from "@/components/generation/JobItem"
import type { GenerationJob } from "@/lib/generationJobs"

interface TrackedJob {
  id: string
  projectId: string
  expanded: boolean
}

interface JobsContextValue {
  /** Bắt đầu theo dõi 1 job (mặc định mở panel giám sát). */
  track: (job: Pick<GenerationJob, "id" | "projectId">, expanded?: boolean) => void
}

const JobsContext = createContext<JobsContextValue | null>(null)
const STORAGE_KEY = "gen-jobs-v1"

function load(): TrackedJob[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    const parsed = raw ? (JSON.parse(raw) as TrackedJob[]) : []
    return Array.isArray(parsed) ? parsed.filter((j) => typeof j.id === "string" && typeof j.projectId === "string") : []
  } catch {
    return []
  }
}

/**
 * Giữ danh sách job sinh test đang theo dõi ở cấp layout nên khung giám sát sống qua mọi lần chuyển trang, và lưu id vào localStorage để còn thấy
 * sau khi tải lại trang (job vẫn chạy ở backend). Mỗi job tự polling và tự dọn khi 404.
 */
export function GenerationJobsProvider({ children }: { children: ReactNode }) {
  const [tracked, setTracked] = useState<TrackedJob[]>(load)

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(tracked))
    } catch {
      // trình duyệt chặn lưu trữ: vẫn hoạt động trong phiên này
    }
  }, [tracked])

  const track = useCallback((job: Pick<GenerationJob, "id" | "projectId">, expanded = true) => {
    setTracked((prev) => {
      const others = prev.filter((j) => j.id !== job.id).map((j) => (expanded ? { ...j, expanded: false } : j))
      return [...others, { id: job.id, projectId: job.projectId, expanded }]
    })
  }, [])

  const setExpanded = useCallback((id: string, expanded: boolean) => {
    setTracked((prev) => prev.map((j) => (j.id === id ? { ...j, expanded } : expanded ? { ...j, expanded: false } : j)))
  }, [])

  const dismiss = useCallback((id: string) => setTracked((prev) => prev.filter((j) => j.id !== id)), [])

  const value = useMemo(() => ({ track }), [track])

  return (
    <JobsContext.Provider value={value}>
      {children}
      {tracked.length > 0 && (
        <div className="pointer-events-none fixed right-4 bottom-4 z-50 flex max-w-[calc(100vw-2rem)] flex-col items-end gap-2">
          {tracked.map((j) => (
            <JobItem
              key={j.id}
              projectId={j.projectId}
              jobId={j.id}
              expanded={j.expanded}
              onExpand={() => setExpanded(j.id, true)}
              onMinimize={() => setExpanded(j.id, false)}
              onDismiss={() => dismiss(j.id)}
            />
          ))}
        </div>
      )}
    </JobsContext.Provider>
  )
}

export function useGenerationJobs(): JobsContextValue {
  const ctx = useContext(JobsContext)
  if (!ctx) throw new Error("useGenerationJobs phải dùng bên trong GenerationJobsProvider")
  return ctx
}
