import { type ComponentType } from "react"
import { NavLink } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { Bug, FolderKanban, ListChecks, PieChart, Plug, Sparkles, Users } from "lucide-react"

import { cn } from "@/lib/utils"
import { getAdminDashboardSummary } from "@/lib/admin"

export function AdminDashboardPage() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["admin-dashboard-summary"],
    queryFn: () => getAdminDashboardSummary(),
  })

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-semibold">Quản trị hệ thống</h1>
        <p className="mt-1 text-muted-foreground">Số liệu toàn hệ thống - không giới hạn theo 1 user.</p>
      </div>

      <AdminTabs active="dashboard" />

      {isLoading && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {Array.from({ length: 8 }).map((_, i) => (
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
          <KpiCard icon={FolderKanban} label="Project" value={data.totalProjects} />
          <KpiCard icon={Plug} label="Endpoint" value={data.totalEndpoints} />
          <KpiCard icon={ListChecks} label="Test case" value={data.totalTestCases} />
          <KpiCard
            icon={PieChart}
            label="Tỷ lệ pass toàn hệ thống"
            value={data.overallPassRate === null ? "—" : `${data.overallPassRate}%`}
            valueClassName="text-emerald-500"
          />
          <KpiCard label="Kết quả test đã ghi nhận" value={data.totalTestResults} icon={ListChecks} />
          <KpiCard
            icon={Bug}
            label="Bug Report đang mở"
            value={data.totalOpenBugs}
            valueClassName={data.totalOpenBugs > 0 ? "text-amber-500" : "text-foreground"}
          />
          <KpiCard icon={Sparkles} label="Lượt AI sinh test case" value={data.totalGenerationEvents} />
        </div>
      )}
    </div>
  )
}

export function AdminTabs({ active }: { active: "dashboard" | "users" }) {
  const tabs: { to: string; key: "dashboard" | "users"; label: string }[] = [
    { to: "/admin", key: "dashboard", label: "Tổng quan" },
    { to: "/admin/users", key: "users", label: "Người dùng" },
  ]

  return (
    <div className="mb-6 flex gap-2 border-b border-border">
      {tabs.map((tab) => (
        <NavLink
          key={tab.key}
          to={tab.to}
          end={tab.to === "/admin"}
          className={cn(
            "border-b-2 px-3 py-2 text-sm font-medium transition-colors",
            active === tab.key
              ? "border-primary text-foreground"
              : "border-transparent text-muted-foreground hover:text-foreground"
          )}
        >
          {tab.label}
        </NavLink>
      ))}
    </div>
  )
}

function KpiCard({
  icon: Icon,
  label,
  value,
  valueClassName,
}: {
  icon: ComponentType<{ className?: string }>
  label: string
  value: number | string
  valueClassName?: string
}) {
  return (
    <div className="rounded-2xl border border-border bg-card p-5 shadow-sm">
      <div className="flex items-center gap-2 text-sm text-muted-foreground">
        <Icon className="h-4 w-4" />
        {label}
      </div>
      <div className={`mt-3 text-3xl font-bold tabular-nums ${valueClassName ?? "text-foreground"}`}>{value}</div>
    </div>
  )
}
