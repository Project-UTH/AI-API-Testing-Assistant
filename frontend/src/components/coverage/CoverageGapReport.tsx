import { useState } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { CheckCircle2, CircleAlert, Loader2, MinusCircle, Wand2, X } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { StatusBadge } from "@/components/shared/StatusBadge"
import { cn } from "@/lib/utils"
import {
  coverageCounts,
  fillCoverageGaps,
  type CoverageDimension,
  type CoverageFillResult,
  type CoverageRunStatus,
  type EndpointCoverage,
} from "@/lib/coverage"

/** Thứ tự nhóm = thứ tự đọc tự nhiên: 4 loại checklist trước, rồi mã trạng thái, quan hệ endpoint, phân quyền. */
const GROUPS = [
  { category: "POSITIVE", title: "Positive", rule: "1 bộ dữ liệu hợp lệ đầy đủ · mỗi giá trị enum hợp lệ 1 case." },
  {
    category: "NEGATIVE",
    title: "Negative",
    rule: "Thiếu từng field/tham số bắt buộc · null ở field bắt buộc · sai kiểu dữ liệu · giá trị ngoài enum.",
  },
  {
    category: "BOUNDARY",
    title: "Boundary",
    rule: "Biên khai rõ trong spec (đúng biên + ngay ngoài biên) · giới hạn int32/int64 · biên ngầm (0, số âm, chuỗi rỗng) · id không tồn tại.",
  },
  { category: "SECURITY", title: "Security", rule: "Gọi thiếu token · gọi với token sai." },
  { category: "STATUS_CODE", title: "Mã trạng thái spec khai báo", rule: "Mỗi mã cần ít nhất 1 test case kỳ vọng đúng mã đó." },
  {
    category: "RELATION",
    title: "Quan hệ giữa các endpoint",
    rule: "Endpoint dùng id do 1 POST khác tạo ra cần ít nhất 1 test lấy id thật từ response (Test Data Chaining).",
  },
  { category: "AUTH", title: "Phân quyền", rule: "" },
] as const

/** Biên ngầm kỳ vọng thành công (spec không cấm) - API trả 400 nghĩa là spec khai thiếu ràng buộc hoặc API có lỗi. */
const IMPLICIT_TYPES = new Set(["BOUNDARY_ZERO", "BOUNDARY_NEGATIVE_NUMBER", "BOUNDARY_EMPTY_STRING"])

const RUN_STATUS_DISPLAY: Record<CoverageRunStatus, { label: string; className: string; title: string }> = {
  PASSED: {
    label: "Đã chạy · Pass",
    className: "bg-green-500/10 text-green-600 dark:text-green-400",
    title: "Lần chạy mới nhất của test case phủ mục này cho kết quả đúng kỳ vọng.",
  },
  FAILED: {
    label: "Đã chạy · Fail",
    className: "bg-destructive/10 text-destructive",
    title: "Đã kiểm và API trả lệch kỳ vọng - mục vẫn tính là đã kiểm, nhưng cần xem lại kết quả.",
  },
  NO_VERDICT: {
    label: "Chạy chưa ra kết quả",
    className: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
    title: "Lần chạy mới nhất bị lỗi kết nối, bị chặn bởi chuỗi phụ thuộc, hoặc Fail do nguyên nhân không phải API (token hết hạn, môi trường) - chưa biết API đúng hay sai. Xem nguyên nhân ở trang Kết quả thực thi.",
  },
  NOT_RUN: {
    label: "Chưa chạy",
    className: "bg-muted text-muted-foreground",
    title: "Đã có test case nhưng chưa chạy lần nào - mới là thiết kế, chưa kiểm thật.",
  },
}

interface CoverageGapReportProps {
  projectId: string
  endpoint: EndpointCoverage
}

/**
 * Module 15j - Coverage Gap Analysis của 1 endpoint: không chỉ "%", mà là danh sách mục cần kiểm (tự tính
 * từ OpenAPI spec, cùng checklist EP/BVA lúc sinh test case), mục nào đã có test và đã chạy, mục nào còn
 * thiếu kèm vì sao cần kiểm + bằng chứng case gần đạt, và nút bổ sung (hệ thống tự tạo, 0 token).
 */
