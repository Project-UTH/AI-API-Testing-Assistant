import { useMemo, useState } from "react"
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
import { Input } from "@/components/ui/input"
import { Pagination } from "@/components/shared/Pagination"
import { ProjectFormDialog } from "@/components/projects/ProjectFormDialog"
import { DeleteProjectDialog } from "@/components/projects/DeleteProjectDialog"
import { listProjects, type Project } from "@/lib/projects"

const PAGE_SIZE = 12

export function ProjectsPage() {
  const navigate = useNavigate()
  const { data, isLoading, isError } = useQuery({
    queryKey: ["projects"],
    queryFn: () => listProjects(200),
  })
  // Tải tối đa 200 project mới nhất rồi tìm/phân trang ở máy: trước đây chỉ hiện 20 project đầu và không có cách xem phần còn lại.
  const [search, setSearch] = useState("")
  const [page, setPage] = useState(0)

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

  const allProjects = data?.data ?? []
  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    return q ? allProjects.filter((p) => p.name.toLowerCase().includes(q) || (p.description ?? "").toLowerCase().includes(q)) : allProjects
  }, [allProjects, search])
  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE))
  const currentPage = Math.min(page, totalPages - 1)
  const projects = filtered.slice(currentPage * PAGE_SIZE, (currentPage + 1) * PAGE_SIZE)

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

      {allProjects.length > PAGE_SIZE && (
        <div className="mt-4 max-w-sm">
          <Input
            aria-label="Tìm project theo tên"
            placeholder="Tìm project theo tên hoặc mô tả..."
            value={search}
            onChange={(e) => {
              setSearch(e.target.value)
              setPage(0)
            }}
          />
        </div>
      )}

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

      {filtered.length > PAGE_SIZE && (
        <Pagination
          className="mt-4"
          page={currentPage}
          totalPages={totalPages}
          totalElements={filtered.length}
          shownCount={projects.length}
          size={PAGE_SIZE}
          onPageChange={setPage}
          isFetching={false}
          itemLabel="project"
        />
      )}
      {search.trim() && filtered.length === 0 && <p className="mt-6 text-muted-foreground">Không có project nào khớp "{search}".</p>}

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
