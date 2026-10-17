import { cn } from "@/lib/utils"
import type { RiskLevel } from "@/lib/coverage"

const RISK_STYLES: Record<RiskLevel, string> = {
  LOW: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400",
  MEDIUM: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  HIGH: "bg-orange-500/10 text-orange-600 dark:text-orange-400",
  CRITICAL: "bg-destructive/10 text-destructive",
}

const RISK_LABEL: Record<RiskLevel, string> = {
  LOW: "Risk thấp",
  MEDIUM: "Risk trung bình",
  HIGH: "Risk cao",
  CRITICAL: "Risk rất cao",
}

/** Badge risk level - tính tất định ở backend (Risk Engine, Module 15c), dùng chung EndpointList + CoveragePage. */
export function RiskBadge({ level, reasons, className }: { level: RiskLevel; reasons?: string[]; className?: string }) {
  return (
    <span
      className={cn("shrink-0 rounded-full px-2 py-0.5 text-xs font-medium", RISK_STYLES[level], className)}
      title={reasons && reasons.length > 0 ? reasons.join("\n") : undefined}
    >
      {RISK_LABEL[level]}
    </span>
  )
}

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
  riskWeightedCoveragePercent: number
}

/**
 * Donut cạnh nhau: coverage phẳng vs coverage có trọng số rủi ro - khoảng cách giữa 2 số là tín hiệu chính
 * ("coverage cao nhưng API rủi ro vẫn chưa test đủ"), xem Module 15c trong roadmap. Module 15j thêm donut
 * "đã chạy có kết quả": có test mới là thiết kế, chạy ra Pass/Fail mới là đã kiểm thật.
 */
export function CoverageSummary({ coveragePercent, executedPercent, riskWeightedCoveragePercent }: CoverageSummaryProps) {
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
      <div className="flex items-center gap-3">
        <MiniDonut
          percent={riskWeightedCoveragePercent}
          color={riskWeightedCoveragePercent < coveragePercent ? "#f97316" : "#10b981"}
        />
        <div className="text-sm">
          <p className="font-medium text-foreground">Coverage theo trọng số rủi ro</p>
          <p className="text-xs text-muted-foreground">Endpoint rủi ro cao mà thiếu test kéo điểm này xuống nhiều hơn</p>
        </div>
      </div>
      {riskWeightedCoveragePercent < coveragePercent && (
        <p className="w-full text-xs text-orange-600 dark:text-orange-400">
          Coverage phẳng {coveragePercent}% nhưng theo trọng số rủi ro chỉ {riskWeightedCoveragePercent}% - các endpoint rủi
          ro cao chưa được test đầy đủ.
        </p>
      )}
      {executedPercent < coveragePercent && (
        <p className="w-full text-xs text-sky-600 dark:text-sky-400">
          {coveragePercent}% mục đã có test nhưng mới {executedPercent}% đã chạy ra kết quả - phần chênh lệch mới là thiết
          kế, chưa được kiểm thật trên API.
        </p>
      )}
    </div>
  )
}
