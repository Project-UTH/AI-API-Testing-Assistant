import { useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Pencil, Trash2 } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { ApiError } from "@/lib/api"
import {
  createAuthProfile,
  deleteAuthProfile,
  listAuthProfiles,
  updateAuthProfile,
  type AuthProfile,
  type AuthProfileInput,
} from "@/lib/authProfiles"
import type { TargetAuthType } from "@/lib/endpoints"
import { AUTH_TYPE_LABEL, authValueHint, authValueLabel } from "@/lib/authTypes"

const selectClassName =
  "h-8 w-full min-w-0 rounded-lg border border-input bg-transparent px-2.5 text-sm outline-none dark:bg-input/30"

const TYPE_LABEL: Record<TargetAuthType, string> = { ...AUTH_TYPE_LABEL, NONE: "Không token (ẩn danh)" }

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: string
}

/**
 * Module 17 (3.1) - quản lý các vai trò (profile xác thực) của target API: HR, Candidate, Admin... Token mã hoá ở backend và
 * không bao giờ trả lại, nên lúc sửa để trống ô token = giữ token cũ. Token chung của project (nút "Sửa xác thực") vẫn là vai
 * trò mặc định.
 */
export function AuthProfilesDialog({ open, onOpenChange, projectId }: Props) {
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState<AuthProfile | null>(null)
  const [name, setName] = useState("")
  const [roleKey, setRoleKey] = useState("")
  const [authType, setAuthType] = useState<TargetAuthType>("BEARER_TOKEN")
  const [authValue, setAuthValue] = useState("")
  const [loginMode, setLoginMode] = useState(false)
  const [loginPath, setLoginPath] = useState("")
  const [loginBody, setLoginBody] = useState("")
  const [tokenJsonPath, setTokenJsonPath] = useState("")

  const { data: profiles = [], isLoading } = useQuery({
    queryKey: ["auth-profiles", projectId],
    queryFn: () => listAuthProfiles(projectId),
    enabled: open,
  })

  function resetForm() {
    setEditing(null)
    setName("")
    setRoleKey("")
    setAuthType("BEARER_TOKEN")
    setAuthValue("")
    setLoginMode(false)
    setLoginPath("")
    setLoginBody("")
    setTokenJsonPath("")
  }

  function startEdit(profile: AuthProfile) {
    setEditing(profile)
    setName(profile.name)
    setRoleKey(profile.roleKey)
    setAuthType(profile.authType)
    setAuthValue("")
    setLoginMode(profile.loginMode)
    setLoginPath(profile.loginPath ?? "")
    setLoginBody("")
    setTokenJsonPath(profile.tokenJsonPath ?? "")
  }

  const saveMutation = useMutation({
    mutationFn: () => {
      const input: AuthProfileInput = loginMode
        ? { name, authType: "BEARER_TOKEN", loginPath, loginBody: loginBody || undefined, tokenJsonPath }
        : { name, authType, authValue: authType === "NONE" ? undefined : authValue || undefined }
      return editing
        ? updateAuthProfile(projectId, editing.id, input)
        : createAuthProfile(projectId, { ...input, roleKey: roleKey || undefined })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["auth-profiles", projectId] })
      resetForm()
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (profileId: string) => deleteAuthProfile(projectId, profileId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["auth-profiles", projectId] }),
  })

  const error = saveMutation.error ?? deleteMutation.error
  const needsValue = authType !== "NONE" && !editing && !loginMode

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Vai trò (profile xác thực)</DialogTitle>
          <DialogDescription>
            Khai báo token của từng vai trò (HR, Candidate, Admin...) để test cùng 1 API bằng nhiều vai trò. Token mã hoá AES-256
            và không bao giờ hiển thị lại. Token chung của project vẫn là vai trò mặc định.
          </DialogDescription>
        </DialogHeader>

        {isLoading ? (
          <p className="text-sm text-muted-foreground">Đang tải...</p>
        ) : profiles.length === 0 ? (
          <p className="text-sm text-muted-foreground">Chưa có vai trò nào - thêm vai trò đầu tiên bên dưới.</p>
        ) : (
          <ul className="flex flex-col divide-y divide-border rounded-md border border-border text-sm">
            {profiles.map((p) => (
              <li key={p.id} className="flex flex-wrap items-center gap-2 px-3 py-2">
                <span className="font-medium">{p.name}</span>
                <code className="rounded bg-muted px-1.5 py-0.5 text-xs">{p.roleKey}</code>
                <span className="text-xs text-muted-foreground">
                  {p.loginMode ? `Tự đăng nhập (${p.loginPath})` : TYPE_LABEL[p.authType]}
                  {p.loginMode
                    ? p.hasValue ? " · đã có tài khoản" : " · CHƯA có tài khoản"
                    : p.authType !== "NONE" && (p.hasValue ? " · đã có token" : " · CHƯA có token")}
                  {" · "}
                  {p.testCaseCount} test case
                </span>
                <div className="ml-auto flex gap-1">
                  <Button size="icon-sm" variant="ghost" aria-label={`Sửa ${p.name}`} onClick={() => startEdit(p)}>
                    <Pencil className="h-4 w-4" />
                  </Button>
                  <Button
                    size="icon-sm"
                    variant="ghost"
                    aria-label={`Xoá ${p.name}`}
                    disabled={deleteMutation.isPending || p.testCaseCount > 0}
                    title={p.testCaseCount > 0 ? "Còn test case đang dùng vai trò này" : "Xoá vai trò"}
                    onClick={() => deleteMutation.mutate(p.id)}
                  >
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        )}

        <form
          className="mt-2 flex flex-col gap-3 rounded-md border border-border p-3"
          onSubmit={(e) => {
            e.preventDefault()
            saveMutation.mutate()
          }}
        >
          <p className="text-sm font-medium">{editing ? `Sửa vai trò: ${editing.name}` : "Thêm vai trò"}</p>
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="profile-name">Tên</Label>
              <Input id="profile-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="Nhà tuyển dụng (HR)" required />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="profile-key">Khoá vai trò</Label>
              <Input
                id="profile-key"
                value={roleKey}
                onChange={(e) => setRoleKey(e.target.value)}
                placeholder="HR (bỏ trống = suy từ tên)"
                disabled={Boolean(editing)}
              />
            </div>
            <div className="flex flex-col gap-1.5 sm:col-span-2">
              <Label htmlFor="profile-mode">Cách lấy token</Label>
              <select
                id="profile-mode"
                className={selectClassName}
                value={loginMode ? "LOGIN" : "STATIC"}
                onChange={(e) => setLoginMode(e.target.value === "LOGIN")}
              >
                <option value="STATIC">Nhập token có sẵn</option>
                <option value="LOGIN">Tự đăng nhập để lấy token (token hết hạn vẫn chạy đủ)</option>
              </select>
            </div>
            {loginMode && (
              <>
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor="profile-login-path">Đường dẫn đăng nhập (POST)</Label>
                  <Input id="profile-login-path" value={loginPath} onChange={(e) => setLoginPath(e.target.value)} placeholder="/auth/login" required />
                </div>
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor="profile-token-path">JSONPath tới token trong response</Label>
                  <Input id="profile-token-path" value={tokenJsonPath} onChange={(e) => setTokenJsonPath(e.target.value)} placeholder="data.accessToken" required />
                </div>
                <div className="flex flex-col gap-1.5 sm:col-span-2">
                  <Label htmlFor="profile-login-body">
                    {editing ? "Body đăng nhập mới (bỏ trống = giữ tài khoản cũ)" : "Body đăng nhập (JSON có tài khoản/mật khẩu)"}
                  </Label>
                  <p className="text-xs text-muted-foreground">
                    Mã hoá AES-256, không hiển thị lại. Hệ thống gọi vào base URL của project, token chỉ giữ trong bộ nhớ vài phút và tự đăng nhập lại 1 lần khi gặp 401.
                  </p>
                  <textarea
                    id="profile-login-body"
                    autoComplete="off"
                    value={loginBody}
                    onChange={(e) => setLoginBody(e.target.value)}
                    required={!editing}
                    rows={3}
                    placeholder={'{"email": "hr@example.com", "password": "..."}'}
                    className="w-full rounded-lg border border-input bg-transparent px-2.5 py-1.5 font-mono text-sm outline-none dark:bg-input/30"
                  />
                </div>
              </>
            )}
            {!loginMode && (
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="profile-type">Loại xác thực</Label>
              <select id="profile-type" className={selectClassName} value={authType} onChange={(e) => setAuthType(e.target.value as TargetAuthType)}>
                {(Object.keys(TYPE_LABEL) as TargetAuthType[]).map((t) => (
                  <option key={t} value={t}>
                    {TYPE_LABEL[t]}
                  </option>
                ))}
              </select>
            </div>
            )}
            {!loginMode && authType !== "NONE" && (
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="profile-value">{editing ? "Giá trị mới (bỏ trống = giữ giá trị cũ)" : authValueLabel(authType)}</Label>
                {authValueHint(authType) && <p className="text-xs text-muted-foreground">{authValueHint(authType)}</p>}
                <Input
                  id="profile-value"
                  type="password"
                  autoComplete="off"
                  value={authValue}
                  onChange={(e) => setAuthValue(e.target.value)}
                  required={needsValue}
                />
              </div>
            )}
          </div>
          {error && (
            <p className="text-sm text-destructive">{error instanceof ApiError ? error.message : "Đã xảy ra lỗi, vui lòng thử lại"}</p>
          )}
          <div className="flex gap-2">
            <Button type="submit" size="sm" disabled={saveMutation.isPending}>
              {saveMutation.isPending ? "Đang lưu..." : editing ? "Lưu thay đổi" : "Thêm vai trò"}
            </Button>
            {editing && (
              <Button type="button" size="sm" variant="ghost" onClick={resetForm}>
                Huỷ sửa
              </Button>
            )}
          </div>
        </form>
      </DialogContent>
    </Dialog>
  )
}
