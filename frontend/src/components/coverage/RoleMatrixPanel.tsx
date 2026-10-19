import { useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"
import { ApiError } from "@/lib/api"
import { getRoleMatrix, updateRoleMatrix, type AuthzExpectation } from "@/lib/roleMatrix"

const CELL_LABEL: Record<AuthzExpectation | "NONE", string> = { NONE: "—", ALLOW: "Được", DENY: "Cấm" }
const CELL_STYLE: Record<AuthzExpectation | "NONE", string> = {
  NONE: "text-muted-foreground",
  ALLOW: "bg-green-500/10 text-green-600 dark:text-green-400",
  DENY: "bg-destructive/10 text-destructive",
}
/** Bấm 1 ô: chưa khai → Được → Cấm → chưa khai. */
const NEXT: Record<AuthzExpectation | "NONE", AuthzExpectation | null> = { NONE: "ALLOW", ALLOW: "DENY", DENY: null }

const selectClassName = "h-8 rounded-lg border border-input bg-transparent px-2 text-sm outline-none dark:bg-input/30"

/**
 * Module 17 (17a) - ma trận phân quyền: vai trò nào được phép / bị cấm gọi endpoint nào. Chỉ là khai báo; mỗi ô đã khai trở thành 1
 * mục cần kiểm ở Coverage (nhóm "Phân quyền theo vai trò") và nút Bổ sung tự tạo test cho ô đó (0 token).
 */
export function RoleMatrixPanel({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient()
  const [profileId, setProfileId] = useState("")
  const [expectation, setExpectation] = useState<"ALLOW" | "DENY" | "CLEAR">("DENY")
  const [pathPrefix, setPathPrefix] = useState("")
  const [method, setMethod] = useState("")
  const [message, setMessage] = useState<string | null>(null)

  const { data, isLoading } = useQuery({ queryKey: ["role-matrix", projectId], queryFn: () => getRoleMatrix(projectId) })

  const mutation = useMutation({
    mutationFn: (rule: Parameters<typeof updateRoleMatrix>[1][number]) => updateRoleMatrix(projectId, [rule]),
    onSuccess: (result) => {
      setMessage(`Đã cập nhật ${result.updated} ô.`)
      queryClient.invalidateQueries({ queryKey: ["role-matrix", projectId] })
      queryClient.invalidateQueries({ queryKey: ["project-coverage", projectId] })
    },
    onError: (error) => setMessage(error instanceof ApiError ? error.message : "Không cập nhật được."),
  })

  const profiles = data?.profiles ?? []
  const effectiveProfile = profileId || profiles[0]?.id || ""

  return (
    <details className="rounded-lg border border-border bg-card px-4 py-3 text-sm">
      <summary className="cursor-pointer font-medium">Ma trận phân quyền (endpoint × vai trò)</summary>
      {isLoading && <p className="mt-2 text-xs text-muted-foreground">Đang tải...</p>}
      {data && profiles.length === 0 && (
        <p className="mt-2 text-xs text-muted-foreground">
          Chưa có vai trò nào. Vào trang Endpoint của project, bấm nút &quot;Vai trò&quot; để thêm (HR, Candidate...) rồi quay lại đây.
        </p>
      )}
      {data && profiles.length > 0 && (
        <>
          <p className="mt-2 text-xs text-muted-foreground">
            Khai vai trò nào được phép (Được) hoặc bị cấm (Cấm) gọi từng endpoint. Mỗi ô đã khai thành 1 mục cần kiểm ở Coverage. Bấm 1
            ô để đổi: — → Được → Cấm → —.
          </p>

          <div className="mt-3 flex flex-wrap items-center gap-2">
            <span className="text-xs text-muted-foreground">Áp dụng hàng loạt:</span>
            <select aria-label="Vai trò" className={selectClassName} value={effectiveProfile} onChange={(e) => setProfileId(e.target.value)}>
              {profiles.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
            <select
              aria-label="Kỳ vọng"
              className={selectClassName}
              value={expectation}
              onChange={(e) => setExpectation(e.target.value as "ALLOW" | "DENY" | "CLEAR")}
            >
              <option value="ALLOW">Được phép (2xx)</option>
              <option value="DENY">Bị cấm (403)</option>
              <option value="CLEAR">Xoá khai báo</option>
            </select>
            <input
              aria-label="Tiền tố đường dẫn"
              value={pathPrefix}
              onChange={(e) => setPathPrefix(e.target.value)}
              placeholder="/api/hr/ (trống = tất cả)"
              className="h-8 w-52 rounded-md border border-input bg-background px-2 text-sm"
            />
            <input
              aria-label="Method"
              value={method}
              onChange={(e) => setMethod(e.target.value)}
              placeholder="GET (trống = mọi method)"
              className="h-8 w-44 rounded-md border border-input bg-background px-2 text-sm"
            />
            <Button
              size="sm"
              disabled={mutation.isPending || !effectiveProfile}
              onClick={() =>
                mutation.mutate({
                  profileId: effectiveProfile,
                  pathPrefix: pathPrefix.trim() || undefined,
                  method: method.trim() || undefined,
                  expectation: expectation === "CLEAR" ? null : expectation,
                })
              }
            >
              Áp dụng
            </Button>
            {message && <span className="text-xs text-muted-foreground">{message}</span>}
          </div>

          <div className="mt-3 max-h-96 overflow-auto rounded-md border border-border">
            <table className="w-full text-xs">
              <thead className="sticky top-0 bg-muted/80 backdrop-blur">
                <tr>
                  <th className="px-2 py-1.5 text-left font-medium">Endpoint</th>
                  {profiles.map((p) => (
                    <th key={p.id} className="px-2 py-1.5 text-center font-medium">
                      {p.name}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {data.endpoints.map((row) => (
                  <tr key={row.endpointId} className="border-t border-border">
                    <td className="px-2 py-1 font-mono">
                      <span className="mr-1.5 text-muted-foreground">{row.method}</span>
                      {row.path}
                    </td>
                    {profiles.map((p) => {
                      const current = row.rules[p.id] ?? "NONE"
                      return (
                        <td key={p.id} className="px-1 py-0.5 text-center">
                          <button
                            type="button"
                            disabled={mutation.isPending}
                            aria-label={`${row.method} ${row.path} - ${p.name}: ${CELL_LABEL[current]}`}
                            className={cn("w-14 rounded px-1.5 py-0.5 font-medium hover:ring-1 hover:ring-border", CELL_STYLE[current])}
                            onClick={() =>
                              mutation.mutate({ endpointIds: [row.endpointId], profileId: p.id, expectation: NEXT[current] })
                            }
                          >
                            {CELL_LABEL[current]}
                          </button>
                        </td>
                      )
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </details>
  )
}
