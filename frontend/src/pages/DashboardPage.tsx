import { useState, type ComponentType, type ReactNode } from "react"
import { Link } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { Bug, CircleCheck, FolderKanban, ListChecks, PieChart, Plug, Plus, Sparkles, Trash2, TriangleAlert, X } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { getCurrentUserEmail } from "@/lib/api"
import { getDashboardSummary } from "@/lib/dashboard"
import { getHistoryFeed, type HistoryFeedItem } from "@/lib/history"

export function DashboardPage() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["dashboard-summary"],
    queryFn: () => getDashboardSummary(),
  })

  const hasData = !!data && data.totalProjects > 0

  const { data: activityData, isLoading: isActivityLoading } = useQuery({
    queryKey: ["dashboard-recent-activity"],
    queryFn: () => getHistoryFeed({ page: 0 }),
    enabled: hasData,
  })

  const username = getCurrentUserEmail()?.split("@")[0]

  return (
    <div>
      <div className="mb-8 animate-rise">
        <p className="text-sm text-muted-foreground">
          Chào mừng bạn quay trở lại{username ? `, ${username}` : ""}
        </p>
        <h1 className="text-3xl font-bold tracking-tight text-foreground">Tổng quan</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          {data && data.totalProjects > 0
            ? `${data.totalProjects} project · ${data.totalTestResults} kết quả test đã ghi nhận.`
            : "Toàn cảnh project, endpoint và kết quả kiểm thử API của bạn."}
        </p>
      </div>

      {isLoading && <DashboardSkeleton />}
      {isError && <ErrorState />}
      {data && data.totalProjects === 0 && <EmptyState />}

      {data && data.totalProjects > 0 && (
        <div className="grid gap-5">
          <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-5">
            <KpiCard icon={FolderKanban} label="Project" value={data.totalProjects} index={0} />
            <KpiCard icon={Plug} label="Endpoint" value={data.totalEndpoints} index={1} />
            <KpiCard icon={ListChecks} label="Test case" value={data.totalTestCases} index={2} />
            <KpiCard
              icon={PieChart}
              label="Tỷ lệ pass"
              value={data.overallPassRate === null ? "—" : `${data.overallPassRate}%`}
              valueClassName="text-emerald-500"
              index={3}
            >
              {data.overallPassRate !== null && (
                <>
                  <div className="mt-3 h-1.5 w-full overflow-hidden rounded-full bg-rose-400/25">
                    <div
                      className="h-full rounded-full bg-emerald-500 transition-[width] duration-700 ease-out"
                      style={{ width: `${data.overallPassRate}%` }}
                    />
                  </div>
                  <p className="mt-2 text-xs text-muted-foreground">{data.totalTestResults} kết quả test</p>
                </>
              )}
            </KpiCard>
            <KpiCard
              icon={Bug}
              label="Bug Report đang mở"
              value={data.totalOpenBugs}
              valueClassName={data.totalOpenBugs > 0 ? "text-amber-500" : "text-foreground"}
              index={4}
            />
          </div>

          <div className="grid gap-5 lg:grid-cols-3">
            <TrendChartPanel enabled={hasData} />
            <ActivityFeedPanel items={activityData?.data} isLoading={isActivityLoading} />
          </div>
        </div>
      )}

      <DashboardFooter />
    </div>
  )
}

function DashboardFooter() {
  return (
    <footer className="animate-rise mt-12 border-t border-border pt-6 text-center" style={{ animationDelay: "600ms" }}>
      <p className="font-mono text-[11px] uppercase tracking-[0.28em] text-muted-foreground">
        AI API Testing Assistant
      </p>
      <p className="mx-auto mt-2 max-w-md text-sm text-muted-foreground">
        Import OpenAPI/Swagger, để AI sinh test case, bạn review và thực thi — tất cả trong một nơi.
      </p>
    </footer>
  )
}

function KpiCard({
  icon: Icon,
  label,
  value,
  valueClassName,
  index,
  children,
}: {
  icon: ComponentType<{ className?: string }>
  label: string
  value: number | string
  valueClassName?: string
  index: number
  children?: ReactNode
}) {
  return (
    <div
      className="animate-rise rounded-2xl border border-border bg-card p-5 shadow-sm"
      style={{ animationDelay: `${100 + index * 80}ms` }}
    >
      <div className="flex items-center gap-2 text-sm text-muted-foreground">
        <Icon className="h-4 w-4" />
        {label}
      </div>
      <div className={`mt-3 text-3xl font-bold tabular-nums ${valueClassName ?? "text-foreground"}`}>{value}</div>
      {children}
    </div>
  )
}

