import { useSearchParams, useNavigate, useParams, Link } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { ArrowLeft, ChevronLeft, ChevronRight, FolderKanban } from "lucide-react"

import { Button } from "@/components/ui/button"
import { formatDateTime } from "@/lib/utils"
import { listAdminUserProjects } from "@/lib/admin"

/**
 * Chỉ đọc - xem Project của 1 user khác để hỗ trợ/điều tra (Module 11). Không có nút tạo/sửa/xoá
 * nào ở đây hay ở AdminProjectDataPage kế tiếp - đúng quyết định đã ghi ở skill api-contract 4d.
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
        <h1 className="text-2xl font-semibold">Project của người dùng</h1>
        <p className="mt-1 text-muted-foreground">Chế độ chỉ xem - phục vụ hỗ trợ/điều tra.</p>
      </div>

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