export function CoverageGapReport({ projectId, endpoint }: CoverageGapReportProps) {
  const queryClient = useQueryClient()
  const [showCovered, setShowCovered] = useState(false)
  const [fillResult, setFillResult] = useState<CoverageFillResult | null>(null)
  const counts = coverageCounts(endpoint)

  const mutation = useMutation({
    mutationFn: (itemKeys: string[]) => fillCoverageGaps(projectId, endpoint.endpointId, itemKeys),
    onSuccess: (result) => {
      setFillResult(result)
      queryClient.invalidateQueries({ queryKey: ["project-coverage", projectId] })
      queryClient.invalidateQueries({ queryKey: ["test-cases", projectId] })
      queryClient.invalidateQueries({ queryKey: ["endpoints", projectId] })
    },
  })

  /** Dòng đang được bù: chỉ các khoá nằm trong lượt đang chạy (bấm 1 dòng hoặc bấm bù tất cả). */
  function isFilling(itemKey: string | null) {
    return mutation.isPending && itemKey !== null && (mutation.variables?.includes(itemKey) ?? false)
  }

  return (
    <div className="flex flex-col gap-3 text-sm">
      <div>
        <p>
          Cần kiểm <span className="font-semibold">{counts.relevant}</span> mục · đã có test{" "}
          <span className="font-semibold">{counts.covered}</span> · đã chạy ra kết quả{" "}
          <span className="font-semibold">{counts.executed}</span> ·{" "}
          <span className={cn("font-semibold", counts.missing > 0 && "text-amber-600 dark:text-amber-400")}>
            còn thiếu {counts.missing}
          </span>
          {counts.failing > 0 && (
            <span className="text-destructive"> · {counts.failing} mục đã chạy nhưng API lệch kỳ vọng</span>
          )}
        </p>
        <p className="mt-1 text-xs text-muted-foreground">
          Danh sách mục cần kiểm do hệ thống tự tính từ đặc tả OpenAPI theo kỹ thuật EP/BVA - cùng checklist dùng khi sinh
          test case. Một test case chỉ được tính cho 1 mục khi hệ thống đọc nội dung thật thấy đúng tình huống đó và chỉ vi
          phạm đúng 1 ràng buộc (không dựa vào tên case hay lời AI tự khai).
        </p>
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <Button
          size="sm"
          disabled={counts.fillableKeys.length === 0 || mutation.isPending}
          onClick={() => mutation.mutate(counts.fillableKeys)}
          title={
            counts.fillableKeys.length === 0
              ? "Chưa có mục nào bù tự động được - xem lý do bên dưới."
              : "Hệ thống tạo test case cho các mục còn thiếu mà bù được ngay: mượn dữ liệu từ 1 test case hợp lệ đang có (hoặc dựng từ đặc tả nếu endpoint không có đầu vào nào khác) rồi sửa đúng 1 chỗ. Không gọi AI, không tốn token."
          }
        >
          {mutation.isPending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Wand2 className="h-3.5 w-3.5" />}
          Bổ sung {counts.fillableKeys.length} mục bù được ngay
        </Button>
        <span className="text-xs text-muted-foreground">Hệ thống tự tạo · 0 token</span>
        <label className="ml-auto flex items-center gap-1.5 text-xs text-muted-foreground">
          <Checkbox checked={showCovered} onCheckedChange={(checked) => setShowCovered(checked === true)} />
          Hiện cả mục đã đạt / không áp dụng
        </label>
      </div>

      {counts.blockedCount > 0 && (
        <div className="rounded-md border border-amber-500/30 bg-amber-500/5 p-2.5 text-xs">
          <p className="font-medium text-amber-600 dark:text-amber-400">
            {counts.blockedCount} mục chưa bổ sung tự động được
            {counts.fillableKeys.length === 0 && " (nút Bổ sung đã được làm mờ vì chưa có mục nào bù được)"}
          </p>
          <ul className="mt-1 flex flex-col gap-1 text-muted-foreground">
            {counts.blockedReasons.map(({ reason, count }) => (
              <li key={reason}>
                • <span className="font-medium text-foreground">{count} mục:</span> {reason}
              </li>
            ))}
          </ul>
        </div>
      )}

      {mutation.isError && (
        <p className="rounded-md bg-destructive/10 p-2 text-xs text-destructive">
          {mutation.error instanceof Error ? mutation.error.message : "Không bổ sung được, vui lòng thử lại."}
        </p>
      )}
      {fillResult && <FillResultBox result={fillResult} onClose={() => setFillResult(null)} />}

      {GROUPS.map((group) => {
        const rows = endpoint.dimensions.filter((d) => d.category === group.category)
        if (rows.length === 0) return null
        return (
          <GroupSection
            key={group.category}
            title={group.title}
            rule={group.rule}
            rows={rows}
            showCovered={showCovered}
            isFilling={isFilling}
            onFill={(itemKey) => mutation.mutate([itemKey])}
            fillDisabled={mutation.isPending}
          />
        )
      })}
    </div>
  )
}

