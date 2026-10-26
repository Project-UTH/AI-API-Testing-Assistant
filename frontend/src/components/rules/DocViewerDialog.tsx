import { useEffect, useMemo, useRef } from "react"
import { useQuery } from "@tanstack/react-query"

import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { ApiError } from "@/lib/api"
import { getRequirementDocContent } from "@/lib/requirementDocs"

interface Props {
  projectId: string
  /** Tài liệu + đoạn cần tô sáng; null = đóng. */
  target: { documentId: string; quote: string; offset: number | null } | null
  onClose: () => void
}

/** Xem tài liệu gốc với đoạn trích được tô sáng theo vị trí backend đã kiểm (không tự tìm chuỗi con để khỏi tô nhầm chỗ trùng). */
export function DocViewerDialog({ projectId, target, onClose }: Props) {
  const markRef = useRef<HTMLElement | null>(null)
  const { data, isLoading, error } = useQuery({
    queryKey: ["requirement-doc-content", projectId, target?.documentId],
    queryFn: () => getRequirementDocContent(projectId, target!.documentId),
    enabled: target !== null,
    staleTime: 60_000,
  })

  const parts = useMemo(() => {
    if (!data || !target || target.offset === null) return null
    const start = target.offset
    const end = start + target.quote.length
    // Tài liệu đã được thay đổi sau khi trích: nếu đoạn tại vị trí cũ không còn khớp thì không tô sai chỗ.
    if (data.content.slice(start, end) !== target.quote) return { stale: true as const }
    return { stale: false as const, before: data.content.slice(0, start), mark: data.content.slice(start, end), after: data.content.slice(end) }
  }, [data, target])

  useEffect(() => {
    if (parts && !parts.stale) markRef.current?.scrollIntoView({ block: "center" })
  }, [parts])

  const notFound = error instanceof ApiError && error.code === "DOCUMENT_NOT_FOUND"

  return (
    <Dialog open={target !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>{data?.fileName ?? "Tài liệu nghiệp vụ"}</DialogTitle>
          <DialogDescription>Đoạn được tô sáng là trích dẫn mà mục dựa vào. Hãy đọc cả ngữ cảnh xung quanh trước khi duyệt.</DialogDescription>
        </DialogHeader>
        {isLoading && <p className="text-sm text-muted-foreground">Đang tải tài liệu...</p>}
        {error && (
          <p role="alert" className="text-sm text-destructive">
            {notFound ? "Tài liệu đã bị xoá hoặc đã được thay bản mới. Đoạn trích của mục vẫn được giữ nguyên bên dưới." : "Không tải được tài liệu."}
          </p>
        )}
        {(error || parts?.stale) && target && (
          <blockquote className="border-l-2 border-emerald-500/50 pl-2 text-sm">“{target.quote}”</blockquote>
        )}
        {parts?.stale && <p className="text-xs text-amber-700 dark:text-amber-400">Nội dung tài liệu đã đổi, không còn khớp vị trí đoạn trích.</p>}
        {parts && !parts.stale && (
          <pre className="max-h-[60vh] overflow-auto rounded-md border border-border bg-muted/30 p-3 font-sans text-sm leading-relaxed whitespace-pre-wrap">
            {parts.before}
            <mark ref={markRef} aria-label="Đoạn trích của mục" className="rounded bg-yellow-300/60 px-0.5 text-foreground dark:bg-yellow-500/40">
              {parts.mark}
            </mark>
            {parts.after}
          </pre>
        )}
      </DialogContent>
    </Dialog>
  )
}
