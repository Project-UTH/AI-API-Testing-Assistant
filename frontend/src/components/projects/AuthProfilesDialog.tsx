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
} from "@/lib/authProfiles"
import type { TargetAuthType } from "@/lib/endpoints"

const selectClassName =
  "h-8 w-full min-w-0 rounded-lg border border-input bg-transparent px-2.5 text-sm outline-none dark:bg-input/30"

const TYPE_LABEL: Record<TargetAuthType, string> = { NONE: "Không token (ẩn danh)", API_KEY: "API Key", BEARER_TOKEN: "Bearer Token" }

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
  }

  function startEdit(profile: AuthProfile) {
    setEditing(profile)
    setName(profile.name)
    setRoleKey(profile.roleKey)
    setAuthType(profile.authType)
    setAuthValue("")
  }

  const saveMutation = useMutation({
    mutationFn: () => {
      const input = { name, authType, authValue: authType === "NONE" ? undefined : authValue || undefined }
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
  const needsValue = authType !== "NONE" && !editing

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
                  {TYPE_LABEL[p.authType]}
                  {p.authType !== "NONE" && (p.hasValue ? " · đã có token" : " · CHƯA có token")}
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
            {authType !== "NONE" && (
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="profile-value">{editing ? "Token mới (bỏ trống = giữ token cũ)" : "Token"}</Label>
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
