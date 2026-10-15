import { cn } from "@/lib/utils"
import type { CoverageDimension, RiskLevel } from "@/lib/coverage"

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
  riskWeightedCoveragePercent: number
}

/**
 * 2 donut cạnh nhau: coverage phẳng vs coverage có trọng số rủi ro - khoảng cách giữa 2 số là tín
 * hiệu chính ("coverage cao nhưng API rủi ro vẫn chưa test đủ"), xem Module 15c trong roadmap.
 */
export function CoverageSummary({ coveragePercent, riskWeightedCoveragePercent }: CoverageSummaryProps) {
  return (
    <div className="flex flex-wrap items-center gap-6 rounded-2xl border border-border bg-card p-5 shadow-sm">
      <div className="flex items-center gap-3">
        <MiniDonut percent={coveragePercent} color="#10b981" />
        <div className="text-sm">
          <p className="font-medium text-foreground">Coverage (phẳng)</p>
          <p className="text-xs text-muted-foreground">% dimension đã có test case</p>
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
    </div>
  )
}

const DIMENSION_STYLES: Record<CoverageDimension["status"], string> = {
  COVERED: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400 border-emerald-500/20",
  MISSING: "bg-amber-500/10 text-amber-700 dark:text-amber-400 border-amber-500/20",
  NOT_APPLICABLE: "bg-muted text-muted-foreground border-border",
}

/** Module 15f - tooltip của chip COVERED hiện rõ test case nào khiến dimension đó được tính là đã kiểm (provenance), không chỉ "có hay không". */
function dimensionTooltip(d: CoverageDimension): string | undefined {
  if (d.status === "COVERED") {
    return d.coveringTestCases.length > 0
      ? `Được kiểm bởi: ${d.coveringTestCases.map((tc) => tc.name).join(", ")}`
      : undefined
  }
  return d.detail ?? undefined
}

/** Lưới chip cho từng dimension của 1 endpoint - xanh (COVERED)/vàng (MISSING)/xám (NOT_APPLICABLE), tooltip hiện detail hoặc provenance. */
export function CoverageDimensionGrid({ dimensions }: { dimensions: CoverageDimension[] }) {
  if (dimensions.length === 0) {
    return <p className="text-sm text-muted-foreground">Không có dimension nào để hiển thị.</p>
  }
  return (
    <div className="flex flex-wrap gap-1.5">
      {dimensions.map((d, idx) => (
        <span
          key={`${d.category}-${d.label}-${idx}`}
          title={dimensionTooltip(d)}
          className={cn("rounded-full border px-2 py-0.5 text-xs font-medium", DIMENSION_STYLES[d.status])}
        >
          {d.label}
        </span>
      ))}
    </div>
  )
}
