import { useMemo, useState } from "react"

import { cn } from "@/lib/utils"
import type { AiUsageDailyPoint } from "@/lib/aiUsage"

type Granularity = "day" | "week" | "month"

interface Bucket {
  key: string
  label: string
  totalTokens: number
  callCount: number
}

function mondayOf(dateStr: string): string {
  const d = new Date(`${dateStr}T00:00:00Z`)
  const day = d.getUTCDay()
  const diffToMonday = day === 0 ? -6 : 1 - day
  d.setUTCDate(d.getUTCDate() + diffToMonday)
  return d.toISOString().slice(0, 10)
}

function dayLabel(dateStr: string): string {
  return new Intl.DateTimeFormat("vi-VN", { day: "2-digit", month: "2-digit" }).format(new Date(`${dateStr}T00:00:00Z`))
}

function weekLabel(mondayStr: string): string {
  return `T.${new Intl.DateTimeFormat("vi-VN", { day: "2-digit", month: "2-digit" }).format(new Date(`${mondayStr}T00:00:00Z`))}`
}

function monthLabel(monthStr: string): string {
  const [year, month] = monthStr.split("-")
  return `Th${month}/${year.slice(2)}`
}

function bucketize(daily: AiUsageDailyPoint[], granularity: Granularity): Bucket[] {
  if (granularity === "day") {
    return daily.slice(-30).map((p) => ({ key: p.date, label: dayLabel(p.date), totalTokens: p.totalTokens, callCount: p.callCount }))
  }

  const groups = new Map<string, Bucket>()
  for (const p of daily) {
    const key = granularity === "week" ? mondayOf(p.date) : p.date.slice(0, 7)
    const label = granularity === "week" ? weekLabel(key) : monthLabel(key)
    const existing = groups.get(key) ?? { key, label, totalTokens: 0, callCount: 0 }
    existing.totalTokens += p.totalTokens
    existing.callCount += p.callCount
    groups.set(key, existing)
  }
  return Array.from(groups.values()).sort((a, b) => a.key.localeCompare(b.key))
}

const GRANULARITY_LABEL: Record<Granularity, string> = { day: "Ngày", week: "Tuần", month: "Tháng" }

/** Biểu đồ cột usage token AI - dùng chung cho trang Tổng quan (chính user) và Admin (1 user/toàn hệ thống). */
export function AiUsageChart({ daily, isLoading }: { daily: AiUsageDailyPoint[] | undefined; isLoading: boolean }) {
  const [granularity, setGranularity] = useState<Granularity>("day")
  const buckets = useMemo(() => bucketize(daily ?? [], granularity), [daily, granularity])
  const totalInWindow = buckets.reduce((sum, b) => sum + b.totalTokens, 0)

  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-xs text-muted-foreground">
          {totalInWindow > 0 ? `${totalInWindow.toLocaleString("vi-VN")} token trong khoảng đang xem` : "Chưa dùng token AI nào trong khoảng đang xem"}
        </p>
        <div className="flex gap-1 rounded-lg border border-border p-0.5">
          {(["day", "week", "month"] as Granularity[]).map((g) => (
            <button
              key={g}
              type="button"
              onClick={() => setGranularity(g)}
              className={cn(
                "rounded-md px-2.5 py-1 text-xs font-medium transition-colors",
                granularity === g ? "bg-primary text-primary-foreground" : "text-muted-foreground hover:text-foreground"
              )}
            >
              {GRANULARITY_LABEL[g]}
            </button>
          ))}
        </div>
      </div>

      {isLoading ? (
        <div className="mt-4 h-[200px] animate-pulse rounded-xl bg-muted" />
      ) : buckets.length === 0 || totalInWindow === 0 ? (
        <p className="mt-10 text-center text-sm text-muted-foreground">Chưa có dữ liệu usage AI nào.</p>
      ) : (
        <BarChart buckets={buckets} />
      )}
    </div>
  )
}

function BarChart({ buckets }: { buckets: Bucket[] }) {
  const width = 600
  const height = 200
  const paddingLeft = 40
  const paddingRight = 8
  const paddingTop = 12
  const paddingBottom = buckets.length <= 14 ? 22 : 8
  const chartWidth = width - paddingLeft - paddingRight
  const chartHeight = height - paddingTop - paddingBottom

  const maxValue = Math.max(1, ...buckets.map((b) => b.totalTokens))
  const yFor = (v: number) => paddingTop + (1 - v / maxValue) * chartHeight
  const slot = buckets.length > 0 ? chartWidth / buckets.length : 0
  const barWidth = Math.max(2, slot * 0.6)
  const ticks = [0, 0.5, 1].map((f) => Math.round(maxValue * f))
  const showLabels = buckets.length <= 14

  function formatTick(v: number): string {
    return v >= 1000 ? `${Math.round(v / 1000)}k` : String(v)
  }

  return (
    <svg viewBox={`0 0 ${width} ${height}`} className="mt-4 w-full" style={{ height }}>
      {ticks.map((t) => (
        <g key={t}>
          <line x1={paddingLeft} x2={width - paddingRight} y1={yFor(t)} y2={yFor(t)} stroke="var(--color-border)" strokeDasharray="3 3" />
          <text x={paddingLeft - 6} y={yFor(t) + 3} textAnchor="end" fontSize="10" className="fill-muted-foreground">
            {formatTick(t)}
          </text>
        </g>
      ))}
      {buckets.map((b, i) => {
        const x = paddingLeft + i * slot + (slot - barWidth) / 2
        const y = yFor(b.totalTokens)
        const h = Math.max(b.totalTokens > 0 ? 2 : 0, paddingTop + chartHeight - y)
        return (
          <g key={b.key}>
            <rect
              x={x}
              y={paddingTop + chartHeight - h}
              width={barWidth}
              height={h}
              rx="2"
              fill="#8b5cf6"
              fillOpacity={b.totalTokens > 0 ? 0.85 : 0.15}
            >
              <title>
                {b.label}: {b.totalTokens.toLocaleString("vi-VN")} token ({b.callCount} lượt gọi)
              </title>
            </rect>
            {showLabels && (
              <text x={x + barWidth / 2} y={height - 6} textAnchor="middle" fontSize="9" className="fill-muted-foreground">
                {b.label}
              </text>
            )}
          </g>
        )
      })}
    </svg>
  )
}
