import { useEffect, useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { Timer } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Label } from "@/components/ui/label"
import { fetchReviewTiming, medianSeconds, setTimingEnabled, TIMING_SAVED_EVENT, useTimingEnabled } from "@/lib/reviewTiming"

/**
 * Công tắc đo thời gian duyệt luật (E2). Mặc định TẮT; khi bật hiện dòng thông báo công khai nói rõ đo gì và không đo gì, và có nút xuất dữ liệu ẩn danh.
 */
export function ReviewTimingBanner({ projectId }: { projectId: string }) {
  const enabled = useTimingEnabled()
  const [exporting, setExporting] = useState(false)
  const { data: rows = [], refetch } = useQuery({
    queryKey: ["rules", projectId, "review-timing"],
    queryFn: () => fetchReviewTiming(projectId),
    enabled,
  })
  const median = medianSeconds(rows)
  useEffect(() => {
    if (!enabled) return
    const onSaved = () => void refetch()
    window.addEventListener(TIMING_SAVED_EVENT, onSaved)
    return () => window.removeEventListener(TIMING_SAVED_EVENT, onSaved)
  }, [enabled, refetch])

  async function exportJson() {
    setExporting(true)
    try {
      const data = await fetchReviewTiming(projectId)
      const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }))
      const link = document.createElement("a")
      link.href = url
      link.download = "review-timing.json"
      link.click()
      URL.revokeObjectURL(url)
    } finally {
      setExporting(false)
    }
  }

  return (
    <div className="mt-4 rounded-md border border-border p-3 text-xs">
      <Label className="items-start gap-2 text-xs font-normal">
        <Checkbox checked={enabled} onCheckedChange={(v) => setTimingEnabled(v === true)} className="mt-0.5" />
        <span>
          <strong>Đo thời gian duyệt luật (thực nghiệm E2, tuỳ chọn).</strong> Chỉ đo khi bạn bật.
        </span>
      </Label>
      {enabled && (
        <div role="status" className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-muted-foreground">
          <Timer className="size-3.5" aria-hidden />
          <span>
            Phiên duyệt đang được đo thời gian cho đánh giá E2: chỉ ghi thời gian mỗi luật được xem khi tab đang mở và có focus, cùng quyết định (duyệt, loại,
            sửa). Không ghi chuột, bàn phím hay nội dung luật. Duyệt hàng loạt không được đo.
          </span>
          <span>
            Đã ghi {rows.length} quyết định{median !== null ? `, trung vị ${median} giây` : ""}.
          </span>
          <Button type="button" size="xs" variant="outline" disabled={rows.length === 0 || exporting} onClick={exportJson}>
            Xuất dữ liệu ẩn danh (JSON)
          </Button>
        </div>
      )}
    </div>
  )
}
