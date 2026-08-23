import { type ComponentType, type ReactNode } from "react"
import { useQuery } from "@tanstack/react-query"
import { Bug, Coins, ListChecks, Plug, Sparkles, Users } from "lucide-react"

import { getAdminAiUsage, getAdminDashboardSummary } from "@/lib/admin"
import { AiUsageChart } from "@/components/shared/AiUsageChart"
import { PassRateCard } from "@/components/shared/PassRateCard"

export function AdminDashboardPage() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["admin-dashboard-summary"],
    queryFn: () => getAdminDashboardSummary(),
  })

  const { data: aiUsage, isLoading: isAiUsageLoading } = useQuery({
    queryKey: ["admin-ai-usage"],
    queryFn: () => getAdminAiUsage(),
  })

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-semibold">Quản trị hệ thống</h1>
        <p className="mt-1 text-muted-foreground">Số liệu toàn hệ thống - không giới hạn theo 1 user.</p>
      </div>

      {isLoading && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {Array.from({ length: 7 }).map((_, i) => (
            <div key={i} className="h-24 animate-pulse rounded-2xl border border-border bg-card" />
          ))}
        </div>
      )}

      {isError && (
        <div className="rounded-lg border border-destructive/40 bg-destructive/5 p-4 text-sm text-destructive">
          Không tải được số liệu hệ thống.
        </div>
      )}

      {data && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <KpiCard icon={Users} label="Người dùng" value={data.totalUsers} />
          <KpiCard icon={Plug} label="Endpoint" value={data.totalEndpoints} />
          <KpiCard icon={ListChecks} label="Test case" value={data.totalTestCases} />
          <PassRateCard
            label="Tỷ lệ pass toàn hệ thống"
            percent={data.overallPassRate}
            passed={data.passedTestResults}
            total={data.totalTestResults}
          />
          <KpiCard
            icon={Bug}
            label="Bug Report đang mở"
            value={data.totalOpenBugs}
            valueClassName={data.totalOpenBugs > 0 ? "text-amber-500" : "text-foreground"}
          />
          <KpiCard icon={Sparkles} label="Lượt AI sinh test case" value={data.totalGenerationEvents} />
          <KpiCard icon={Coins} label="Token AI đã dùng hôm nay (mọi user)" value={data.totalAiTokensToday.toLocaleString("vi-VN")} />
        </div>
      )}

      <div className="mt-6 rounded-2xl border border-border bg-card p-6 shadow-sm">
        <h2 className="text-sm font-semibold text-foreground">Token AI đã dùng - toàn hệ thống</h2>
        <AiUsageChart daily={aiUsage?.daily} isLoading={isAiUsageLoading} />
      </div>
    </div>
  )
}

function KpiCard({
  icon: Icon,
  label,
  value,
  valueClassName,
  children,
}: {
  icon: ComponentType<{ className?: string }>
  label: string
  value: number | string
  valueClassName?: string
  children?: ReactNode
}) {
  return (
    <div className="rounded-2xl border border-border bg-card p-5 shadow-sm">
      <div className="flex items-center gap-2 text-sm text-muted-foreground">
        <Icon className="h-4 w-4" />
        {label}
      </div>
      <div className={`mt-3 text-3xl font-bold tabular-nums ${valueClassName ?? "text-foreground"}`}>{value}</div>
      {children}
    </div>
  )
}
