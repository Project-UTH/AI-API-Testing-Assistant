import { Link, useParams } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { ArrowLeft } from "lucide-react"

import { Button } from "@/components/ui/button"
import { getProject } from "@/lib/projects"

export function ProjectDetailPage() {
  const { id } = useParams<{ id: string }>()

  const { data: project, isLoading, isError } = useQuery({
    queryKey: ["projects", id],
    queryFn: () => getProject(id!),
    enabled: Boolean(id),
  })

  return (
    <div>
      <Button
        variant="ghost"
        size="sm"
        nativeButton={false}
        render={<Link to="/projects" />}
      >
        <ArrowLeft className="h-4 w-4" />
        Quay lại danh sách Project
      </Button>

      {isLoading && <p className="mt-6 text-muted-foreground">Đang tải...</p>}

      {isError && (
        <p className="mt-6 text-destructive">
          Không tìm thấy project hoặc bạn không có quyền truy cập.
        </p>
      )}

      {project && (
        <div className="mt-4">
          <h1 className="text-2xl font-semibold">{project.name}</h1>
          <p className="mt-2 text-muted-foreground">
            {project.description || "Không có mô tả"}
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            Tạo ngày {new Date(project.createdAt).toLocaleDateString("vi-VN")}
          </p>

          <div className="mt-8 rounded-lg border border-dashed border-border p-6 text-center text-muted-foreground">
            Danh sách Endpoint sẽ hiển thị ở đây (Module 3 — Import & Parse OpenAPI)
          </div>
        </div>
      )}
    </div>
  )
}
