import { useState } from "react"
import { useSearchParams, useNavigate, useParams, Link } from "react-router-dom"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { ArrowLeft, ChevronLeft, ChevronRight, FolderKanban } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { formatDateTime } from "@/lib/utils"
import { getAdminUser, getAdminUserAiUsage, listAdminUserProjects, setAdminUserAiQuota } from "@/lib/admin"
import { AiUsageChart } from "@/components/shared/AiUsageChart"

/**
 * Chỉ đọc - xem Project của 1 user khác để hỗ trợ/điều tra (Module 11). Không có nút tạo/sửa/xoá
 * nào ở đây hay ở AdminProjectDataPage kế tiếp - đúng quyết định đã ghi ở skill api-contract 4d.
 * Ngoại lệ duy nhất: chỉnh quota AI riêng cho user (không phải sửa dữ liệu do user tạo ra).
 */
export function AdminUserDetailPage() {
  const { userId } = useParams<{ userId: string }>()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const page = Number(searchParams.get("page") ?? "0")

  const { data, isLoading, isError } = useQuery({
    queryKey: ["admin-user-projects", userId, page],
    queryFn: () => listAdminUserProjects(userId!, page),
    enabled: Boolean(userId),
  })

  const projects = data?.data ?? []
  const totalPages = data?.totalPages ?? 0

  return (
    <div>
      <Button variant="ghost" size="sm" className="mb-4" onClick={() => navigate("/admin/users")}>
        <ArrowLeft className="h-4 w-4" />
        Quay lại danh sách user
      </Button>

      <div className="mb-6">
        <h1 className="text-2xl font-semibold">Chi tiết người dùng</h1>
        <p className="mt-1 text-muted-foreground">Xem project (chỉ đọc), usage token AI và chỉnh quota riêng.</p>
      </div>

      <AiQuotaAndUsageSection userId={userId!} />

      <h2 className="mb-3 mt-8 text-lg font-semibold">Project của người dùng</h2>

      {isLoading && (
        <div className="space-y-2">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="h-20 animate-pulse rounded-lg border border-border bg-card" />
          ))}
        </div>
      )}

      {isError && (
        <div className="rounded-lg border border-destructive/40 bg-destructive/5 p-4 text-sm text-destructive">
          Không tải được danh sách project.
        </div>
      )}

      {!isLoading && !isError && projects.length === 0 && (
        <div className="rounded-lg border border-dashed border-border p-8 text-center text-sm text-muted-foreground">
          User này chưa có project nào.
        </div>
      )}

      <div className="space-y-2">
        {projects.map((project) => (
          <Link
            key={project.id}
            to={`/admin/users/${userId}/projects/${project.id}`}
            className="flex items-center gap-3 rounded-lg border border-border bg-card p-4 transition-colors hover:bg-accent"
          >
            <FolderKanban className="h-5 w-5 shrink-0 text-muted-foreground" />
            <div className="min-w-0 flex-1">
              <p className="truncate font-medium">{project.name}</p>
              {project.description && (
                <p className="truncate text-sm text-muted-foreground">{project.description}</p>
              )}
            </div>
            <span className="shrink-0 text-xs text-muted-foreground">{formatDateTime(project.createdAt)}</span>
          </Link>
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

function AiQuotaAndUsageSection({ userId }: { userId: string }) {
  const queryClient = useQueryClient()
  const [draftLimit, setDraftLimit] = useState("")
  const [isSaving, setIsSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)

  const { data: user, isLoading: isUserLoading } = useQuery({
    queryKey: ["admin-user", userId],
    queryFn: () => getAdminUser(userId),
  })

  const { data: usage, isLoading: isUsageLoading } = useQuery({
    queryKey: ["admin-user-ai-usage", userId],
    queryFn: () => getAdminUserAiUsage(userId),
  })

  async function handleSave() {
    const trimmed = draftLimit.trim()
    const parsed = trimmed === "" ? null : Number(trimmed)
    if (parsed !== null && (!Number.isInteger(parsed) || parsed < 0)) {
      setSaveError("Giới hạn phải là số nguyên không âm")
      return
    }
    setIsSaving(true)
    setSaveError(null)
    try {
      await setAdminUserAiQuota(userId, parsed)
      queryClient.invalidateQueries({ queryKey: ["admin-user", userId] })
      queryClient.invalidateQueries({ queryKey: ["admin-users"] })
      queryClient.invalidateQueries({ queryKey: ["admin-audit-log"] })
      setDraftLimit("")
    } catch {
      setSaveError("Lưu thất bại, thử lại")
    } finally {
      setIsSaving(false)
    }
  }

  async function handleResetToDefault() {
    setIsSaving(true)
    setSaveError(null)
    try {
      await setAdminUserAiQuota(userId, null)
      queryClient.invalidateQueries({ queryKey: ["admin-user", userId] })
      queryClient.invalidateQueries({ queryKey: ["admin-users"] })
      queryClient.invalidateQueries({ queryKey: ["admin-audit-log"] })
      setDraftLimit("")
    } catch {
      setSaveError("Lưu thất bại, thử lại")
    } finally {
      setIsSaving(false)
    }
  }

  return (
    <div className="rounded-2xl border border-border bg-card p-6 shadow-sm">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="text-sm font-semibold text-foreground">{isUserLoading ? "Đang tải..." : user?.email}</h2>
          <p className="mt-1 text-xs text-muted-foreground">
            Quota AI/ngày hiện tại:{" "}
            {user?.aiDailyTokenLimitOverride != null ? (
              <span className="font-medium text-foreground">
                {user.aiDailyTokenLimitOverride.toLocaleString("vi-VN")} token (đã ghi đè riêng)
              </span>
            ) : (
              <span className="font-medium text-foreground">Mặc định hệ thống</span>
            )}
          </p>
        </div>

        <div className="flex items-end gap-2">
          <div>
            <label className="mb-1 block text-xs text-muted-foreground" htmlFor="ai-quota-input">
              Đặt giới hạn riêng (token/ngày)
            </label>
            <Input
              id="ai-quota-input"
              type="number"
              min={0}
              step={1}
              placeholder="vd: 50000"
              value={draftLimit}
              onChange={(e) => setDraftLimit(e.target.value)}
              className="w-40"
            />
          </div>
          <Button size="sm" disabled={isSaving || draftLimit.trim() === ""} onClick={handleSave}>
            Lưu
          </Button>
          <Button
            size="sm"
            variant="outline"
            disabled={isSaving || user?.aiDailyTokenLimitOverride == null}
            onClick={handleResetToDefault}
          >
            Về mặc định
          </Button>
        </div>
      </div>

      {saveError && <p className="mt-2 text-xs text-destructive">{saveError}</p>}

      <div className="mt-6 border-t border-border pt-6">
        <h3 className="text-sm font-semibold text-foreground">Token AI đã dùng</h3>
        <AiUsageChart daily={usage?.daily} isLoading={isUsageLoading} />
      </div>
    </div>
  )
}
