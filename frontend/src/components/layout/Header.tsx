import { useState } from "react"
import { useNavigate } from "react-router-dom"
import { KeyRound, LogOut, User } from "lucide-react"
import { Button } from "@/components/ui/button"
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

export function Header() {
  const navigate = useNavigate()
  const [changePasswordOpen, setChangePasswordOpen] = useState(false)

  function handleLogout() {
    clearToken()
    navigate("/login", { replace: true })
  }

  return (
    <header className="flex h-14 items-center justify-between border-b border-border px-4">
      <div className="md:hidden font-semibold">AI API Testing Assistant</div>
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
