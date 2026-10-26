import { useEffect, useMemo } from "react"
import { useQuery } from "@tanstack/react-query"

import { Checkbox } from "@/components/ui/checkbox"
import { Label } from "@/components/ui/label"
import { getEndpointCoverage, type CoverageDimension } from "@/lib/coverage"

const CATEGORY_LABEL: Record<string, string> = {
  POSITIVE: "Positive (dữ liệu hợp lệ)",
  NEGATIVE: "Negative (dữ liệu sai)",
  BOUNDARY: "Boundary (giá trị biên)",
  SECURITY: "Security (token, quyền)",
}
const CATEGORY_ORDER = ["POSITIVE", "NEGATIVE", "BOUNDARY", "SECURITY"]

interface Props {
  projectId: string
  endpointId: string
  /** Khoá các mục hệ thống bạn chọn để sinh test (state do trang giữ để nút "Sinh test" cuối cùng dùng). */
  selected: Set<string>
  onChange: (next: Set<string>) => void
}

/**
 * Checklist CỦA HỆ THỐNG (EP/BVA/bảo mật suy ra từ spec, 0 token) cho 1 endpoint, hiện cùng màn duyệt với mục AI: bạn xem từng mục rồi tick những
 * mục muốn sinh test. Chưa tick thì chưa sinh. Mục "đã có test" chỉ để xem; mục hệ thống chưa bù được kèm lý do.
 */
export function SystemChecklistReview({ projectId, endpointId, selected, onChange }: Props) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["endpoint-coverage", projectId, endpointId],
    queryFn: () => getEndpointCoverage(projectId, endpointId),
  })

  const items = useMemo(() => (data?.dimensions ?? []).filter((d) => d.checklistType !== null && d.status !== "NOT_APPLICABLE"), [data])
  const blockedReasons = useMemo(() => {
    const counts = new Map<string, number>()
    for (const d of items) {
      if (d.status === "MISSING" && !d.fillable) counts.set(d.fillBlockedReason ?? "Hệ thống chưa sinh tự động được mục này", (counts.get(d.fillBlockedReason ?? "Hệ thống chưa sinh tự động được mục này") ?? 0) + 1)
    }
    return [...counts.entries()]
  }, [items])
  const fillable = useMemo(() => items.filter((d) => d.status === "MISSING" && d.itemKey && d.fillable), [items])

  // Lần đầu có dữ liệu: tick sẵn các mục hệ thống bù được (bỏ tick được); chỉ chạy khi chưa chọn gì để không ghi đè lựa chọn của bạn.
  const fillableSignature = fillable.map((d) => d.itemKey).join("|")
  useEffect(() => {
    if (fillable.length > 0 && selected.size === 0) onChange(new Set(fillable.map((d) => d.itemKey as string)))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fillableSignature])

  const groups = CATEGORY_ORDER.map((category) => ({ category, rows: items.filter((d) => d.category === category) })).filter((g) => g.rows.length > 0)
  const toggle = (key: string, on: boolean) => {
    const next = new Set(selected)
    if (on) next.add(key)
    else next.delete(key)
    onChange(next)
  }

  return (
    <section aria-label="Checklist của hệ thống" className="mt-4 rounded-md border border-border p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-semibold">Checklist của hệ thống (0 token)</h3>
        {fillable.length > 0 && (
          <span className="flex items-center gap-2 text-xs">
            <span className="text-muted-foreground">
              Đã chọn {selected.size}/{fillable.length}
            </span>
            <button type="button" className="text-primary underline underline-offset-2" onClick={() => onChange(new Set(fillable.map((d) => d.itemKey as string)))}>
              Chọn tất cả
            </button>
            <button type="button" className="text-primary underline underline-offset-2" onClick={() => onChange(new Set())}>
              Bỏ chọn
            </button>
          </span>
        )}
      </div>
      <p className="mt-1 text-xs text-muted-foreground">
        Các mục kiểm thử hệ thống suy ra từ spec của endpoint này. Tick mục bạn muốn có test; hệ thống chỉ sinh test cho mục đã tick khi bạn bấm "Sinh test" ở cuối.
      </p>
      {blockedReasons.map(([reason, count]) => (
        <p key={reason} className="mt-2 rounded-md border border-amber-500/30 bg-amber-500/5 p-2 text-xs">
          <strong>{count} mục chưa sinh được:</strong> {reason}
        </p>
      ))}
      {isLoading && <p className="mt-2 text-xs text-muted-foreground">Đang tải checklist hệ thống...</p>}
      {isError && <p className="mt-2 text-xs text-destructive">Không tải được checklist hệ thống của endpoint.</p>}
      {data && items.length === 0 && <p className="mt-2 text-xs text-muted-foreground">Endpoint này chưa có mục checklist hệ thống nào.</p>}
      <div className="mt-2 flex flex-col gap-3">
        {groups.map((g) => (
          <div key={g.category}>
            <h4 className="text-xs font-medium text-muted-foreground">
              {CATEGORY_LABEL[g.category] ?? g.category} ({g.rows.length})
            </h4>
            <ul className="mt-1 flex flex-col gap-1">
              {g.rows.map((row: CoverageDimension, i) => {
                const covered = row.status === "COVERED"
                const canPick = row.status === "MISSING" && row.itemKey !== null && row.fillable
                return (
                  <li key={`${g.category}-${i}`} className="text-xs">
                    <Label className="items-start gap-2 font-normal">
                      <Checkbox
                        className="mt-0.5"
                        disabled={!canPick}
                        checked={covered || (canPick && selected.has(row.itemKey as string))}
                        onCheckedChange={(v) => canPick && toggle(row.itemKey as string, v === true)}
                        aria-label={row.label}
                      />
                      <span className="flex min-w-0 flex-col">
                        <span>
                          {row.label}
                          {covered && <span className="ml-1.5 rounded-full bg-emerald-500/10 px-1.5 py-0.5 text-[11px] text-emerald-700 dark:text-emerald-400">đã có test</span>}
                          {row.status === "MISSING" && !canPick && (
                            <span className="ml-1.5 rounded-full bg-amber-500/10 px-1.5 py-0.5 text-[11px] text-amber-700 dark:text-amber-400">chưa sinh được</span>
                          )}
                        </span>
                      </span>
                    </Label>
                  </li>
                )
              })}
            </ul>
          </div>
        ))}
      </div>
    </section>
  )
}
