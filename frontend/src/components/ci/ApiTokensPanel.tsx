import { useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { KeyRound, Plus } from "lucide-react"

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { CopyButton } from "@/components/ci/CopyButton"
import { ApiError } from "@/lib/api"
import { cn } from "@/lib/utils"
import { createApiToken, listApiTokens, revokeApiToken, type ApiToken } from "@/lib/apiTokens"

const STATUS_LABEL: Record<ApiToken["status"], { label: string; className: string }> = {
  ACTIVE: { label: "Hoạt động", className: "bg-green-500/10 text-green-600 dark:text-green-400" },
  EXPIRED: { label: "Hết hạn", className: "bg-amber-500/10 text-amber-700 dark:text-amber-400" },
  REVOKED: { label: "Đã thu hồi", className: "bg-muted text-muted-foreground" },
}

const EXPIRY_OPTIONS: { label: string; value: number | null }[] = [
  { label: "Không hết hạn", value: null },
  { label: "30 ngày", value: 30 },
  { label: "90 ngày", value: 90 },
  { label: "365 ngày", value: 365 },
]

function formatDate(iso: string | null, empty: string) {
  return iso ? new Date(iso).toLocaleString("vi-VN", { day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit" }) : empty
}

/**
 * Module 17 (17d) - quản lý token API của project cho CI. Token thô chỉ tồn tại trong state `createdValue` đến khi người dùng bấm
 * "Tôi đã lưu token": KHÔNG đưa vào cache query, localStorage hay log; dialog hiển thị không đóng được bằng click ngoài/Esc.
 */
export function ApiTokensPanel({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [name, setName] = useState("")
  const [expiresInDays, setExpiresInDays] = useState<number | null>(90)
  const [createdValue, setCreatedValue] = useState<string | null>(null)
  const [revoking, setRevoking] = useState<ApiToken | null>(null)

  const { data: tokens = [], isLoading } = useQuery({
    queryKey: ["api-tokens", projectId],
    queryFn: () => listApiTokens(projectId),
  })

  const createMutation = useMutation({
    mutationFn: () => createApiToken(projectId, { name: name.trim(), expiresInDays }),
    onSuccess: (created) => {
      setCreatedValue(created.value) // CHỈ giữ trong state, không setQueryData
      setCreateOpen(false)
      setName("")
      createMutation.reset() // mutation.data còn giữ token - xoá ngay
      queryClient.invalidateQueries({ queryKey: ["api-tokens", projectId] })
    },
  })

  const revokeMutation = useMutation({
    mutationFn: (token: ApiToken) => revokeApiToken(projectId, token.id),
    onSuccess: () => {
      setRevoking(null)
      queryClient.invalidateQueries({ queryKey: ["api-tokens", projectId] })
    },
  })

  return (
    <section className="rounded-lg border border-border bg-card p-4">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h3 className="flex items-center gap-2 font-semibold">
            <KeyRound className="h-4 w-4" /> Token API cho CI
          </h3>
          <p className="mt-0.5 text-xs text-muted-foreground">
            Token chỉ gọi được API chạy test và lấy báo cáo của project này (không đăng nhập được vào ứng dụng). Chỉ lưu băm - không xem lại được sau khi tạo.
          </p>
        </div>
        <Button size="sm" onClick={() => setCreateOpen(true)}>
          <Plus className="h-4 w-4" /> Tạo token
        </Button>
      </div>

      {isLoading ? (
        <p className="mt-3 text-sm text-muted-foreground">Đang tải...</p>
      ) : tokens.length === 0 ? (
        <p className="mt-3 text-sm text-muted-foreground">Chưa có token nào.</p>
      ) : (
        <div className="mt-3 overflow-auto rounded-md border border-border">
          <table className="w-full text-sm">
            <thead className="bg-muted/60 text-xs">
              <tr>
                <th className="px-3 py-1.5 text-left font-medium">Tên</th>
                <th className="px-3 py-1.5 text-left font-medium">Tiền tố</th>
                <th className="px-3 py-1.5 text-left font-medium">Hết hạn</th>
                <th className="px-3 py-1.5 text-left font-medium">Dùng gần nhất</th>
                <th className="px-3 py-1.5 text-left font-medium">Trạng thái</th>
                <th className="px-3 py-1.5" />
              </tr>
            </thead>
            <tbody>
              {tokens.map((token) => {
                const status = STATUS_LABEL[token.status]
                const inactive = token.status !== "ACTIVE"
                return (
                  <tr key={token.id} className={cn("border-t border-border", inactive && "opacity-60")}>
                    <td className="px-3 py-1.5">{token.name}</td>
                    <td className="px-3 py-1.5">
                      <code className="text-xs">{token.prefix}…</code>
                    </td>
                    <td className="px-3 py-1.5 text-xs">{formatDate(token.expiresAt, "Không hết hạn")}</td>
                    <td className="px-3 py-1.5 text-xs">{formatDate(token.lastUsedAt, "Chưa dùng")}</td>
                    <td className="px-3 py-1.5">
                      <span className={cn("rounded-full px-2 py-0.5 text-xs font-medium", status.className)}>{status.label}</span>
                    </td>
                    <td className="px-3 py-1.5 text-right">
                      {token.status === "ACTIVE" && (
                        <Button size="xs" variant="ghost" className="text-destructive" onClick={() => setRevoking(token)}>
                          Thu hồi
                        </Button>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* Tạo token */}
      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent>
          <form
            className="flex flex-col gap-4"
            onSubmit={(e) => {
              e.preventDefault()
              createMutation.mutate()
            }}
          >
            <DialogHeader>
              <DialogTitle>Tạo token API</DialogTitle>
              <DialogDescription>Đặt tên theo nơi dùng (vd &quot;GitHub Actions&quot;) để biết thu hồi token nào khi cần.</DialogDescription>
            </DialogHeader>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="token-name">Tên</Label>
              <Input id="token-name" value={name} maxLength={100} onChange={(e) => setName(e.target.value)} required />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="token-expiry">Hạn dùng</Label>
              <select
                id="token-expiry"
                className="h-8 w-full rounded-lg border border-input bg-transparent px-2.5 text-sm outline-none dark:bg-input/30"
                value={expiresInDays === null ? "" : String(expiresInDays)}
                onChange={(e) => setExpiresInDays(e.target.value === "" ? null : Number(e.target.value))}
              >
                {EXPIRY_OPTIONS.map((o) => (
                  <option key={o.label} value={o.value === null ? "" : String(o.value)}>
                    {o.label}
                  </option>
                ))}
              </select>
            </div>
            {createMutation.isError && (
              <p className="text-sm text-destructive">
                {createMutation.error instanceof ApiError ? createMutation.error.message : "Không tạo được token, vui lòng thử lại"}
              </p>
            )}
            <DialogFooter>
              <Button type="submit" disabled={createMutation.isPending || name.trim().length === 0}>
                {createMutation.isPending ? "Đang tạo..." : "Tạo token"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Hiển thị token 1 lần: không đóng được bằng click ngoài/Esc/nút X, chỉ bằng nút xác nhận */}
      <Dialog open={createdValue !== null} onOpenChange={() => undefined}>
        <DialogContent showCloseButton={false}>
          <DialogHeader>
            <DialogTitle>Token đã được tạo</DialogTitle>
            <DialogDescription>
              Sao chép và lưu token vào nơi an toàn (vd secret của CI) <strong>ngay bây giờ</strong>. Token chỉ hiện một lần, không xem lại được.
            </DialogDescription>
          </DialogHeader>
          <div role="alert" className="rounded-md border border-amber-500/40 bg-amber-500/10 p-2 text-xs text-amber-700 dark:text-amber-400">
            Ai có token này gọi được API chạy test của project. Đừng đưa vào mã nguồn hay chat.
          </div>
          <Input readOnly value={createdValue ?? ""} onFocus={(e) => e.currentTarget.select()} className="font-mono text-xs" aria-label="Giá trị token" />
          <DialogFooter>
            <CopyButton text={createdValue ?? ""} />
            <Button onClick={() => setCreatedValue(null)}>Tôi đã lưu token</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Thu hồi */}
      <AlertDialog open={revoking !== null} onOpenChange={(open) => !open && setRevoking(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Thu hồi token &quot;{revoking?.name}&quot;?</AlertDialogTitle>
            <AlertDialogDescription>CI đang dùng token này sẽ bị lỗi 401 ngay. Không hoàn tác được - cần tạo token mới.</AlertDialogDescription>
          </AlertDialogHeader>
          {revokeMutation.isError && <p className="text-sm text-destructive">Không thu hồi được, vui lòng thử lại.</p>}
          <AlertDialogFooter>
            <AlertDialogCancel disabled={revokeMutation.isPending}>Huỷ</AlertDialogCancel>
            <AlertDialogAction variant="destructive" disabled={revokeMutation.isPending} onClick={() => revoking && revokeMutation.mutate(revoking)}>
              {revokeMutation.isPending ? "Đang thu hồi..." : "Thu hồi"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </section>
  )
}