interface GroupSectionProps {
  title: string
  rule: string
  rows: CoverageDimension[]
  showCovered: boolean
  isFilling: (itemKey: string | null) => boolean
  onFill: (itemKey: string) => void
  fillDisabled: boolean
}

function GroupSection({ title, rule, rows, showCovered, isFilling, onFill, fillDisabled }: GroupSectionProps) {
  const relevant = rows.filter((d) => d.status !== "NOT_APPLICABLE")
  const covered = relevant.filter((d) => d.status === "COVERED")
  const visible = showCovered ? rows : rows.filter((d) => d.status === "MISSING")

  return (
    <section className="rounded-md border border-border px-3 py-2">
      <div className="flex flex-wrap items-baseline gap-x-2">
        <h4 className="font-semibold">{title}</h4>
        {relevant.length > 0 ? (
          <span
            className={cn(
              "text-xs font-medium",
              covered.length === relevant.length ? "text-green-600 dark:text-green-400" : "text-amber-600 dark:text-amber-400"
            )}
          >
            đạt {covered.length}/{relevant.length}
          </span>
        ) : (
          <span className="text-xs text-muted-foreground">không áp dụng</span>
        )}
      </div>
      {rule && <p className="text-xs text-muted-foreground">{rule}</p>}

      {visible.length === 0 ? (
        <p className="mt-1.5 text-xs text-muted-foreground">
          {relevant.length === 0
            ? rows[0]?.detail
            : `✓ Đủ cả ${relevant.length} mục - tick "Hiện cả mục đã đạt" để xem test case nào phủ từng mục.`}
        </p>
      ) : (
        <ul className="mt-2 flex flex-col gap-2.5">
          {visible.map((row, index) => (
            <GapRow
              key={row.itemKey ?? `${row.category}-${row.label}-${index}`}
              row={row}
              filling={isFilling(row.itemKey)}
              onFill={onFill}
              fillDisabled={fillDisabled}
            />
          ))}
        </ul>
      )}
    </section>
  )
}

interface GapRowProps {
  row: CoverageDimension
  filling: boolean
  onFill: (itemKey: string) => void
  fillDisabled: boolean
}

