import { NavLink } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { cn } from "@/lib/utils"
import { FolderKanban, History, LayoutDashboard, ScrollText, ShieldCheck, Users } from "lucide-react"
import { getCurrentUserInfo } from "@/lib/auth"

const navItems = [
  { to: "/", label: "Tổng quan", icon: LayoutDashboard, end: true },
  { to: "/projects", label: "Project", icon: FolderKanban },
  { to: "/history", label: "Lịch sử", icon: History },
]

const adminNavItems = [
  { to: "/admin", label: "Tổng quan", icon: LayoutDashboard, end: true },
  { to: "/admin/users", label: "Người dùng", icon: Users },
  { to: "/admin/audit-log", label: "Nhật ký", icon: ScrollText },
]

function navLinkClass({ isActive }: { isActive: boolean }) {
  return cn(
    "flex items-center gap-2 rounded-md px-3 py-2 text-sm font-medium transition-colors",
    isActive
      ? "bg-primary text-primary-foreground"
      : "text-muted-foreground hover:bg-accent hover:text-accent-foreground"
  )
}

export function Sidebar() {
  // Cùng queryKey với RequireAdmin - dùng chung cache React Query, không gọi thêm request nếu
  // trang hiện tại đã nằm trong /admin/* (RequireAdmin đã fetch trước đó).
  const { data } = useQuery({
    queryKey: ["current-user-info"],
    queryFn: () => getCurrentUserInfo(),
  })
  const isAdmin = data?.role === "ADMIN"

  return (
    <aside className="hidden w-56 shrink-0 border-r border-border bg-card md:block">
      <div className="flex h-14 items-center border-b border-border px-4">
        <span className="font-semibold">AI API Testing Assistant</span>
      </div>
      <nav className="flex flex-col gap-1 p-2">
        {/* Admin không cần các trang dữ liệu của riêng user (Tổng quan/Project/Lịch sử của
            chính họ) - chỉ dùng các trang quản trị bên dưới. */}
        {!isAdmin &&
          navItems.map(({ to, label, icon: Icon, end }) => (
            <NavLink key={to} to={to} end={end} className={navLinkClass}>
              <Icon className="h-4 w-4" />
              {label}
            </NavLink>
          ))}

        {isAdmin && (
          <div>
            <div className="flex items-center gap-2 rounded-md px-3 py-2 text-sm font-semibold text-foreground">
              <ShieldCheck className="h-4 w-4" />
              Quản trị
            </div>
            <div className="ml-4 flex flex-col gap-1 border-l border-border pl-2">
              {adminNavItems.map(({ to, label, icon: Icon, end }) => (
                <NavLink key={to} to={to} end={end} className={navLinkClass}>
                  <Icon className="h-4 w-4" />
                  {label}
                </NavLink>
              ))}
            </div>
          </div>
        )}
      </nav>
    </aside>
  )
}
