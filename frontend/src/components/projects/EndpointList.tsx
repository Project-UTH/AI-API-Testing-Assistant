import { useState } from "react"
import { Link } from "react-router-dom"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { ChevronDown, Coins, Loader2, Sparkles } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { cn, METHOD_STYLES } from "@/lib/utils"
import { ApiError } from "@/lib/api"
import { listEndpoints } from "@/lib/endpoints"
import { generateTestCases, type GenerateTestCasesResponse } from "@/lib/testcases"
import { GenerationPlanReport } from "@/components/projects/GenerationPlanReport"
import { getDashboardSummary } from "@/lib/dashboard"
import { coverageCounts, getProjectCoverage } from "@/lib/coverage"

interface EndpointListProps {
  projectId: string
}

type GenerationState =
  | { status: "pending" }
  // result chỉ có khi vừa sinh trong phiên này (Module 15h) - không có khi lấy testCaseCount từ server.
  | { status: "success"; count: number; result?: GenerateTestCasesResponse }
  | { status: "error"; message: string }

export function EndpointList({ projectId }: EndpointListProps) {
  const queryClient = useQueryClient()
  const { data, isLoading, isError } = useQuery({
    queryKey: ["endpoints", projectId],
    queryFn: () => listEndpoints(projectId),
  })

  // Cùng queryKey với Dashboard - dùng chung cache, không gọi thêm request.
  const { data: summary } = useQuery({
    queryKey: ["dashboard-summary"],
    queryFn: () => getDashboardSummary(),
  })

  // Coverage của từng endpoint, truyền xuống báo cáo kế hoạch sinh test case.
  const { data: coverage } = useQuery({
    queryKey: ["project-coverage", projectId],
    queryFn: () => getProjectCoverage(projectId),
  })
  const coverageByEndpointId = new Map((coverage?.endpoints ?? []).map((e) => [e.endpointId, e]))

  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  // Endpoint đang mở phần chi tiết (coverage + báo cáo lần sinh) - mặc định gập để danh sách gọn, 1 dòng/endpoint.
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set())
  const [generationState, setGenerationState] = useState<Record<string, GenerationState>>({})
  const [includeSecurity, setIncludeSecurity] = useState(false)
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

  function toggleExpanded(endpointId: string) {
    setExpandedIds((prev) => {
      const next = new Set(prev)
      if (next.has(endpointId)) {
        next.delete(endpointId)
      } else {
        next.add(endpointId)
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
        includePositive,
        includeNegative,
        includeBoundary,
      })
        .then((result) => {
          setGenerationState((prev) => ({
            ...prev,
            [endpointId]: { status: "success", count: result.testCases.length, result },
          }))
          // Đồng bộ lại testCaseCount thật từ server - để trạng thái "đã sinh" không bị mất
          // khi rời trang rồi quay lại (generationState chỉ sống trong phiên hiện tại)
          queryClient.invalidateQueries({ queryKey: ["endpoints", projectId] })
          // Báo cáo checklist đã lưu ở backend vừa đổi - bỏ bản cũ trong cache.
          queryClient.invalidateQueries({ queryKey: ["generation-plan", projectId, endpointId] })
          // Bộ test case đổi thì Coverage cũng đổi - trước đây không tự cập nhật.
          queryClient.invalidateQueries({ queryKey: ["project-coverage", projectId] })
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

  // "Chọn tất cả" chỉ xét endpoint chọn được (không đang sinh) - so với toàn bộ thì khi có endpoint đang sinh nhãn kẹt ở "Chọn tất cả".
  const selectableEndpoints = endpoints.filter((e) => generationState[e.id]?.status !== "pending")
  const allSelectableSelected = selectableEndpoints.length > 0 && selectableEndpoints.every((e) => selectedIds.has(e.id))
  const pendingCount = endpoints.filter((e) => generationState[e.id]?.status === "pending").length
  // Số endpoint đã tick mà đã có test case: sinh lại sẽ thay test case AI chưa khoá của chúng.
  const regenerateCount = endpoints.filter((e) => selectedIds.has(e.id) && e.testCaseCount > 0).length
  const todayTokens = summary?.aiTokensToday ?? 0
  const dailyLimit = summary?.aiDailyTokenLimit ?? 0
  const nearOrOverQuota = dailyLimit > 0 && todayTokens >= dailyLimit
  // Số token chỉ đáng nhìn khi đã dùng đáng kể (từ nửa hạn mức) - lúc còn thoải mái nó chỉ là 1 khối nhiễu ngay đầu danh sách.
  const showTokenUsage = dailyLimit > 0 && todayTokens >= dailyLimit / 2

  return (
    <div className="mt-4 flex flex-col gap-3">
      {summary && showTokenUsage && (
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
          Tick endpoint rồi chọn loại test case để sinh (Sinh Test Case dùng AI, tốn token).
        </span>
        <button
          type="button"
          className="text-xs text-primary underline underline-offset-4"
          onClick={() => setSelectedIds(allSelectableSelected ? new Set() : new Set(selectableEndpoints.map((e) => e.id)))}
        >
          {allSelectableSelected ? "Bỏ chọn tất cả" : "Chọn tất cả"}
        </button>
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
          const selected = selectedIds.has(endpoint.id)
          const isExpanded = expandedIds.has(endpoint.id)
          const coverageOfEndpoint = coverageByEndpointId.get(endpoint.id)
          const counts = coverageOfEndpoint ? coverageCounts(coverageOfEndpoint) : null
          return (
            <li key={endpoint.id} className="rounded-lg border border-border p-3">
              <div className="flex items-center gap-3">
                <Checkbox
                  aria-label={`Chọn ${endpoint.method} ${endpoint.path}`}
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
                <span className="min-w-0 truncate font-mono text-sm">{endpoint.path}</span>
                {endpoint.summary && (
                  <span className="min-w-0 flex-1 truncate text-sm text-muted-foreground" title={endpoint.summary}>
                    {endpoint.summary}
                  </span>
                )}
                {!endpoint.summary && <span className="flex-1" />}
                {endpoint.missingFromSpec && (
                  <span
                    className="shrink-0 rounded-full bg-destructive/10 px-2 py-0.5 text-xs font-medium text-destructive"
                    title="Lần import gần nhất không còn thấy endpoint này trong spec. Test case của nó vẫn được giữ - khi chạy có thể nhận 404."
                  >
                    Không còn trong spec
                  </span>
                )}
                {!endpoint.missingFromSpec && endpoint.schemaChangedAt && (
                  <span
                    className="shrink-0 rounded-full bg-amber-500/10 px-2 py-0.5 text-xs font-medium text-amber-700 dark:text-amber-400"
                    title="Spec của endpoint này đã đổi ở lần import gần nhất - test case có thể lỗi thời, nên xem lại hoặc chạy lại."
                  >
                    Spec đổi {new Date(endpoint.schemaChangedAt).toLocaleDateString("vi-VN", { day: "2-digit", month: "2-digit" })}
                  </span>
                )}
                {state?.status === "pending" && <Loader2 className="h-4 w-4 shrink-0 animate-spin text-muted-foreground" />}
                {state?.status === "error" && <span className="shrink-0 text-xs text-destructive">{state.message}</span>}
                <span className="shrink-0 text-xs text-muted-foreground">
                  {endpoint.testCaseCount > 0 ? (
                    <>
                      <span className="font-medium text-foreground">{endpoint.testCaseCount}</span> test
                      {counts && (
                        <>
                          {" · "}
                          <span className="font-medium text-foreground">
                            {counts.covered}/{counts.relevant}
                          </span>{" "}
                          mục
                          {counts.missing > 0 && (
                            <span className="text-amber-700 dark:text-amber-400"> · thiếu {counts.missing}</span>
                          )}
                        </>
                      )}
                    </>
                  ) : (
                    "Chưa có test case"
                  )}
                </span>
                {endpoint.testCaseCount > 0 && (
                  <>
                    <Link
                      to={`/projects/${projectId}/test-cases?endpointId=${endpoint.id}`}
                      className="shrink-0 text-xs text-primary underline underline-offset-4"
                    >
                      Xem test case
                    </Link>
                    <button
                      type="button"
                      aria-label={`${isExpanded ? "Thu gọn" : "Mở"} chi tiết ${endpoint.method} ${endpoint.path}`}
                      aria-expanded={isExpanded}
                      className="shrink-0 rounded-md p-1 text-muted-foreground hover:bg-muted hover:text-foreground"
                      onClick={() => toggleExpanded(endpoint.id)}
                    >
                      <ChevronDown className={cn("h-4 w-4 transition-transform", isExpanded && "rotate-180")} />
                    </button>
                  </>
                )}
              </div>
              {isExpanded && state?.status === "success" && state.count > 0 && (
                <GenerationPlanReport
                  projectId={projectId}
                  endpointId={endpoint.id}
                  freshPlan={state.result?.planSummary ?? undefined}
                  coverage={coverageByEndpointId.get(endpoint.id)}
                />
              )}
            </li>
          )
        })}
      </ul>

      {(selectedIds.size > 0 || pendingCount > 0) && (
        <div className="sticky bottom-4 z-20 flex flex-col gap-2 rounded-lg border border-border bg-card p-3 shadow-lg">
          {pendingCount > 0 && (
            <p className="flex items-center gap-2 text-sm text-muted-foreground" role="status">
              <Loader2 className="h-4 w-4 animate-spin" />
              Đang sinh test case cho {pendingCount} endpoint, có thể mất vài chục giây...
            </p>
          )}
          {selectedIds.size > 0 && (
          <div className="flex flex-wrap items-center justify-between gap-3">
            <span className="text-sm font-medium">Đã chọn {selectedIds.size} endpoint</span>
            <div className="flex flex-wrap items-center gap-3">
              <label className="flex items-center gap-1.5 text-sm text-muted-foreground">
                <Checkbox checked={includePositive} onCheckedChange={(checked) => setIncludePositive(checked === true)} />
                Positive
              </label>
              <label className="flex items-center gap-1.5 text-sm text-muted-foreground">
                <Checkbox checked={includeNegative} onCheckedChange={(checked) => setIncludeNegative(checked === true)} />
                Negative
              </label>
              <label className="flex items-center gap-1.5 text-sm text-muted-foreground">
                <Checkbox checked={includeBoundary} onCheckedChange={(checked) => setIncludeBoundary(checked === true)} />
                Boundary
              </label>
              <label
                className="flex items-center gap-1.5 text-sm text-muted-foreground"
                title="Test kiểm tra API chặn truy cập khi bỏ hoặc gửi sai token"
              >
                <Checkbox checked={includeSecurity} onCheckedChange={(checked) => setIncludeSecurity(checked === true)} />
                + Security
              </label>
              <Button size="sm" disabled={!hasAnyCategorySelected} onClick={handleGenerate}>
                <Sparkles className="h-4 w-4" />
                Sinh Test Case
              </Button>
            </div>
          </div>
          )}
          {selectedIds.size > 0 && regenerateCount > 0 && (
            <p className="text-xs text-amber-700 dark:text-amber-400">
              {regenerateCount} endpoint đã có test case: sinh lại sẽ thay các test case AI chưa khoá 🔒 của chúng, kèm kết quả
              chạy và bug report của các test case đó. Test case bạn tự thêm và test case đã khoá được giữ nguyên.
            </p>
          )}
          {selectedIds.size > 0 && !hasAnyCategorySelected && (
            <p className="text-xs text-destructive">
              Phải tích ít nhất 1 trong Positive/Negative/Boundary/Security để có thứ sinh ra.
            </p>
          )}
        </div>
      )}
    </div>
  )
}