function GapRow({ row, filling, onFill, fillDisabled }: GapRowProps) {
  const implicit = row.checklistType !== null && IMPLICIT_TYPES.has(row.checklistType) && row.label.includes("thành công")
  const StatusIcon = row.status === "COVERED" ? CheckCircle2 : row.status === "MISSING" ? CircleAlert : MinusCircle

  return (
    <li className="text-xs">
      <div className="flex items-start gap-2">
        <StatusIcon
          className={cn(
            "mt-0.5 h-4 w-4 shrink-0",
            row.status === "COVERED" && "text-green-600 dark:text-green-400",
            row.status === "MISSING" && "text-amber-600 dark:text-amber-400",
            row.status === "NOT_APPLICABLE" && "text-muted-foreground"
          )}
        />
        <span className="min-w-0 flex-1 text-sm">
          {row.label}
          {implicit && (
            <span
              className="ml-1.5 rounded-full bg-amber-500/10 px-1.5 py-0.5 text-xs font-medium text-amber-600 dark:text-amber-400"
              title="Spec không khai ràng buộc nên giá trị này không bị cấm - kỳ vọng thành công. Nếu API trả 400 thì spec đang khai thiếu ràng buộc hoặc API có lỗi."
            >
              ⚠ biên ngầm
            </span>
          )}
        </span>
        {row.status === "COVERED" && row.runStatus && (
          <span
            className={cn("shrink-0 rounded-full px-2 py-0.5 font-medium", RUN_STATUS_DISPLAY[row.runStatus].className)}
            title={RUN_STATUS_DISPLAY[row.runStatus].title}
          >
            {RUN_STATUS_DISPLAY[row.runStatus].label}
          </span>
        )}
        {row.status === "MISSING" && row.itemKey && (
          <Button
            size="xs"
            variant="outline"
            disabled={fillDisabled || !row.fillable}
            onClick={() => onFill(row.itemKey as string)}
            title={
              row.fillable
                ? "Hệ thống tự tạo 1 test case cho đúng mục này (0 token)"
                : (row.fillBlockedReason ?? "Hệ thống chưa bù tự động được mục này")
            }
          >
            {filling ? <Loader2 className="animate-spin" /> : <Wand2 />}
            Bổ sung
          </Button>
        )}
      </div>

      <div className="mt-1 flex flex-col gap-1 pl-6 text-muted-foreground">
        {row.status === "COVERED" && row.coveringTestCases.length > 0 && (
          <div className="flex flex-wrap items-center gap-1.5">
            <span>Được kiểm bởi:</span>
            {row.coveringTestCases.map((tc) => (
              <span key={tc.id} className="inline-flex items-center gap-1 rounded-md bg-muted/60 px-1.5 py-0.5 text-foreground">
                {tc.name}
                {tc.latestResult ? (
                  <StatusBadge status={tc.latestResult} compact />
                ) : (
                  <span className="text-[11px] text-muted-foreground">(chưa chạy)</span>
                )}
              </span>
            ))}
          </div>
        )}
        {row.status === "MISSING" && (
          <>
            {row.whyNeeded && (
              <p>
                <span className="font-medium text-foreground">Vì sao cần: </span>
                {row.whyNeeded}
              </p>
            )}
            {row.nearMisses.length > 0 ? (
              <div>
                <p className="font-medium text-amber-600 dark:text-amber-400">{row.detail}</p>
                <ul className="mt-0.5 flex flex-col gap-0.5">
                  {row.nearMisses.map((nm) => (
                    <li key={nm.testCaseId}>
                      • <span className="text-foreground">'{nm.testCaseName}'</span>: {nm.reason}
                    </li>
                  ))}
                </ul>
              </div>
            ) : (
              row.detail && <p>{row.detail}</p>
            )}
          </>
        )}
        {row.status === "NOT_APPLICABLE" && row.detail && <p>{row.detail}</p>}
      </div>
    </li>
  )
}

function FillResultBox({ result, onClose }: { result: CoverageFillResult; onClose: () => void }) {
  return (
    <div className="relative rounded-md border border-border bg-muted/30 p-3 text-xs">
      <button
        type="button"
        onClick={onClose}
        aria-label="Đóng"
        className="absolute top-2 right-2 rounded p-0.5 text-muted-foreground hover:bg-muted"
      >
        <X className="h-3.5 w-3.5" />
      </button>
      {result.createdCount > 0 ? (
        <>
          <p className="text-sm font-medium text-green-600 dark:text-green-400">
            Đã bổ sung {result.createdCount} test case
            {result.baselineTestCaseName && <> từ case gốc '{result.baselineTestCaseName}'</>}
            {result.fromSpecCount > 0 && (
              <>
                {" "}
                · {result.fromSpecCount} case dựng thẳng từ đặc tả (endpoint không có đầu vào nào khác nên không cần case gốc)
              </>
            )}
          </p>
          <ul className="mt-1 pl-3 text-muted-foreground">
            {result.created.map((tc) => (
              <li key={tc.id}>• {tc.name}</li>
            ))}
          </ul>
          <p className="mt-1.5 text-muted-foreground">
            Case mới chưa chạy - vào "Chạy test" để kiểm thật. Case do hệ thống quản lý: chưa khoá 🔒 thì lần "Sinh Test
            Case" sau sẽ thay thế.
          </p>
        </>
      ) : (
        result.notFilled.length === 0 && <p className="text-muted-foreground">Không còn mục nào cần bổ sung.</p>
      )}
      {result.notFilled.length > 0 && (
        <div className="mt-2">
          <p className="font-medium text-amber-600 dark:text-amber-400">
            {result.notFilled.length} mục không bổ sung được:
          </p>
          <ul className="mt-0.5 flex flex-col gap-0.5 pl-3 text-muted-foreground">
            {result.notFilled.map((nf) => (
              <li key={nf.itemKey}>
                • {nf.label} — {nf.reason}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
