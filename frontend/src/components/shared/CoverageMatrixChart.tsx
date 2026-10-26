import { cn } from "@/lib/utils"
import { getTestStateDisplay, TEST_STATE_ORDER, type CoverageOverview, type EndpointTestState } from "@/lib/coverage"

interface CoverageOverviewCardProps {
  overview: CoverageOverview
  /** Trạng thái đang lọc (bấm lại chip đang chọn để bỏ lọc). */
  selectedState?: string
  onSelectState: (state: EndpointTestState | null) => void
}

function percentOf(part: number, whole: number) {
  return whole === 0 ? 0 : Math.round((part * 1000) / whole) / 10
}

/**
 * 17q G2 - tổng quan Coverage của project lấy ENDPOINT làm đơn vị: số chính là "X/N endpoint đã kiểm thử" + phân bố 4 trạng thái
 * (thanh xếp chồng + chip bấm để lọc). Không còn % gộp làm số chính: endpoint chưa sinh test không bị tính là "0%"; % (số phụ)
 * chỉ tính trên endpoint đã sinh và luôn kèm mẫu số. Phân bố luôn tính trên toàn project, không đổi theo bộ lọc.
 */
export function CoverageOverviewCard({ overview, selectedState, onSelectState }: CoverageOverviewCardProps) {
  const total = overview.endpointCount
  const counts = TEST_STATE_ORDER.map((state) => ({ state, count: overview.stateCounts?.[state] ?? 0 }))
  const tested = overview.testedEndpoints ?? 0
  const generatedEndpoints = total - (overview.stateCounts?.NOT_GENERATED ?? 0)
  const generatedRelevant = overview.generatedRelevantCount ?? 0

  return (
    <div className="flex flex-col gap-3 rounded-2xl border border-border bg-card p-5 shadow-sm">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <p className="text-base font-semibold">
          <span className="tabular-nums">
            {tested}/{total}
          </span>{" "}
          endpoint đã kiểm thử
          <span className="ml-2 text-xs font-normal text-muted-foreground">(đã chạy ra kết luận ít nhất 1 lần)</span>
        </p>
        {generatedEndpoints > 0 && (
          <p
            className="text-xs text-muted-foreground"
            title="Chỉ tính trên các endpoint đã sinh test - endpoint chưa sinh không bị tính là 0%"
          >
            Có test {percentOf(overview.generatedCoveredCount ?? 0, generatedRelevant)}% · đã chạy{" "}
            {percentOf(overview.generatedExecutedCount ?? 0, generatedRelevant)}% · trên {generatedRelevant} mục của {generatedEndpoints}{" "}
            endpoint đã sinh
          </p>
        )}
      </div>

      {total > 0 && (
        <div className="flex h-2.5 w-full overflow-hidden rounded-full bg-muted" aria-hidden="true">
          {counts.map(({ state, count }) =>
            count > 0 ? (
              <div key={state} className={getTestStateDisplay(state)?.bar} style={{ width: `${(count * 100) / total}%` }} />
            ) : null
          )}
        </div>
      )}

      <div className="flex flex-wrap gap-2" role="group" aria-label="Lọc endpoint theo trạng thái kiểm thử">
        {counts.map(({ state, count }) => {
          const display = getTestStateDisplay(state)!
          const active = selectedState === state
          return (
            <button
              key={state}
              type="button"
              aria-pressed={active}
              title={`${display.title}. Bấm để ${active ? "bỏ lọc" : "lọc danh sách"}.`}
              onClick={() => onSelectState(active ? null : state)}
              className={cn(
                "inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs transition-colors",
                active ? "border-primary bg-primary/10 font-medium" : "border-border hover:bg-muted"
              )}
            >
              <span className={cn("h-2 w-2 rounded-full", display.dot)} />
              {display.label}
              <span className="tabular-nums font-semibold">{count}</span>
            </button>
          )
        })}
      </div>

      <p className="text-xs text-muted-foreground">Độ phủ đo mức đủ tình huống theo spec, không đo khả năng bắt lỗi.</p>
    </div>
  )
}
