import { useEffect, useMemo, useState } from "react"
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { ArrowLeft, ArrowRight } from "lucide-react"

import { Button } from "@/components/ui/button"
import { StatusBadge } from "@/components/shared/StatusBadge"
import { cn, METHOD_STYLES } from "@/lib/utils"
import {
  compareExecutions,
  listExecutions,
  type ExecutionCompare,
  type ExecutionDiffCategory,
  type ExecutionDiffItem,
  type ExecutionSummary,
} from "@/lib/executionCompare"

const CATEGORY_LABEL: Record<ExecutionDiffCategory, string> = {
  REGRESSED: "Hồi quy",
  FIXED: "Đã sửa",
  STILL_FAILING: "Vẫn Fail",
  CHANGED_TEST: "Test đã sửa",
  UNRELIABLE: "Không ổn định",
  INCONCLUSIVE: "Không kết luận",
  ONLY_IN_TARGET: "Chỉ ở lượt sau",
  ONLY_IN_BASE: "Chỉ ở lượt trước",
  STILL_PASSING: "Vẫn Pass",
}

const CATEGORY_HINT: Record<ExecutionDiffCategory, string> = {
  REGRESSED: "Pass ở lượt trước, Fail ở lượt sau do API (không phải môi trường/token) - cần xem trước tiên.",
  FIXED: "Fail ở lượt trước, Pass ở lượt sau.",
  STILL_FAILING: "Fail ở cả hai lượt.",
  CHANGED_TEST: "Test case đã được sửa giữa hai lượt nên kết quả không so sánh trực tiếp được.",
  UNRELIABLE: "Test không ổn định (kết quả đổi qua lại dù nội dung không đổi) - không kết luận hồi quy/được sửa.",
  INCONCLUSIVE: "Một trong hai lượt bị chặn/lỗi kết nối hoặc Fail do môi trường/token/cấu hình test - chưa biết API đúng hay sai.",
  ONLY_IN_TARGET: "Test case mới, chỉ có ở lượt sau.",
  ONLY_IN_BASE: "Test case chỉ có ở lượt trước (đã xoá hoặc không chạy ở lượt sau).",
  STILL_PASSING: "Pass ở cả hai lượt.",
}

const CATEGORY_TONE: Record<ExecutionDiffCategory, string> = {
  REGRESSED: "border-destructive/40 bg-destructive/10 text-destructive",
  FIXED: "border-green-500/40 bg-green-500/10 text-green-600 dark:text-green-400",
  STILL_FAILING: "border-orange-500/40 bg-orange-500/10 text-orange-600 dark:text-orange-400",
  CHANGED_TEST: "border-violet-500/40 bg-violet-500/10 text-violet-600 dark:text-violet-400",
  UNRELIABLE: "border-amber-500/40 bg-amber-500/10 text-amber-700 dark:text-amber-400",
  INCONCLUSIVE: "border-border bg-muted text-muted-foreground",
  ONLY_IN_TARGET: "border-sky-500/40 bg-sky-500/10 text-sky-600 dark:text-sky-400",
  ONLY_IN_BASE: "border-border bg-muted text-muted-foreground",
  STILL_PASSING: "border-border bg-muted text-muted-foreground",
}

const CAUSE_LABEL: Record<string, string> = {
  TEST_SETUP: "Lỗi cấu hình test",
  ENVIRONMENT: "Nghi lỗi môi trường",
  AUTH_CONFIG: "Nghi token/xác thực",
  FLAKY: "Không ổn định",
  ASSERTION_FAILED: "Assertion sai",
  DATA_STATE: "Nghi dữ liệu/trạng thái đổi",
  STABLE_FAILURE: "Lỗi ổn định",
  RESOURCE_NOT_FOUND: "Có thể id không tồn tại",
  EXPECTATION_MISMATCH: "Kỳ vọng có thể quá chặt",
  UNDETERMINED: "Chưa đủ dữ liệu",
  STATE_CONFLICT: "Xung đột trạng thái dữ liệu",
  AUTHZ_VIOLATION: "Sai lệch phân quyền",
  TEST_DATA_SUSPECT: "Nghi dữ liệu test không hợp lệ",
  MASS_ASSIGNMENT: "Nhận field không khai báo (mass assignment)",
  HTTP_ROBUSTNESS: "Xử lý sai request lỗi định dạng (JSON hỏng / sai Content-Type)",
  TOKEN_FORBIDDEN: "Nghi token thiếu quyền cho endpoint này",
  API_WRONG_STATUS: "API trả sai mã lỗi (401 thay vì 400)",
  RULE_VIOLATION_ACCEPTED: "API nhận dữ liệu vi phạm checklist nghiệp vụ",
}

const SHOWN_ORDER: ExecutionDiffCategory[] = [
  "REGRESSED", "FIXED", "STILL_FAILING", "CHANGED_TEST", "UNRELIABLE", "INCONCLUSIVE", "ONLY_IN_TARGET", "ONLY_IN_BASE", "STILL_PASSING",
]

