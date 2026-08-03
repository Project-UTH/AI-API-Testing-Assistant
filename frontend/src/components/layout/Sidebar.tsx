import { NavLink } from "react-router-dom"
import { cn } from "@/lib/utils"
import { FolderKanban, LayoutDashboard } from "lucide-react"

const navItems = [
  { to: "/", label: "Tổng quan", icon: LayoutDashboard, end: true },
  { to: "/projects", label: "Project", icon: FolderKanban },
]

export function Sidebar() {
  return (
    <aside className="hidden w-56 shrink-0 border-r border-border bg-card md:block">
      <div className="flex h-14 items-center border-b border-border px-4">
        <span className="font-semibold">AI API Testing Agent</span>
      </div>
      <nav className="flex flex-col gap-1 p-2">
        {navItems.map(({ to, label, icon: Icon, end }) => (
          <NavLink
            key={to}
            to={to}
            end={end}
            className={({ isActive }) =>
              cn(
                "flex items-center gap-2 rounded-md px-3 py-2 text-sm font-medium transition-colors",
                isActive
                  ? "bg-primary text-primary-foreground"
                  : "text-muted-foreground hover:bg-accent hover:text-accent-foreground"
              )
            }
          >
            <Icon className="h-4 w-4" />
            {label}
          </NavLink>
        ))}
      </nav>
    </aside>
  )
}
