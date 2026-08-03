import { useNavigate } from "react-router-dom"
import { LogOut } from "lucide-react"
import { Button } from "@/components/ui/button"
import { ModeToggle } from "@/components/mode-toggle"
import { clearToken } from "@/lib/api"

export function Header() {
  const navigate = useNavigate()

  function handleLogout() {
    clearToken()
    navigate("/login", { replace: true })
  }

  return (
    <header className="flex h-14 items-center justify-between border-b border-border px-4">
      <div className="md:hidden font-semibold">AI API Testing Agent</div>
      <div className="ml-auto flex items-center gap-2">
        <ModeToggle />
        <Button variant="ghost" size="icon" onClick={handleLogout} title="Đăng xuất">
          <LogOut className="h-4 w-4" />
        </Button>
      </div>
    </header>
  )
}
