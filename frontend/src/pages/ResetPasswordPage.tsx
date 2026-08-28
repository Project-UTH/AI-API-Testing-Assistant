import { useState } from "react"
import { Link, useSearchParams } from "react-router-dom"
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
import { ApiError } from "@/lib/api"
import { requestPasswordReset, resetPassword } from "@/lib/auth"

export function ResetPasswordPage() {
  const [searchParams] = useSearchParams()
  const email = searchParams.get("email") ?? ""

  const [otp, setOtp] = useState("")
  const [newPassword, setNewPassword] = useState("")
  const [confirmPassword, setConfirmPassword] = useState("")
  const [confirmError, setConfirmError] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  const resendMutation = useMutation({
    mutationFn: () => requestPasswordReset(email),
  })

  const resetMutation = useMutation({
    mutationFn: () => resetPassword(email, otp, newPassword),
    onSuccess: () => setDone(true),
  })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setConfirmError(null)

    if (newPassword !== confirmPassword) {
      setConfirmError("Xác nhận mật khẩu mới không khớp")
      return
    }

    resetMutation.mutate()
  }

  if (!email) {
    return (
      <AuthLayout>
        <Card className="w-full max-w-sm border border-border bg-card/80 shadow-xl shadow-black/5 ring-0 backdrop-blur-md">
          <CardHeader>
            <CardTitle className="text-2xl font-heading font-semibold">
              Nhập mã xác nhận
            </CardTitle>
            <CardDescription>
              Thiếu thông tin email - vui lòng thực hiện lại từ bước quên mật khẩu
            </CardDescription>
          </CardHeader>
          <CardContent>
            <Button
              className="w-full"
              render={<Link to="/forgot-password">Quay lại quên mật khẩu</Link>}
            />
          </CardContent>
        </Card>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout>
      <Card className="w-full max-w-sm border border-border bg-card/80 shadow-xl shadow-black/5 ring-0 backdrop-blur-md">
        <CardHeader>
          <CardTitle className="text-2xl font-heading font-semibold">
            Nhập mã xác nhận
          </CardTitle>
          <CardDescription>
            {done
              ? "Đổi mật khẩu thành công"
              : `Mã xác nhận đã được gửi tới ${email} (nếu email này đã đăng ký)`}
          </CardDescription>
        </CardHeader>
        <CardContent>
          {done ? (
            <div className="flex flex-col gap-4">
              <p className="text-sm text-muted-foreground">
                Mật khẩu của bạn đã được đổi thành công. Hãy đăng nhập lại bằng
                mật khẩu mới.
              </p>
              <Button render={<Link to="/login">Đăng nhập</Link>} />
            </div>
          ) : (
            <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
              <div className="flex flex-col gap-2 rounded-lg border border-border bg-muted/40 p-3">
                <Label htmlFor="otp">Mã xác nhận (từ email)</Label>
                <Input
                  id="otp"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  required
                  maxLength={6}
                  className="text-center font-mono text-lg tracking-[0.5em]"
                  value={otp}
                  onChange={(e) => setOtp(e.target.value)}
                />
              </div>

              <div className="h-px bg-border" />

              <div className="flex flex-col gap-4">
                <p className="text-sm font-medium">Đặt mật khẩu mới</p>

                <div className="flex flex-col gap-2">
                  <Label htmlFor="new-password">Mật khẩu mới</Label>
                  <Input
                    id="new-password"
                    type="password"
                    required
                    minLength={8}
                    autoComplete="new-password"
                    value={newPassword}
                    onChange={(e) => setNewPassword(e.target.value)}
                  />
                </div>

                <div className="flex flex-col gap-2">
                  <Label htmlFor="confirm-password">Xác nhận mật khẩu mới</Label>
                  <Input
                    id="confirm-password"
                    type="password"
                    required
                    minLength={8}
                    autoComplete="new-password"
                    value={confirmPassword}
                    onChange={(e) => setConfirmPassword(e.target.value)}
                  />
                </div>
              </div>

              {confirmError && (
                <p className="text-sm text-destructive">{confirmError}</p>
              )}

              {resetMutation.isError && (
                <p className="text-sm text-destructive">
                  {resetMutation.error instanceof ApiError
                    ? resetMutation.error.message
                    : "Đã xảy ra lỗi, vui lòng thử lại"}
                </p>
              )}

              <Button type="submit" disabled={resetMutation.isPending}>
                {resetMutation.isPending ? "Đang xử lý..." : "Đổi mật khẩu"}
              </Button>
              <p className="text-center text-sm text-muted-foreground">
                <button
                  type="button"
                  className="text-primary underline"
                  onClick={() => resendMutation.mutate()}
                  disabled={resendMutation.isPending}
                >
                  {resendMutation.isPending ? "Đang gửi..." : "Gửi lại mã"}
                </button>
                {" · "}
                <Link to="/forgot-password" className="text-primary underline">
                  Nhập email khác
                </Link>
              </p>
              {resendMutation.isSuccess && (
                <p className="text-center text-sm text-muted-foreground">
                  Đã gửi lại mã xác nhận
                </p>
              )}
            </form>
          )}
        </CardContent>
      </Card>
    </AuthLayout>
  )
}
