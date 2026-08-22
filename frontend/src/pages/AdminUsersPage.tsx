import { useState } from "react"
import { Link, useSearchParams } from "react-router-dom"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { ChevronLeft, ChevronRight, Lock, Unlock } from "lucide-react"

import { Button } from "@/components/ui/button"
import { cn, formatDateTime } from "@/lib/utils"
import { getAdminDashboardSummary, listAdminUsers, setAdminUserEnabled } from "@/lib/admin"
import { getCurrentUserInfo } from "@/lib/auth"

const GRID_COLS = "grid-cols-[1fr_80px_70px_70px_80px_110px_130px_120px]"

export function AdminUsersPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const page = Number(searchParams.get("page") ?? "0")
  const queryClient = useQueryClient()
  const [pendingIds, setPendingIds] = useState<Set<string>>(new Set())

  const { data: me } = useQuery({ queryKey: ["current-user-info"], queryFn: () => getCurrentUserInfo() })

  const { data, isLoading, isError } = useQuery({
    queryKey: ["admin-users", page],
    queryFn: () => listAdminUsers(page),
  })

  const { data: summary } = useQuery({
    queryKey: ["admin-dashboard-summary"],
    queryFn: () => getAdminDashboardSummary(),
  })
  const dailyLimit = summary?.aiDailyTokenLimit ?? 0

  const users = data?.data ?? []
  const totalPages = data?.totalPages ?? 0

  async function handleToggle(userId: string, nextEnabled: boolean) {
    setPendingIds((prev) => new Set(prev).add(userId))
    try {
      await setAdminUserEnabled(userId, nextEnabled)
      queryClient.invalidateQueries({ queryKey: ["admin-users"] })
    } catch {
      // Bỏ qua - trạng thái không đổi, người dùng thấy nút vẫn ở icon cũ và có thể bấm lại
    } finally {
      setPendingIds((prev) => {
        const next = new Set(prev)
        next.delete(userId)
        return next
      })
    }
  }

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-semibold">Quản trị hệ thống</h1>
        <p className="mt-1 text-muted-foreground">Toàn bộ user đã đăng ký - khoá/mở tài khoản khi cần.</p>
      </div>

      {isLoading && (
        <div className="space-y-2">
          {Array.from({ length: 5 }).map((_, i) => (
            <div key={i} className="h-16 animate-pulse rounded-lg border border-border bg-card" />
          ))}
        </div>
      )}

      {isError && (
        <div className="rounded-lg border border-destructive/40 bg-destructive/5 p-4 text-sm text-destructive">
          Không tải được danh sách người dùng.
        </div>
      )}

      {!isLoading && !isError && (
        <div className="overflow-x-auto rounded-lg border border-border">
          <div className="min-w-[860px]">
            <div className={cn("grid gap-2 border-b border-border bg-muted/40 px-4 py-2 text-xs font-medium uppercase tracking-wide text-muted-foreground", GRID_COLS)}>
              <span>Email</span>
              <span>Role</span>
              <span>Project</span>
              <span>Test case</span>
              <span>Bug report</span>
              <span>AI/ngày</span>
              <span>Ngày tạo</span>
              <span className="text-right">Trạng thái</span>
            </div>
            {users.map((user) => {
              const isSelf = me?.email === user.email
              const isPending = pendingIds.has(user.id)
              // aiDailyTokenLimitOverride null = chưa ghi đè riêng, dùng mặc định hệ thống.
              const effectiveLimit = user.aiDailyTokenLimitOverride ?? dailyLimit
              const nearOrOverQuota = effectiveLimit > 0 && user.aiTokensToday >= effectiveLimit
              return (
                <div
                  key={user.id}
                  className={cn("grid items-center gap-2 border-b border-border px-4 py-3 text-sm last:border-b-0", GRID_COLS)}
                >
                  <Link
                    to={`/admin/users/${user.id}`}
                    className="truncate font-medium text-primary hover:underline"
                    title="Xem project/test case của user này"
                  >
                    {user.email}
                  </Link>
                  <span
                    className={
                      user.role === "ADMIN"
                        ? "w-fit rounded-full bg-violet-500/10 px-2 py-0.5 text-xs font-medium text-violet-600 dark:text-violet-400"
                        : "w-fit rounded-full bg-slate-500/10 px-2 py-0.5 text-xs font-medium text-slate-600 dark:text-slate-400"
                    }
                  >
                    {user.role === "ADMIN" ? "Admin" : "User"}
                  </span>
                  <span className="tabular-nums text-muted-foreground">{user.totalProjects}</span>
                  <span className="tabular-nums text-muted-foreground">{user.totalTestCases}</span>
                  <span className="tabular-nums text-muted-foreground">{user.totalBugReports}</span>
                  <span
                    className={cn("truncate text-xs tabular-nums", nearOrOverQuota ? "font-semibold text-destructive" : "text-muted-foreground")}
                    title={`${user.aiCallsToday} lần gọi AI hôm nay${user.aiDailyTokenLimitOverride != null ? " · đã ghi đè quota riêng" : ""}`}
                  >
                    {user.aiTokensToday.toLocaleString("vi-VN")}
                    {effectiveLimit > 0 && ` / ${effectiveLimit.toLocaleString("vi-VN")}`}
                  </span>
                  <span className="text-xs text-muted-foreground">{formatDateTime(user.createdAt)}</span>
                  <div className="flex justify-end">
                    {user.enabled ? (
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={isPending || isSelf}
                        title={isSelf ? "Không thể tự khoá tài khoản của chính mình" : "Khoá tài khoản"}
                        onClick={() => handleToggle(user.id, false)}
                      >
                        <Lock className="h-3.5 w-3.5" />
                        Khoá
                      </Button>
                    ) : (
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={isPending}
                        className="text-emerald-600 dark:text-emerald-400"
                        onClick={() => handleToggle(user.id, true)}
                      >
                        <Unlock className="h-3.5 w-3.5" />
                        Mở khoá
                      </Button>
                    )}
                  </div>
                </div>
              )
            })}
          </div>
        </div>
      )}

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
