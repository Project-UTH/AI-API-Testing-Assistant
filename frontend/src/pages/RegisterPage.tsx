import { useState } from "react"
import { Link, useNavigate } from "react-router-dom"
import { useMutation } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { AuthLayout } from "@/components/layout/AuthLayout"
import { GoogleAuthButton } from "@/components/auth/GoogleAuthButton"
import { ApiError, setToken } from "@/lib/api"
import { registerUser } from "@/lib/auth"

export function RegisterPage() {
  const navigate = useNavigate()
  const [email, setEmail] = useState("")
  const [password, setPassword] = useState("")
  const [confirmPassword, setConfirmPassword] = useState("")
  const [formError, setFormError] = useState<string | null>(null)

  const passwordMismatch =
    confirmPassword.length > 0 && password !== confirmPassword

  const mutation = useMutation({
    mutationFn: () => registerUser(email, password),
    onSuccess: (data) => {
      setToken(data.token)
      navigate("/", { replace: true })
    },
  })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    // noValidate + kiểm tra tay: thông báo native của trình duyệt bằng tiếng Anh, lẫn với giao diện tiếng Việt.
    if (!/^\S+@\S+\.\S+$/.test(email.trim())) {
      setFormError("Vui lòng nhập email hợp lệ")
      return
    }
    if (password.length < 8) {
      setFormError("Mật khẩu cần tối thiểu 8 ký tự")
      return
    }
    if (password !== confirmPassword) {
      return
    }
    setFormError(null)
    mutation.mutate()
  }

  return (
    <AuthLayout>
      <Card className="w-full max-w-sm border border-border bg-card/80 shadow-xl shadow-black/5 ring-0 backdrop-blur-md">
        <CardHeader>
          <CardTitle className="text-2xl font-heading font-semibold">
            Đăng ký
          </CardTitle>
          <CardDescription>
            Tạo tài khoản AI API Testing Assistant mới
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form className="flex flex-col gap-4" onSubmit={handleSubmit} noValidate>
            <div className="flex flex-col gap-2">
              <Label htmlFor="email">Email</Label>
              <Input
                id="email"
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
              />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="password">Mật khẩu</Label>
              <Input
                id="password"
                type="password"
                required
                minLength={8}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="confirm-password">Nhập lại mật khẩu</Label>
              <Input
                id="confirm-password"
                type="password"
                required
                minLength={8}
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                aria-invalid={passwordMismatch}
              />
              {passwordMismatch && (
                <p className="text-sm text-destructive">
                  Mật khẩu nhập lại không khớp
                </p>
              )}
            </div>
            {formError && <p className="text-sm text-destructive">{formError}</p>}
            {!formError && mutation.isError && (
              <p className="text-sm text-destructive">
                {mutation.error instanceof ApiError
                  ? mutation.error.message
                  : "Đã xảy ra lỗi, vui lòng thử lại"}
              </p>
            )}
            <Button
              type="submit"
              disabled={mutation.isPending || passwordMismatch}
            >
              {mutation.isPending ? "Đang đăng ký..." : "Đăng ký"}
            </Button>

            <div className="flex items-center gap-3">
              <div className="h-px flex-1 bg-border" />
              <span className="text-xs text-muted-foreground">hoặc</span>
              <div className="h-px flex-1 bg-border" />
            </div>

            <GoogleAuthButton />

            <p className="text-center text-sm text-muted-foreground">
              Đã có tài khoản?{" "}
              <Link to="/login" className="text-primary underline">
                Đăng nhập
              </Link>
            </p>
          </form>
        </CardContent>
      </Card>
    </AuthLayout>
  )
}
