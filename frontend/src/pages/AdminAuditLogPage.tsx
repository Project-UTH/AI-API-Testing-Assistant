import { useSearchParams } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { ChevronLeft, ChevronRight, Lock, Unlock } from "lucide-react"

import { Button } from "@/components/ui/button"
import { formatDateTime } from "@/lib/utils"
import { listAdminAuditLog } from "@/lib/admin"
import { AdminTabs } from "@/pages/AdminDashboardPage"

const ACTION_LABEL: Record<string, string> = {
  USER_LOCKED: "Khoá tài khoản",
  USER_UNLOCKED: "Mở khoá tài khoản",
}

export function AdminAuditLogPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const page = Number(searchParams.get("page") ?? "0")

  const { data, isLoading, isError } = useQuery({
    queryKey: ["admin-audit-log", page],
    queryFn: () => listAdminAuditLog(page),
  })

  const events = data?.data ?? []
  const totalPages = data?.totalPages ?? 0

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-semibold">Quản trị hệ thống</h1>
        <p className="mt-1 text-muted-foreground">Nhật ký hành động nhạy cảm của admin (khoá/mở tài khoản).</p>
      </div>

      <AdminTabs active="audit-log" />

      {isLoading && (
        <div className="space-y-2">
          {Array.from({ length: 5 }).map((_, i) => (
            <div key={i} className="h-14 animate-pulse rounded-lg border border-border bg-card" />
          ))}
        </div>
      )}

      {isError && (
        <div className="rounded-lg border border-destructive/40 bg-destructive/5 p-4 text-sm text-destructive">
          Không tải được nhật ký.
        </div>
      )}

      {!isLoading && !isError && events.length === 0 && (
        <div className="rounded-lg border border-dashed border-border p-8 text-center text-sm text-muted-foreground">
          Chưa có hành động nhạy cảm nào được ghi nhận.
        </div>
      )}

      <div className="space-y-2">
        {events.map((event) => (
          <div key={event.id} className="flex items-center gap-3 rounded-lg border border-border bg-card p-3 text-sm">
            {event.action === "USER_LOCKED" ? (
              <Lock className="h-4 w-4 shrink-0 text-destructive" />
            ) : (
              <Unlock className="h-4 w-4 shrink-0 text-emerald-500" />
            )}
            <span className="min-w-0 flex-1">
              <span className="font-medium">{event.adminEmail}</span> {ACTION_LABEL[event.action] ?? event.action}{" "}
              <span className="font-medium">{event.targetEmail}</span>
            </span>
            <span className="shrink-0 text-xs text-muted-foreground">{formatDateTime(event.createdAt)}</span>
          </div>
        ))}
      </div>

      {totalPages > 1 && (
        <div className="mt-6 flex items-center justify-center gap-3">
          <Button
            variant="outline"
            size="sm"
            disabled={page <= 0}
            onClick={() => setSearchParams({ page: String(page - 1) })}
          >
            <ChevronLeft className="h-4 w-4" />
            Trước
          </Button>
          <span className="text-sm text-muted-foreground">
            Trang {page + 1}/{totalPages}
          </span>
          <Button
            variant="outline"
            size="sm"
            disabled={page + 1 >= totalPages}
            onClick={() => setSearchParams({ page: String(page + 1) })}
          >
            Sau
            <ChevronRight className="h-4 w-4" />
          </Button>
        </div>
      )}
    </div>
  )
}
