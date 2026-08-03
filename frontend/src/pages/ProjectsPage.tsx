import { useState } from "react"
import { useNavigate } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { MoreVertical, Plus } from "lucide-react"

import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { ProjectFormDialog } from "@/components/projects/ProjectFormDialog"
import { DeleteProjectDialog } from "@/components/projects/DeleteProjectDialog"
import { listProjects, type Project } from "@/lib/projects"

export function ProjectsPage() {
  const navigate = useNavigate()
  const { data, isLoading, isError } = useQuery({
    queryKey: ["projects"],
    queryFn: listProjects,
  })

  const [formOpen, setFormOpen] = useState(false)
  const [editingProject, setEditingProject] = useState<Project | undefined>()
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [deletingProject, setDeletingProject] = useState<Project | null>(null)

  function openCreateForm() {
    setEditingProject(undefined)
    setFormOpen(true)
  }

  function openEditForm(project: Project) {
    setEditingProject(project)
    setFormOpen(true)
  }

  function openDeleteConfirm(project: Project) {
    setDeletingProject(project)
    setDeleteOpen(true)
  }

  const projects = data?.data ?? []

  return (
    <div>
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Project</h1>
          <p className="mt-1 text-muted-foreground">
            Quản lý các project kiểm thử API của bạn
          </p>
        </div>
        <Button onClick={openCreateForm}>
          <Plus className="h-4 w-4" />
          Tạo Project
        </Button>
      </div>

      {isLoading && (
        <p className="mt-8 text-muted-foreground">Đang tải...</p>
      )}

      {isError && (
        <p className="mt-8 text-destructive">
          Không tải được danh sách project, vui lòng thử lại.
        </p>
      )}

      {!isLoading && !isError && projects.length === 0 && (
        <p className="mt-8 text-muted-foreground">
          Chưa có project nào. Bấm "Tạo Project" để bắt đầu.
        </p>
      )}

      <div className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {projects.map((project) => (
          <Card
            key={project.id}
            className="cursor-pointer transition-shadow hover:shadow-md"
            onClick={() => navigate(`/projects/${project.id}`)}
          >
            <CardHeader className="flex flex-row items-start justify-between gap-2">
              <div className="min-w-0">
                <CardTitle className="truncate">{project.name}</CardTitle>
                <CardDescription className="mt-1 line-clamp-2">
                  {project.description || "Không có mô tả"}
                </CardDescription>
              </div>
              <DropdownMenu>
                <DropdownMenuTrigger
                  render={
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      onClick={(e: React.MouseEvent) => e.stopPropagation()}
                    >
                      <MoreVertical className="h-4 w-4" />
                      <span className="sr-only">Tuỳ chọn project</span>
                    </Button>
                  }
                />
                <DropdownMenuContent
                  align="end"
                  onClick={(e: React.MouseEvent) => e.stopPropagation()}
                >
                  <DropdownMenuItem onClick={() => openEditForm(project)}>
                    Sửa
                  </DropdownMenuItem>
                  <DropdownMenuItem
                    variant="destructive"
                    onClick={() => openDeleteConfirm(project)}
                  >
                    Xoá
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </CardHeader>
            <CardContent>
              <p className="text-xs text-muted-foreground">
                Tạo ngày {new Date(project.createdAt).toLocaleDateString("vi-VN")}
              </p>
            </CardContent>
          </Card>
        ))}
      </div>

      <ProjectFormDialog
        open={formOpen}
        onOpenChange={setFormOpen}
        project={editingProject}
      />
      <DeleteProjectDialog
        open={deleteOpen}
        onOpenChange={setDeleteOpen}
        project={deletingProject}
      />
    </div>
  )
}