function TrendChartPanel({ enabled }: { enabled: boolean }) {
  const [from, setFrom] = useState("")
  const [to, setTo] = useState("")
  const hasDateFilter = Boolean(from || to)

  // size=50 (thay vì mặc định 20) - có lọc theo ngày thì số lần chạy khớp bộ lọc có thể nằm rải rác
  // qua nhiều trang hơn mức mặc định, cần đủ dữ liệu để lấy ra 8 lần chạy gần nhất TRONG khoảng đã lọc.
  const { data: trendData, isLoading } = useQuery({
    queryKey: ["dashboard-trend", from, to],
    queryFn: () => getHistoryFeed({ page: 0, size: 50, from: from || undefined, to: to || undefined }),
    enabled,
  })

  const executions = (trendData?.data ?? []).filter(
    (item): item is HistoryFeedItem & { passCount: number; selectedCount: number } =>
      item.type === "EXECUTION" && !!item.selectedCount
  )
  const recent = executions.slice(0, 8).reverse()
  const points = recent.map((item) => Math.round((item.passCount / item.selectedCount) * 100))

  return (
    <div className="animate-rise rounded-2xl border border-border bg-card p-6 shadow-sm lg:col-span-2" style={{ animationDelay: "420ms" }}>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-sm font-semibold text-foreground">
          {points.length >= 2 ? `Xu hướng pass rate, ${points.length} lần chạy gần nhất` : "Xu hướng pass rate"}
        </h2>
        <div className="flex flex-wrap items-center gap-2">
          <Input
            type="date"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            className="h-8 w-[9.5rem] text-xs"
            aria-label="Từ ngày"
          />
          <span className="text-xs text-muted-foreground">–</span>
          <Input
            type="date"
            value={to}
            onChange={(e) => setTo(e.target.value)}
            className="h-8 w-[9.5rem] text-xs"
            aria-label="Đến ngày"
          />
          {hasDateFilter && (
            <Button
              size="icon-xs"
              variant="ghost"
              onClick={() => {
                setFrom("")
                setTo("")
              }}
              title="Xoá lọc ngày"
            >
              <X className="h-3.5 w-3.5" />
            </Button>
          )}
        </div>
      </div>

      {isLoading ? (
        <div className="mt-4 h-[220px] animate-pulse rounded-xl bg-muted" />
      ) : points.length < 2 ? (
        <p className="mt-10 text-sm text-muted-foreground">
          {hasDateFilter
            ? "Không đủ lần chạy trong khoảng ngày đã lọc để hiển thị xu hướng."
            : "Chưa đủ dữ liệu để hiển thị xu hướng. Chạy thêm test để xem biểu đồ."}
        </p>
      ) : (
        <TrendChart points={points} />
      )}
    </div>
  )
}

function TrendChart({ points }: { points: number[] }) {
  const width = 600
  const height = 220
  const paddingLeft = 36
  const paddingRight = 8
  const paddingTop = 12
  const paddingBottom = 12
  const chartWidth = width - paddingLeft - paddingRight
  const chartHeight = height - paddingTop - paddingBottom

  const yMax = 100
  const yMin = Math.max(0, Math.floor((Math.min(...points) - 5) / 10) * 10)
  const ticks: number[] = []
  for (let t = yMax; t >= yMin; t -= 10) ticks.push(t)

  const xFor = (i: number) => paddingLeft + (points.length === 1 ? chartWidth / 2 : (i / (points.length - 1)) * chartWidth)
  const yFor = (v: number) => paddingTop + (1 - (v - yMin) / (yMax - yMin || 1)) * chartHeight

  const linePath = points.map((v, i) => `${i === 0 ? "M" : "L"} ${xFor(i)} ${yFor(v)}`).join(" ")
  const areaPath = `${linePath} L ${xFor(points.length - 1)} ${paddingTop + chartHeight} L ${xFor(0)} ${paddingTop + chartHeight} Z`

  return (
    <svg viewBox={`0 0 ${width} ${height}`} className="mt-4 w-full" style={{ height: 220 }}>
      <defs>
        <linearGradient id="trendFill" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#10b981" stopOpacity="0.35" />
          <stop offset="100%" stopColor="#10b981" stopOpacity="0" />
        </linearGradient>
      </defs>
      {ticks.map((t) => (
        <g key={t}>
          <line
            x1={paddingLeft}
            x2={width - paddingRight}
            y1={yFor(t)}
            y2={yFor(t)}
            stroke="var(--color-border)"
            strokeDasharray="3 3"
          />
          <text x={paddingLeft - 8} y={yFor(t) + 3} textAnchor="end" fontSize="10" className="fill-muted-foreground">
            {t}%
          </text>
        </g>
      ))}
      <path d={areaPath} fill="url(#trendFill)" stroke="none" />
      <path d={linePath} fill="none" stroke="#10b981" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
      {points.map((v, i) => (
        <circle key={i} cx={xFor(i)} cy={yFor(v)} r="4" fill="#10b981" stroke="var(--color-card)" strokeWidth="2" />
      ))}
    </svg>
  )
}

