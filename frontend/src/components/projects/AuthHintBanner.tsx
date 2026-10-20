import { useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Loader2, ShieldQuestion, X } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { ApiError } from "@/lib/api"
import { bulkSetRequiresAuth, getAuthSuggestion } from "@/lib/coverage"

const dismissKey = (projectId: string) => `authHint:${projectId}`

function isDismissed(projectId: string): boolean {
  try {
    return localStorage.getItem(dismissKey(projectId)) === "1"
  } catch {
    return false
  }
}

/**
 * Module 17 (S4) - spec không khai báo xác thực nên mọi endpoint bị coi là công khai. Hiện gợi ý theo nhóm đường dẫn; người dùng tự
 * chọn nhóm rồi mới áp dụng (mặc định không chọn gì). Chỉ gửi id của endpoint chưa khai tay nên không ghi đè giá trị đã đặt.
 */
export function AuthHintBanner({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient()
  const [dismissed, setDismissed] = useState(() => isDismissed(projectId))
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [expanded, setExpanded] = useState(false)
  const [done, setDone] = useState<number | null>(null)

  const { data } = useQuery({
    queryKey: ["auth-suggestion", projectId],
    queryFn: () => getAuthSuggestion(projectId),
    enabled: !dismissed,
  })

  const protectedGroups = (data?.groups ?? []).filter((g) => g.suggestedRequiresAuth)

  const apply = useMutation({
    mutationFn: async () => {
      const ids = protectedGroups.filter((g) => selected.has(g.pathPrefix)).flatMap((g) => g.endpointIds)
      return bulkSetRequiresAuth(projectId, { endpointIds: ids, requiresAuth: true })
    },
    onSuccess: (result) => {
      setDone(result.updated)
      setSelected(new Set())
      queryClient.invalidateQueries({ queryKey: ["endpoints", projectId] })
      queryClient.invalidateQueries({ queryKey: ["project-coverage", projectId] })
      queryClient.invalidateQueries({ queryKey: ["auth-suggestion", projectId] })
    },
  })

  function dismiss() {
    try {
      localStorage.setItem(dismissKey(projectId), "1")
    } catch {
      // trình duyệt chặn lưu trữ: chỉ ẩn trong phiên này
    }
    setDismissed(true)
  }

  function toggle(prefix: string, checked: boolean) {
    setSelected((prev) => {
      const next = new Set(prev)
      if (checked) next.add(prefix)
      else next.delete(prefix)
      return next
    })
  }

  if (dismissed) return null
  if (done !== null) {
    return (
      <p role="status" className="rounded-md border border-border bg-muted/40 px-3 py-2 text-sm">
        Đã đánh dấu {done} endpoint cần xác thực. Các mục Security sẽ được tính ở lần xem Coverage kế tiếp.
      </p>
    )
  }
  if (!data?.hint || protectedGroups.length === 0) return null

  const selectedCount = protectedGroups.filter((g) => selected.has(g.pathPrefix)).reduce((sum, g) => sum + g.count, 0)
  const shown = expanded ? protectedGroups : protectedGroups.slice(0, 5)

  return (
    <section aria-label="Gợi ý xác thực" className="rounded-lg border border-amber-500/40 bg-amber-500/5 p-3 text-sm">
      <div className="flex items-start justify-between gap-3">
        <p className="flex items-start gap-2 font-medium">
          <ShieldQuestion className="mt-0.5 size-4 shrink-0 text-amber-600 dark:text-amber-400" aria-hidden />
          Spec không khai báo xác thực nào. Gợi ý đánh dấu {data.suggestedProtected} endpoint cần xác thực theo nhóm đường dẫn.
        </p>
        <Button type="button" variant="ghost" size="icon-sm" aria-label="Ẩn gợi ý xác thực" onClick={dismiss}>
          <X className="size-4" />
        </Button>
      </div>
      <p className="mt-1 text-xs text-muted-foreground">
        Đoán theo tên đường dẫn nên có thể sai: chỉ tick nhóm bạn chắc chắn cần token. Đường dẫn dạng public/auth/login/health được giữ là công khai.
        {data.alreadyManual > 0 && ` ${data.alreadyManual} endpoint bạn đã tự khai sẽ không bị đổi.`}
      </p>
      <ul className="mt-2 flex flex-col gap-1.5">
        {shown.map((g) => (
          <li key={g.pathPrefix}>
            <label className="flex cursor-pointer items-start gap-2">
              <Checkbox
                className="mt-0.5"
                checked={selected.has(g.pathPrefix)}
                onCheckedChange={(checked) => toggle(g.pathPrefix, checked === true)}
              />
              <span>
                <code className="font-mono text-xs">{g.pathPrefix}</code> · {g.count} endpoint
                <span className="block text-xs text-muted-foreground">VD: {g.samplePaths.join(", ")}</span>
              </span>
            </label>
          </li>
        ))}
      </ul>
      {protectedGroups.length > 5 && (
        <Button type="button" variant="link" size="sm" className="px-0" onClick={() => setExpanded((v) => !v)}>
          {expanded ? "Thu gọn" : `Xem tất cả ${protectedGroups.length} nhóm`}
        </Button>
      )}
      <div className="mt-2 flex flex-wrap items-center gap-2">
        <Button type="button" size="sm" disabled={selectedCount === 0 || apply.isPending} onClick={() => apply.mutate()}>
          {apply.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
          {apply.isPending ? "Đang áp dụng..." : `Đánh dấu cần xác thực (${selectedCount})`}
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={() => setSelected(new Set(protectedGroups.map((g) => g.pathPrefix)))}>
          Chọn tất cả
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={dismiss}>
          Để sau
        </Button>
      </div>
      {apply.isError && (
        <p role="alert" className="mt-2 text-xs text-destructive">
          {apply.error instanceof ApiError ? apply.error.message : "Không áp dụng được, vui lòng thử lại."}
        </p>
      )}
    </section>
  )
}
