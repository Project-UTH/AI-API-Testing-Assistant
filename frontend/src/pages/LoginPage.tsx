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
import { loginUser } from "@/lib/auth"

export function LoginPage() {
  const navigate = useNavigate()
  const [email, setEmail] = useState("")
  const [password, setPassword] = useState("")

  const mutation = useMutation({
    mutationFn: () => loginUser(email, password),
    onSuccess: (data) => {
      setToken(data.token)
      navigate("/", { replace: true })
    },
  })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    mutation.mutate()
  }

  return (
    <AuthLayout>
      <Card className="w-full max-w-sm border border-border bg-card/80 shadow-xl shadow-black/5 ring-0 backdrop-blur-md">
        <CardHeader>
          <CardTitle className="text-2xl font-heading font-semibold">
            Đăng nhập
          </CardTitle>
          <CardDescription>
            Đăng nhập vào AI API Testing Assistant
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
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
              <div className="flex items-center justify-between">
                <Label htmlFor="password">Mật khẩu</Label>
                <Link
                  to="/forgot-password"
                  className="text-sm text-primary underline"
                >
                  Quên mật khẩu?
                </Link>
              </div>
              <Input
                id="password"
                type="password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </div>
            {mutation.isError && (
              <p className="text-sm text-destructive">
                {mutation.error instanceof ApiError
                  ? mutation.error.message
                  : "Đã xảy ra lỗi, vui lòng thử lại"}
              </p>
            )}
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? "Đang đăng nhập..." : "Đăng nhập"}
            </Button>

            <div className="flex items-center gap-3">
              <div className="h-px flex-1 bg-border" />
              <span className="text-xs text-muted-foreground">hoặc</span>
              <div className="h-px flex-1 bg-border" />
            </div>

            <GoogleAuthButton />

            <p className="text-center text-sm text-muted-foreground">
              Chưa có tài khoản?{" "}
              <Link to="/register" className="text-primary underline">
                Đăng ký
              </Link>
            </p>
          </form>
        </CardContent>
      </Card>
    </AuthLayout>
  )
}