function ActivityFeedPanel({ items, isLoading }: { items: HistoryFeedItem[] | undefined; isLoading: boolean }) {
  const recent = (items ?? []).slice(0, 5)

  return (
    <div className="animate-rise rounded-2xl border border-border bg-card p-6 shadow-sm" style={{ animationDelay: "500ms" }}>
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-foreground">Hoạt động gần đây</h2>
        <Link to="/history" className="text-xs font-medium text-primary hover:underline">
          Xem tất cả
        </Link>
      </div>

      {isLoading ? (
        <div className="mt-4 space-y-4">
          {[0, 1, 2].map((i) => (
            <div key={i} className="h-10 animate-pulse rounded-lg bg-muted" />
          ))}
        </div>
      ) : recent.length === 0 ? (
        <p className="mt-6 text-sm text-muted-foreground">Chưa có hoạt động nào.</p>
      ) : (
        <ul className="mt-4 space-y-4">
          {recent.map((item) => (
            <ActivityRow key={item.id} item={item} />
          ))}
        </ul>
      )}
    </div>
  )
}

function ActivityRow({ item }: { item: HistoryFeedItem }) {
  const isExecution = item.type === "EXECUTION"
  const isBugDeleted = item.type === "BUG_REPORT_DELETED"
  const isBugReport = item.type === "BUG_REPORT_CREATED" || isBugDeleted
  const hasFail = isExecution && (item.failCount ?? 0) > 0

  const Icon = isExecution ? (hasFail ? TriangleAlert : CircleCheck) : isBugReport ? (isBugDeleted ? Trash2 : Bug) : Sparkles
  const iconClassName = isExecution
    ? (hasFail ? "text-rose-500" : "text-emerald-500")
    : isBugReport
      ? (isBugDeleted ? "text-rose-500" : "text-amber-500")
      : "text-violet-500"

  const title = isExecution
    ? `${item.projectName} · ${item.endpointMethod} ${item.endpointPath}`
    : isBugReport
      ? `${item.projectName} · ${isBugDeleted ? "Xoá" : "Tạo"} Bug Report${item.bugId ? ` ${item.bugId}` : ""}`
      : `${item.projectName} · Sinh ${item.generatedCount} test case`

  const subtitle = isExecution
    ? `${item.passCount}/${item.selectedCount} pass · ${formatEventTime(item.occurredAt)}`
    : isBugReport
      ? `${item.bugSummary ? `${item.bugSummary} · ` : ""}${formatEventTime(item.occurredAt)}`
      : formatEventTime(item.occurredAt)

  return (
    <li className="flex items-start gap-3">
      <Icon className={`mt-0.5 h-4 w-4 shrink-0 ${iconClassName}`} />
      <div className="min-w-0">
        <p className="truncate text-sm font-medium text-foreground">{title}</p>
        <p className="text-xs text-muted-foreground">{subtitle}</p>
      </div>
    </li>
  )
}

function formatEventTime(iso: string): string {
  const date = new Date(iso)
  const now = new Date()
  const time = new Intl.DateTimeFormat("vi-VN", { hour: "2-digit", minute: "2-digit" }).format(date)

  if (date.toDateString() === now.toDateString()) return time

  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  if (date.toDateString() === yesterday.toDateString()) return `Hôm qua, ${time}`

  return `${new Intl.DateTimeFormat("vi-VN", { day: "2-digit", month: "2-digit" }).format(date)}, ${time}`
}

function EmptyState() {
  return (
    <div className="animate-rise relative overflow-hidden rounded-2xl border border-dashed border-border bg-card/50 p-12 text-center">
      <div className="mx-auto flex size-14 items-center justify-center rounded-full bg-primary/10 text-primary">
        <FolderKanban className="h-6 w-6" />
      </div>
      <h2 className="mt-6 text-2xl font-bold text-foreground">Chưa có project nào</h2>
      <p className="mx-auto mt-2 max-w-sm text-sm text-muted-foreground">
        Tạo project đầu tiên để import OpenAPI/Swagger và để AI sinh test case cho bạn.
      </p>
      <Button className="mt-6" nativeButton={false} render={<Link to="/projects" />}>
        <Plus className="h-4 w-4" />
        Tạo Project
      </Button>
    </div>
  )
}

function ErrorState() {
  return (
    <div className="animate-rise flex items-center gap-3 rounded-2xl border border-destructive/30 bg-destructive/5 p-6 text-sm text-destructive">
      <TriangleAlert className="h-4 w-4 shrink-0" />
      Không tải được số liệu tổng quan, vui lòng thử lại.
    </div>
  )
}

function DashboardSkeleton() {
  return (
    <div className="grid gap-5">
      <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
        {[0, 1, 2, 3].map((i) => (
          <div key={i} className="h-28 animate-pulse rounded-2xl bg-muted" />
        ))}
      </div>
      <div className="grid gap-5 lg:grid-cols-3">
        <div className="h-80 animate-pulse rounded-2xl bg-muted lg:col-span-2" />
        <div className="h-80 animate-pulse rounded-2xl bg-muted" />
      </div>
    </div>
  )
}
