
/** Donut đơn giản, tự vẽ SVG - theo đúng pattern PassRateDonut (components/shared/PassRateCard.tsx), không dùng thư viện chart. */
function MiniDonut({ percent, color }: { percent: number; color: string }) {
  const size = 72
  const strokeWidth = 8
  const radius = (size - strokeWidth) / 2
  const circumference = 2 * Math.PI * radius
  const dash = (percent / 100) * circumference

  return (
    <div className="relative shrink-0" style={{ width: size, height: size }}>
      <svg width={size} height={size} className="-rotate-90">
        <circle cx={size / 2} cy={size / 2} r={radius} fill="none" stroke="var(--color-border)" strokeWidth={strokeWidth} />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke={color}
          strokeWidth={strokeWidth}
          strokeDasharray={`${dash} ${circumference - dash}`}
          strokeLinecap="round"
        />
      </svg>
      <div className="absolute inset-0 flex items-center justify-center">
        <span className="text-sm font-bold tabular-nums text-foreground">{percent}%</span>
      </div>
    </div>
  )
}

interface CoverageSummaryProps {
  coveragePercent: number
  executedPercent: number
}

/**
 * Hai donut cạnh nhau: "đã có test case" vs "đã chạy có kết quả" - có test mới là thiết kế, chạy ra Pass/Fail
 * mới là đã kiểm thật (Module 15c + 15j).
 */
export function CoverageSummary({ coveragePercent, executedPercent }: CoverageSummaryProps) {
  return (
    <div className="flex flex-wrap items-center gap-6 rounded-2xl border border-border bg-card p-5 shadow-sm">
      <div className="flex items-center gap-3">
        <MiniDonut percent={coveragePercent} color="#10b981" />
        <div className="text-sm">
          <p className="font-medium text-foreground">Đã có test case</p>
          <p className="text-xs text-muted-foreground">% mục cần kiểm (tự tính từ spec) đã có test đạt</p>
        </div>
      </div>
      <div className="flex items-center gap-3">
        <MiniDonut percent={executedPercent} color="#0ea5e9" />
        <div className="text-sm">
          <p className="font-medium text-foreground">Đã chạy có kết quả</p>
          <p className="text-xs text-muted-foreground">% mục mà test đã chạy ra Pass/Fail thật</p>
        </div>
      </div>
      {executedPercent < coveragePercent && (
        <p className="w-full text-xs text-sky-600 dark:text-sky-400">
          {coveragePercent}% mục đã có test nhưng mới {executedPercent}% đã chạy ra kết quả - phần chênh lệch mới là thiết
          kế, chưa được kiểm thật trên API.
        </p>
      )}
    </div>
  )
}
