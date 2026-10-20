import { useState } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
import { bulkSetRequiresAuth } from "@/lib/coverage"

/**
 * Module 17 (1.3) - đặt "yêu cầu xác thực" hàng loạt theo tiền tố đường dẫn (vd /api/hr/). Dành cho API không khai security trong
 * spec: hệ thống không biết endpoint nào cần token nên mọi mục Security bị ghi là không áp dụng.
 */
export function BulkAuthBar({ projectId, endpointCount }: { projectId: string; endpointCount: number }) {
  const queryClient = useQueryClient()
  const [prefix, setPrefix] = useState("")
  const [message, setMessage] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (requiresAuth: boolean | null) =>
      bulkSetRequiresAuth(projectId, { pathPrefix: prefix.trim() || undefined, requiresAuth }),
    onSuccess: (result) => {
      setMessage(`Đã cập nhật ${result.updated} endpoint.`)
      queryClient.invalidateQueries({ queryKey: ["project-coverage", projectId] })
      queryClient.invalidateQueries({ queryKey: ["endpoints", projectId] })
    },
    onError: (error) => setMessage(error instanceof Error ? error.message : "Không cập nhật được."),
  })

  if (endpointCount === 0) return null

  return (
    <details className="rounded-lg border border-border bg-card px-4 py-3 text-sm">
      <summary className="cursor-pointer font-medium">Đặt hàng loạt: endpoint nào yêu cầu xác thực?</summary>
      <p className="mt-2 text-xs text-muted-foreground">
        Nếu spec không khai security thì mọi mục Security bị ghi là &quot;không áp dụng&quot;. Nhập tiền tố đường dẫn (để trống = mọi
        endpoint) rồi chọn giá trị. Không xoá test case nào.
      </p>
      <div className="mt-2 flex flex-wrap items-center gap-2">
        <input
          value={prefix}
          onChange={(e) => setPrefix(e.target.value)}
          placeholder="/api/hr/"
          aria-label="Tiền tố đường dẫn"
          className="h-8 w-48 rounded-md border border-input bg-background px-2 text-sm"
        />
        <Button size="sm" disabled={mutation.isPending} onClick={() => mutation.mutate(true)}>
          Yêu cầu xác thực
        </Button>
        <Button size="sm" variant="outline" disabled={mutation.isPending} onClick={() => mutation.mutate(false)}>
          Công khai
        </Button>
        <Button size="sm" variant="ghost" disabled={mutation.isPending} onClick={() => mutation.mutate(null)}>
          Về theo spec
        </Button>
        {message && <span className="text-xs text-muted-foreground">{message}</span>}
      </div>
    </details>
  )
}