function formatTime(iso: string) {
  return new Date(iso).toLocaleString("vi-VN", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" })
}

function runLabel(run: ExecutionSummary) {
  return `${formatTime(run.startedAt)} · ${run.passedCount}/${run.totalCount} Pass${run.failedCount > 0 ? ` · ${run.failedCount} Fail` : ""}`
}

/**
 * Module 17 (17c) - so sánh 2 lượt chạy: nhóm HỒI QUY nổi bật trước, các nhóm còn lại chọn bằng chip. Chỉ hồi quy khi test không đổi,
 * đủ tin cậy và Fail đến từ API (không phải môi trường/token) - xem ExecutionDiffClassifier ở backend.
 */
export function ExecutionComparePage() {
  const { id, executionId } = useParams<{ id: string; executionId: string }>()
  const projectId = id!
  const targetId = executionId!
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const againstParam = searchParams.get("against")
  const [category, setCategory] = useState<ExecutionDiffCategory | "AUTO" | "ALL">("AUTO")
  const [page, setPage] = useState(0)

  const runsQuery = useQuery({ queryKey: ["executions-list", projectId], queryFn: () => listExecutions(projectId) })
  const finishedRuns = useMemo(
    () => (runsQuery.data ?? []).filter((r) => r.status === "COMPLETED" || r.status === "FAILED"),
    [runsQuery.data]
  )
  // Mặc định: lượt chạy liền trước lượt đang xem (danh sách sắp mới nhất trước).
  const defaultBaseId = useMemo(() => {
    const index = finishedRuns.findIndex((r) => r.id === targetId)
    return index >= 0 ? finishedRuns[index + 1]?.id : undefined
  }, [finishedRuns, targetId])
  const baseId = againstParam ?? defaultBaseId

  useEffect(() => setPage(0), [baseId, category])

  // category "AUTO": lấy trang đầu mặc định (mọi nhóm trừ Vẫn Pass, hồi quy nằm đầu) rồi tự chọn nhóm có số lượng.
  const effectiveCategory = category === "AUTO" || category === "ALL" ? null : category
  const compareQuery = useQuery({
    queryKey: ["execution-compare", projectId, baseId, targetId, effectiveCategory, page],
    queryFn: () => compareExecutions(projectId, baseId!, targetId, effectiveCategory, page),
    enabled: Boolean(baseId),
  })
  const data = compareQuery.data

  // Tự chọn nhóm đầu tiên có dữ liệu (hồi quy nếu có) một lần khi dữ liệu về lần đầu.
  useEffect(() => {
    if (category === "AUTO" && data) {
      const firstNonEmpty = SHOWN_ORDER.find((c) => c !== "STILL_PASSING" && countOf(data.summary, c) > 0)
      setCategory(firstNonEmpty ?? "ALL")
    }
  }, [category, data])

  const base = finishedRuns.find((r) => r.id === baseId)
  const backHref = `/projects/${projectId}/executions/${targetId}`

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <Link to={backHref} className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
            <ArrowLeft className="h-4 w-4" /> Kết quả lượt chạy
          </Link>
          <h2 className="mt-1 text-lg font-semibold">So sánh 2 lượt chạy</h2>
        </div>
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <label htmlFor="compare-base" className="text-muted-foreground">Lượt trước:</label>
          <select
            id="compare-base"
            className="h-8 rounded-lg border border-input bg-transparent px-2 text-sm outline-none dark:bg-input/30"
            value={baseId ?? ""}
            onChange={(e) => setSearchParams({ against: e.target.value })}
          >
            {!baseId && <option value="">Chọn lượt…</option>}
            {finishedRuns.filter((r) => r.id !== targetId).map((r) => (
              <option key={r.id} value={r.id}>{runLabel(r)}</option>
            ))}
          </select>
          <ArrowRight className="h-4 w-4 text-muted-foreground" aria-hidden />
          <span className="rounded-md bg-muted px-2 py-1">
            Lượt này: {finishedRuns.find((r) => r.id === targetId) ? runLabel(finishedRuns.find((r) => r.id === targetId)!) : "…"}
          </span>
        </div>
      </div>

      {runsQuery.isSuccess && !baseId && (
        <div className="rounded-lg border border-dashed border-border p-6 text-center text-sm text-muted-foreground">
          {finishedRuns.length < 2 ? "Project mới có 1 lượt chạy - cần ít nhất 2 lượt đã xong để so sánh." : "Chọn lượt chạy trước để so sánh."}
          <div className="mt-3">
            <Button variant="outline" size="sm" onClick={() => navigate(backHref)}>Quay lại kết quả</Button>
          </div>
        </div>
      )}

      {compareQuery.isError && <p className="text-sm text-destructive">Không so sánh được, vui lòng thử lại.</p>}
      {compareQuery.isPending && baseId && <p className="text-sm text-muted-foreground">Đang so sánh...</p>}

      {data && (
        <>
          {data.summary.environmentChanged && (
            <p className="rounded-md bg-amber-500/10 p-2 text-sm text-amber-700 dark:text-amber-400" role="status">
              Target Base URL hoặc token của hệ thống đích đã đổi giữa hai lượt - nhiều thay đổi có thể do môi trường chứ không phải do API.
            </p>
          )}

          <div className="flex flex-wrap gap-2" role="tablist" aria-label="Nhóm kết quả">
            {SHOWN_ORDER.map((c) => {
              const count = countOf(data.summary, c)
              const active = category === c
              return (
                <button
                  key={c}
                  type="button"
                  role="tab"
                  aria-selected={active}
                  disabled={count === 0}
                  title={CATEGORY_HINT[c]}
                  onClick={() => setCategory(c)}
                  className={cn(
                    "rounded-full border px-3 py-1 text-xs font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50",
                    active ? CATEGORY_TONE[c] + " ring-2 ring-ring/40" : "border-border bg-background text-muted-foreground hover:bg-muted"
                  )}
                >
                  {CATEGORY_LABEL[c]} <span className="font-semibold">{count}</span>
                </button>
              )
            })}
            <button
              type="button"
              role="tab"
              aria-selected={category === "ALL"}
              onClick={() => setCategory("ALL")}
              className={cn("rounded-full border px-3 py-1 text-xs font-medium", category === "ALL" ? "border-primary bg-primary text-primary-foreground" : "border-border text-muted-foreground hover:bg-muted")}
            >
              Tất cả thay đổi
            </button>
          </div>

          {effectiveCategory && <p className="text-xs text-muted-foreground">{CATEGORY_HINT[effectiveCategory]}</p>}

          {data.items.data.length === 0 ? (
            <p className="rounded-lg border border-dashed border-border p-6 text-center text-sm text-muted-foreground">
              {base ? "Không có dòng nào ở nhóm này." : "Không có thay đổi nào giữa hai lượt."}
            </p>
          ) : (
            <ul className="flex flex-col divide-y divide-border rounded-lg border border-border">
              {data.items.data.map((item) => (
                <DiffRow key={item.testCaseId} item={item} />
              ))}
            </ul>
          )}

          {data.items.totalPages > 1 && (
            <div className="flex items-center justify-center gap-3 text-sm">
              <Button size="sm" variant="outline" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Trước</Button>
              <span className="text-muted-foreground">Trang {data.items.page + 1}/{data.items.totalPages} · {data.items.totalElements} dòng</span>
              <Button size="sm" variant="outline" disabled={page + 1 >= data.items.totalPages} onClick={() => setPage((p) => p + 1)}>Sau</Button>
            </div>
          )}
        </>
      )}
    </div>
  )
}

