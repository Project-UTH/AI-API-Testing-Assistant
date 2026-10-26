import { Link, NavLink, Outlet, useMatch, useParams } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { ArrowLeft } from "lucide-react"

import { getProject } from "@/lib/projects"
import { cn } from "@/lib/utils"

const TABS = [
  { to: "", label: "Endpoint", end: true },
  { to: "test-cases", label: "Test case", end: false },
  { to: "rules", label: "Checklist nghiệp vụ", end: false },
  { to: "coverage", label: "Coverage", end: false },
  { to: "history", label: "Lịch sử", end: false },
  { to: "bug-reports", label: "Bug Report", end: false },
  { to: "ci", label: "Tích hợp CI", end: false },
]

/**
 * Khung chung của mọi trang trong 1 project: tên project + thanh tab. Thay cho hàng 5 nút ở trang Project và các
 * nút "Quay lại Project" ở từng trang con - người dùng luôn biết đang ở project nào và nhảy giữa các phần bằng 1 click.
 * URL các trang không đổi (các trang con vẫn là /projects/:id/<phần>).
 */
export function ProjectLayout() {
  const { id } = useParams<{ id: string }>()
  // Kết quả của 1 lần chạy (/executions/:id) thuộc về lịch sử: tô tab Lịch sử dù URL không khớp /history.
  const onExecutionPage = useMatch("/projects/:id/executions/*") !== null

  const { data: project, isLoading, isError } = useQuery({
    queryKey: ["projects", id],
    queryFn: () => getProject(id!),
    enabled: Boolean(id),
  })

  return (
    <div>
      <Link
        to="/projects"
        className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
      >
        <ArrowLeft className="h-4 w-4" />
        Danh sách Project
      </Link>

      {isLoading && <p className="mt-6 text-muted-foreground">Đang tải...</p>}

      {isError && (
        <p className="mt-6 text-destructive">Không tìm thấy project hoặc bạn không có quyền truy cập.</p>
      )}

      {project && (
        <>
          <div className="mt-3">
            <h1 className="text-2xl font-semibold">{project.name}</h1>
            {project.description && <p className="mt-1 text-sm text-muted-foreground">{project.description}</p>}
          </div>

          <nav
            aria-label="Các phần của project"
            className="sticky top-0 z-20 mt-4 flex gap-1 border-b border-border bg-background"
          >
            {TABS.map((tab) => (
              <NavLink
                key={tab.label}
                to={tab.to}
                end={tab.end}
                className={({ isActive }) =>
                  cn(
                    "-mb-px border-b-2 px-3 py-2 text-sm font-medium transition-colors",
                    isActive || (onExecutionPage && tab.to === "history")
                      ? "border-primary text-foreground"
                      : "border-transparent text-muted-foreground hover:text-foreground"
                  )
                }
              >
                {tab.label}
              </NavLink>
            ))}
          </nav>

          <div className="mt-6">
            <Outlet />
          </div>
        </>
      )}
    </div>
  )
}
