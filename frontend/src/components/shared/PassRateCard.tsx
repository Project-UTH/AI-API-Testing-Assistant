import type { CSSProperties } from "react"
import { Info, PieChart } from "lucide-react"

import { cn } from "@/lib/utils"

interface PassRateCardProps {
  label: string
  percent: number | null
  passed: number
  total: number
  className?: string
  style?: CSSProperties
}

/** Thẻ KPI tỷ lệ pass dạng biểu đồ tròn - dùng chung Dashboard user (Module 8) và Admin Dashboard
 *  (Module 11), chỉ khác nhau ở label và nguồn dữ liệu (theo owner hay toàn hệ thống). */
export function PassRateCard({ label, percent, passed, total, className, style }: PassRateCardProps) {
  return (
    <div
      className={cn("flex items-center gap-4 rounded-2xl border border-border bg-card p-5 shadow-sm", className)}
      style={style}
    >
      <PassRateDonut percent={percent} />
      <div className="min-w-0">
        <div className="flex items-start gap-2 text-sm leading-tight text-muted-foreground">
          <PieChart className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{label}</span>
        </div>
        <p className="mt-1.5 flex items-center gap-1 text-xs text-muted-foreground">
          <span>
            {total === 0
              ? "Chưa có kết quả test nào"
              : `${passed.toLocaleString("vi-VN")}/${total.toLocaleString("vi-VN")} kết quả test pass`}
          </span>
          {total > 0 && (
            <span
              title='Tính theo mỗi LƯỢT chạy (kể cả chạy lại) - đúng bằng số "kết quả test" ghi nhận, không phải số test case phân biệt ở ô Test case, vì mỗi lượt chạy đều có lịch sử/Bug Report riêng'
              className="shrink-0"
            >
              <Info className="h-3 w-3 text-muted-foreground/60" />
            </span>
          )}
        </p>
      </div>
    </div>
  )
}

function PassRateDonut({ percent }: { percent: number | null }) {
  const size = 56
  const strokeWidth = 7
  const radius = (size - strokeWidth) / 2
  const circumference = 2 * Math.PI * radius
  const pct = percent ?? 0
  const dash = (pct / 100) * circumference

  return (
    <div className="relative shrink-0" style={{ width: size, height: size }}>
      <svg width={size} height={size} className="-rotate-90">
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke="var(--color-border)"
          strokeWidth={strokeWidth}
        />
        {percent !== null && (
          <circle
            cx={size / 2}
            cy={size / 2}
            r={radius}
            fill="none"
            stroke="#10b981"
            strokeWidth={strokeWidth}
            strokeDasharray={`${dash} ${circumference - dash}`}
            strokeLinecap="round"
          />
        )}
      </svg>
      <div className="absolute inset-0 flex items-center justify-center">
        <span className="text-xs font-bold tabular-nums text-foreground">
          {percent === null ? "—" : `${percent}%`}
        </span>
      </div>
    </div>
  )
}