function countOf(summary: ExecutionCompare["summary"], c: ExecutionDiffCategory): number {
  switch (c) {
    case "REGRESSED": return summary.regressed
    case "FIXED": return summary.fixed
    case "STILL_FAILING": return summary.stillFailing
    case "CHANGED_TEST": return summary.changedTest
    case "UNRELIABLE": return summary.unreliable
    case "INCONCLUSIVE": return summary.inconclusive
    case "ONLY_IN_TARGET": return summary.onlyInTarget
    case "ONLY_IN_BASE": return summary.onlyInBase
    case "STILL_PASSING": return summary.stillPassing
  }
}

function DiffRow({ item }: { item: ExecutionDiffItem }) {
  return (
    <li className="flex flex-col gap-1 px-3 py-2 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <span className={cn("w-16 shrink-0 rounded-md px-2 py-0.5 text-center text-xs font-semibold", METHOD_STYLES[item.endpointMethod] ?? "bg-muted text-muted-foreground")}>
          {item.endpointMethod}
        </span>
        <span className="min-w-0 truncate font-mono text-xs" title={item.endpointPath}>{item.endpointPath}</span>
        <span className="min-w-0 flex-1 truncate" title={item.testCaseName}>{item.testCaseName}</span>
        <span className="flex shrink-0 items-center gap-1.5">
          {item.baseStatus ? <StatusBadge status={item.baseStatus} compact /> : <span className="text-xs text-muted-foreground">—</span>}
          <ArrowRight className="h-3.5 w-3.5 text-muted-foreground" aria-hidden />
          {item.targetStatus ? <StatusBadge status={item.targetStatus} compact /> : <span className="text-xs text-muted-foreground">—</span>}
        </span>
      </div>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-0.5 pl-[4.5rem] text-xs text-muted-foreground">
        {(item.baseResponseStatus !== null || item.targetResponseStatus !== null) && (
          <span>Mã trả về: {item.baseResponseStatus ?? "—"} → {item.targetResponseStatus ?? "—"}</span>
        )}
        {item.baseFailureCause && <span>Trước: {CAUSE_LABEL[item.baseFailureCause] ?? item.baseFailureCause}</span>}
        {item.targetFailureCause && <span>Sau: {CAUSE_LABEL[item.targetFailureCause] ?? item.targetFailureCause}</span>}
        {item.note && <span>{item.note}</span>}
      </div>
    </li>
  )
}
