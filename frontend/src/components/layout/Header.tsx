import { useState } from "react"
import { useNavigate } from "react-router-dom"
import { KeyRound, LogOut, PanelLeftClose, PanelLeftOpen, User } from "lucide-react"
import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { ModeToggle } from "@/components/mode-toggle"
import { clearToken, getCurrentUserEmail } from "@/lib/api"
import { ChangePasswordDialog } from "@/components/account/ChangePasswordDialog"

interface HeaderProps {
  sidebarOpen: boolean
  onToggleSidebar: () => void
}

export function Header({ sidebarOpen, onToggleSidebar }: HeaderProps) {
  const navigate = useNavigate()
  const [changePasswordOpen, setChangePasswordOpen] = useState(false)

  function handleLogout() {
    clearToken()
    navigate("/login", { replace: true })
  }

  return (
    <header className="flex h-14 items-center justify-between border-b border-border px-4">
      <div className="flex items-center gap-3">
        {/* Thanh bên chỉ có từ md trở lên nên nút ẩn/hiện cũng chỉ hiện từ md. */}
        <Button
          variant="ghost"
          size="icon"
          className="hidden md:inline-flex"
          onClick={onToggleSidebar}
          aria-expanded={sidebarOpen}
          aria-controls="app-sidebar"
          aria-label={sidebarOpen ? "Ẩn thanh điều hướng" : "Hiện thanh điều hướng"}
          title={sidebarOpen ? "Ẩn thanh điều hướng" : "Hiện thanh điều hướng"}
        >
          {sidebarOpen ? <PanelLeftClose className="h-4 w-4" /> : <PanelLeftOpen className="h-4 w-4" />}
        </Button>
        {/* Tên ứng dụng: ở màn nhỏ luôn hiện (không có thanh bên); ở màn lớn chỉ hiện khi thanh bên đã ẩn (vì tên nằm sẵn trong thanh bên). */}
        <div className={cn("font-semibold", sidebarOpen && "md:hidden")}>AI API Testing Assistant</div>
      </div>
      <div className="ml-auto flex items-center gap-2">
        <ModeToggle />
        <DropdownMenu>
          <DropdownMenuTrigger
            render={
              <Button variant="ghost" size="icon" title="Tài khoản">
                <User className="h-4 w-4" />
              </Button>
            }
          />
          <DropdownMenuContent align="end">
            <DropdownMenuGroup>
              <DropdownMenuLabel>{getCurrentUserEmail()}</DropdownMenuLabel>
            </DropdownMenuGroup>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={() => setChangePasswordOpen(true)}>
              <KeyRound className="h-4 w-4" />
              Đổi mật khẩu
            </DropdownMenuItem>
            <DropdownMenuItem onClick={handleLogout}>
              <LogOut className="h-4 w-4" />
              Đăng xuất
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
      <ChangePasswordDialog
        open={changePasswordOpen}
        onOpenChange={setChangePasswordOpen}
      />
    </header>
  )
}
