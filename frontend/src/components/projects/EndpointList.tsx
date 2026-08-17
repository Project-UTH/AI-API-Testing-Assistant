import { useState } from "react"
import { Link } from "react-router-dom"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { Loader2, Sparkles } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { cn, METHOD_STYLES } from "@/lib/utils"
import { ApiError } from "@/lib/api"
import { listEndpoints } from "@/lib/endpoints"
import { generateTestCases } from "@/lib/testcases"

interface EndpointListProps {
  projectId: string
}

type GenerationState =
  | { status: "pending" }
  | { status: "success"; count: number }
  | { status: "error"; message: string }

export function EndpointList({ projectId }: EndpointListProps) {
  const queryClient = useQueryClient()
  const { data, isLoading, isError } = useQuery({
    queryKey: ["endpoints", projectId],
    queryFn: () => listEndpoints(projectId),
  })

  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  const [generationState, setGenerationState] = useState<Record<string, GenerationState>>({})
  const [includeSecurity, setIncludeSecurity] = useState(false)
  const [includeAssertions, setIncludeAssertions] = useState(false)

  const endpoints = data?.data ?? []

  function toggleSelect(endpointId: string, checked: boolean) {
    setSelectedIds((prev) => {
      const next = new Set(prev)
      if (checked) {
        next.add(endpointId)
      } else {
        next.delete(endpointId)
      }
      return next
    })
  }

  function handleGenerate() {
    // Gọi thẳng generateTestCases (không qua useMutation) - mỗi endpoint là 1 Promise độc lập,
    // đảm bảo callback của từng cái không bị "đè" khi bấm sinh nhiều endpoint cùng lúc (dùng chung
    // 1 instance useMutation cho nhiều lần mutate() đồng thời chỉ đáng tin cậy với lần gọi cuối).
    // Endpoint đã sinh thành công tự bỏ chọn ngay sau khi xong (bên dưới) nên mặc định không bị
    // chọn lại - nếu người dùng CHỦ ĐỘNG tích lại 1 endpoint đã sinh, nghĩa là họ muốn sinh lại
    // (backend tự xoá bộ cũ và thay bằng bộ mới), nên ở đây không cần bỏ qua nữa.
    selectedIds.forEach((endpointId) => {
      setGenerationState((prev) => ({ ...prev, [endpointId]: { status: "pending" } }))
      generateTestCases(projectId, endpointId, { includeSecurity, includeAssertions })
        .then((created) => {
          setGenerationState((prev) => ({
            ...prev,
            [endpointId]: { status: "success", count: created.length },
          }))
          // Đã xong - tự bỏ chọn để không bị tính vào lần "Sinh Test Case" tiếp theo
          setSelectedIds((prev) => {
            const next = new Set(prev)
            next.delete(endpointId)
            return next
          })
          // Đồng bộ lại testCaseCount thật từ server - để trạng thái "đã sinh" không bị mất
          // khi rời trang rồi quay lại (generationState chỉ sống trong phiên hiện tại)
          queryClient.invalidateQueries({ queryKey: ["endpoints", projectId] })
        })
        .catch((error) => {
          setGenerationState((prev) => ({
            ...prev,
            [endpointId]: {
              status: "error",
              message: error instanceof ApiError ? error.message : "Đã xảy ra lỗi, vui lòng thử lại",
            },
          }))
        })
    })
  }

  if (isLoading) {
    return <p className="mt-4 text-muted-foreground">Đang tải danh sách endpoint...</p>
  }

  if (isError) {
    return (
      <p className="mt-4 text-destructive">
        Không tải được danh sách endpoint, vui lòng thử lại.
      </p>
    )
  }

  if (endpoints.length === 0) {
    return (
      <div className="mt-4 rounded-lg border border-dashed border-border p-6 text-center text-muted-foreground">
        Chưa có endpoint nào. Bấm "Import OpenAPI" để nạp định nghĩa API.
      </div>
    )
  }

  return (
    <div className="mt-4 flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <span className="text-sm text-muted-foreground">
          {selectedIds.size > 0 ? `Đã chọn ${selectedIds.size} endpoint` : "Chọn endpoint để sinh test case"}
        </span>
        <div className="flex items-center gap-3">
          <label className="flex items-center gap-1.5 text-sm text-muted-foreground">
            <Checkbox
              checked={includeSecurity}
              onCheckedChange={(checked) => setIncludeSecurity(checked === true)}
            />
            + Security
          </label>
          <label className="flex items-center gap-1.5 text-sm text-muted-foreground">
            <Checkbox
              checked={includeAssertions}
              onCheckedChange={(checked) => setIncludeAssertions(checked === true)}
            />
            + Assertion
          </label>
          <Button size="sm" disabled={selectedIds.size === 0} onClick={handleGenerate}>
            <Sparkles className="h-4 w-4" />
            Sinh Test Case
          </Button>
        </div>
      </div>

      <ul className="flex flex-col gap-2">
        {endpoints.map((endpoint) => {
          // generationState chỉ phản ánh thao tác trong phiên hiện tại (mất khi rời trang);
          // testCaseCount lấy từ server là nguồn sự thật lâu dài - dùng nó làm mặc định khi
          // chưa có trạng thái phiên nào ghi đè lên.
          const state: GenerationState | undefined =
            generationState[endpoint.id] ??
            (endpoint.testCaseCount > 0
              ? { status: "success", count: endpoint.testCaseCount }
              : undefined)
          const alreadyGenerated = state?.status === "success"
          const selected = selectedIds.has(endpoint.id)
          const willRegenerate = alreadyGenerated && selected
          return (
            <li
              key={endpoint.id}
              className="flex items-center gap-3 rounded-lg border border-border p-3"
            >
              <Checkbox
                checked={selected}
                onCheckedChange={(checked) => toggleSelect(endpoint.id, checked === true)}
              />
              <span
                className={cn(
                  "w-16 shrink-0 rounded-md px-2 py-0.5 text-center text-xs font-semibold",
                  METHOD_STYLES[endpoint.method] ?? "bg-muted text-muted-foreground"
                )}
              >
                {endpoint.method}
              </span>
              <span className="min-w-0 flex-1 truncate font-mono text-sm">{endpoint.path}</span>
              {endpoint.summary && (
                <span className="shrink-0 truncate text-sm text-muted-foreground">
                  {endpoint.summary}
                </span>
              )}
              {state?.status === "pending" && (
                <Loader2 className="h-4 w-4 shrink-0 animate-spin text-muted-foreground" />
              )}
              {state?.status === "success" && willRegenerate && (
                <span className="shrink-0 text-xs text-amber-600 dark:text-amber-400">
                  Sinh lại sẽ thay test case AI đã sinh, giữ nguyên test case bạn tự thêm
                </span>
              )}
              {state?.status === "success" && !willRegenerate && (
                <span className="shrink-0 text-xs text-green-600 dark:text-green-400">
                  Có {state.count} test case
                </span>
              )}
              {state?.status === "error" && (
                <span className="shrink-0 text-xs text-destructive">{state.message}</span>
              )}
              {endpoint.testCaseCount > 0 && (
                <Link
                  to={`/projects/${projectId}/test-cases?endpointId=${endpoint.id}`}
                  className="shrink-0 text-xs text-primary underline-offset-4 hover:underline"
                >
                  Xem test case
                </Link>
              )}
            </li>
          )
        })}
      </ul>
    </div>
  )
}
