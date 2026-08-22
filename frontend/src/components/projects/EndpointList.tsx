import { useState } from "react"
import { Link } from "react-router-dom"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { Coins, Loader2, Sparkles } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { cn, METHOD_STYLES } from "@/lib/utils"
import { ApiError } from "@/lib/api"
import { listEndpoints } from "@/lib/endpoints"
import { generateTestCases } from "@/lib/testcases"
import { getDashboardSummary } from "@/lib/dashboard"

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

  // Cùng queryKey với Dashboard - dùng chung cache, không gọi thêm request nếu đã ghé trang Tổng
  // quan trước đó. Hiện ngay tại đây (trước khi bấm Sinh Test Case) để người dùng biết còn bao
  // nhiêu token trước khi tốn tiền thật, thay vì chỉ biết sau khi bị 429 AI_QUOTA_EXCEEDED.
  const { data: summary } = useQuery({
    queryKey: ["dashboard-summary"],
    queryFn: () => getDashboardSummary(),
  })

  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  const [generationState, setGenerationState] = useState<Record<string, GenerationState>>({})
  const [includeSecurity, setIncludeSecurity] = useState(false)
  const [includeAssertions, setIncludeAssertions] = useState(false)
  // Mặc định cả 3 đều bật - giữ đúng hành vi cũ (luôn sinh đủ Positive/Negative/Boundary) cho tới
  // khi người dùng chủ động bỏ tích bớt.
  const [includePositive, setIncludePositive] = useState(true)
  const [includeNegative, setIncludeNegative] = useState(true)
  const [includeBoundary, setIncludeBoundary] = useState(true)

  const endpoints = data?.data ?? []
  // Không có gì để sinh nếu không tích ít nhất 1 trong 4 loại - khớp validate phía backend
  // (InvalidRequestException "Phải chọn ít nhất 1 loại test case để sinh").
  const hasAnyCategorySelected = includePositive || includeNegative || includeBoundary || includeSecurity

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
    // Bỏ chọn NGAY khi bấm (không đợi tới lúc xong) - trước đây chỉ bỏ chọn sau khi thành công,
    // nghĩa là bấm "Sinh Test Case" 2 lần liên tiếp trong lúc lần đầu còn đang chạy sẽ gửi 2 request
    // trùng cho cùng 1 endpoint, gây race điều kiện thật ở backend (2 lượt xoá-sinh-lại chồng nhau
    // cho cùng test case -> StaleStateException). Bỏ chọn ngay khiến nút "Sinh Test Case" tự vô hiệu
    // hoá (disabled={selectedIds.size===0}) cho tới khi người dùng chủ động tích lại.
    const idsToGenerate = Array.from(selectedIds)
    setSelectedIds(new Set())
    idsToGenerate.forEach((endpointId) => {
      setGenerationState((prev) => ({ ...prev, [endpointId]: { status: "pending" } }))
      generateTestCases(projectId, endpointId, {
        includeSecurity,
        includeAssertions,
        includePositive,
        includeNegative,
        includeBoundary,
      })
        .then((created) => {
          setGenerationState((prev) => ({
            ...prev,
            [endpointId]: { status: "success", count: created.length },
          }))
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

  const todayTokens = summary?.aiTokensToday ?? 0
  const dailyLimit = summary?.aiDailyTokenLimit ?? 0
  const nearOrOverQuota = dailyLimit > 0 && todayTokens >= dailyLimit

  return (
    <div className="mt-4 flex flex-col gap-3">
      {summary && (
        <div
          className={cn(
            "flex flex-wrap items-center gap-2 rounded-md border px-3 py-2 text-sm",
            nearOrOverQuota
              ? "border-destructive/40 bg-destructive/5"
              : "border-violet-500/30 bg-violet-500/5"
          )}
        >
          <Coins className={cn("h-4 w-4 shrink-0", nearOrOverQuota ? "text-destructive" : "text-violet-500")} />
          <span className="text-muted-foreground">Token AI đã dùng hôm nay:</span>
          <span
            className={cn(
              "text-base font-bold tabular-nums",
              nearOrOverQuota ? "text-destructive" : "text-foreground"
            )}
          >
            {todayTokens.toLocaleString("vi-VN")} / {dailyLimit.toLocaleString("vi-VN")}
          </span>
          {nearOrOverQuota && (
            <span className="font-medium text-destructive">Đã đạt giới hạn hôm nay - Sinh Test Case sẽ bị chặn</span>
          )}
        </div>
      )}
      <div className="flex items-center justify-between">
        <span className="text-sm text-muted-foreground">
          {selectedIds.size > 0 ? `Đã chọn ${selectedIds.size} endpoint` : "Chọn endpoint để sinh test case"}
        </span>
        <div className="flex flex-wrap items-center gap-3">
          <label className="flex items-center gap-1.5 text-sm text-muted-foreground">
            <Checkbox
              checked={includePositive}
              onCheckedChange={(checked) => setIncludePositive(checked === true)}
            />
            Positive
          </label>
          <label className="flex items-center gap-1.5 text-sm text-muted-foreground">
            <Checkbox
              checked={includeNegative}
              onCheckedChange={(checked) => setIncludeNegative(checked === true)}
            />
            Negative
          </label>
          <label className="flex items-center gap-1.5 text-sm text-muted-foreground">
            <Checkbox
              checked={includeBoundary}
              onCheckedChange={(checked) => setIncludeBoundary(checked === true)}
            />
            Boundary
          </label>
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
          <Button
            size="sm"
            disabled={selectedIds.size === 0 || !hasAnyCategorySelected}
            onClick={handleGenerate}
          >
            <Sparkles className="h-4 w-4" />
            Sinh Test Case
          </Button>
        </div>
      </div>
      {!hasAnyCategorySelected && (
        <p className="text-xs text-destructive">
          Phải tích ít nhất 1 trong Positive/Negative/Boundary/Security để có thứ sinh ra.
        </p>
      )}

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
                disabled={state?.status === "pending"}
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
                  Sinh lại sẽ thay test case AI đã sinh (trừ case đang khoá 🔒), giữ nguyên test case bạn tự thêm
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
