import { useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { ChevronDown, ChevronRight, Loader2 } from "lucide-react"

import { cn } from "@/lib/utils"
import {
  getLatestGenerationPlan,
  type ChecklistItemResponse,
  type ChecklistItemStatus,
  type GenerationPlanSummary,
} from "@/lib/testcases"

const STATUS_DISPLAY: Record<ChecklistItemStatus, { label: string; className: string }> = {
  AI_SATISFIED: { label: "AI làm đạt", className: "bg-sky-500/10 text-sky-600 dark:text-sky-400" },
  SYSTEM_FILLED: { label: "Hệ thống bù", className: "bg-teal-500/10 text-teal-600 dark:text-teal-400" },
  ALREADY_COVERED: { label: "Đã có sẵn", className: "bg-slate-500/10 text-slate-600 dark:text-slate-400" },
  NOT_FILLABLE: { label: "Chưa có case", className: "bg-destructive/10 text-destructive" },
  NOT_APPLICABLE: { label: "Không áp dụng", className: "bg-muted text-muted-foreground" },
}

/** Module 15i - checklist chuẩn riêng cho từng loại; loại xác định bằng tiền tố của ChecklistItemType. */
const CATEGORIES = [
  {
    prefix: "POSITIVE_",
    title: "Checklist Positive",
    rule: "1 bộ dữ liệu hợp lệ đầy đủ mọi field bắt buộc · mỗi giá trị enum hợp lệ 1 case.",
  },
  {
    prefix: "NEGATIVE_",
    title: "Checklist Negative",
    rule:
      "Thiếu từng field/tham số bắt buộc · null ở field bắt buộc · sai kiểu dữ liệu · giá trị ngoài enum.",
  },
  {
    prefix: "BOUNDARY_",
    title: "Checklist Boundary",
    rule:
      "Biên khai rõ trong spec (đúng biên + ngay ngoài biên, kể cả exclusive) · giới hạn int32/int64 hai đầu · biên ngầm khi spec không khai ràng buộc (số 0, số âm, chuỗi rỗng) · id không tồn tại ở tham số path (kỳ vọng 4xx).",
  },
  {
    prefix: "SECURITY_",
    title: "Checklist Security",
    rule: "Gọi thiếu token · gọi với token sai.",
  },
] as const

/** Biên ngầm: spec KHÔNG khai ràng buộc nên kỳ vọng 2xx - API trả 400 nghĩa là spec khai thiếu hoặc API có lỗi. */
const IMPLICIT_TYPES = new Set(["BOUNDARY_ZERO", "BOUNDARY_NEGATIVE_NUMBER", "BOUNDARY_EMPTY_STRING"])

const CIRCLED = ["①", "②", "③", "④", "⑤", "⑥"]

interface GenerationPlanReportProps {
  projectId: string
  endpointId: string
  /** Báo cáo vừa nhận khi sinh trong phiên này - có thì dùng luôn, không gọi API. */
  freshPlan?: GenerationPlanSummary
}

/**
 * Module 15h - báo cáo 1 lần sinh, mặc định thu gọn còn 1 dòng, bấm mũi tên mới mở. Báo cáo được lưu
 * ở backend nên tải lại trang vẫn xem được; chỉ gọi API khi người dùng mở ra (không gọi N request lúc
 * tải danh sách endpoint). Module 15i - chia checklist theo từng loại test case, sau cùng mới tới phần
 * AI đề xuất thêm (đúng thứ tự AI được yêu cầu làm).
 */
export function GenerationPlanReport({ projectId, endpointId, freshPlan }: GenerationPlanReportProps) {
  const [expanded, setExpanded] = useState(false)

  const { data: savedPlan, isLoading, isError } = useQuery({
    queryKey: ["generation-plan", projectId, endpointId],
    queryFn: () => getLatestGenerationPlan(projectId, endpointId),
    enabled: expanded && !freshPlan,
  })
  const plan = freshPlan ?? savedPlan ?? null

  return (
    <div className="mt-3 rounded-md border border-border bg-muted/30 px-3 py-2 text-xs leading-relaxed">
      <button
        type="button"
        onClick={() => setExpanded((v) => !v)}
        aria-expanded={expanded}
        className="inline-flex w-full items-center gap-1.5 text-left text-sm"
      >
        {expanded ? <ChevronDown className="h-4 w-4 shrink-0" /> : <ChevronRight className="h-4 w-4 shrink-0" />}
        <span className="font-medium">Báo cáo checklist lần sinh gần nhất</span>
        {plan && <span className="truncate text-xs text-muted-foreground">· {headline(plan)}</span>}
      </button>

      {expanded && (
        <div className="mt-2">
          {!plan && isLoading && (
            <p className="flex items-center gap-1.5 text-muted-foreground">
              <Loader2 className="h-3 w-3 animate-spin" /> Đang tải báo cáo...
            </p>
          )}
          {!plan && isError && <p className="text-destructive">Không tải được báo cáo, vui lòng thử lại.</p>}
          {!plan && !isLoading && !isError && (
            <p className="text-muted-foreground">
              Chưa có báo cáo checklist cho endpoint này (test case được sinh trước khi có tính năng này). Sinh lại
              để có báo cáo.
            </p>
          )}
          {plan && <PlanDetail plan={plan} />}
        </div>
      )}
    </div>
  )
}

function headline(plan: GenerationPlanSummary) {
  const covered = plan.aiSatisfiedCount + plan.systemFilledCount + plan.alreadyCoveredCount
  const applicable = plan.checklistTotal - plan.notApplicableCount
  return `đủ ${covered}/${applicable} mục checklist · ${plan.extraCaseNames.length} case AI đề xuất thêm`
}

function PlanDetail({ plan }: { plan: GenerationPlanSummary }) {
  const covered = plan.aiSatisfiedCount + plan.systemFilledCount + plan.alreadyCoveredCount
  const applicable = plan.checklistTotal - plan.notApplicableCount
  const total = plan.checklistCaseCount + plan.extraCaseNames.length
  const groups = CATEGORIES.map((category) => ({
    ...category,
    items: plan.items.filter((item) => item.type.startsWith(category.prefix)),
  })).filter((group) => group.items.length > 0)

  return (
    <>
      <p className="text-sm">
        Tổng cộng <span className="font-semibold">{total}</span> test case ={" "}
        <span className="font-semibold">{plan.checklistCaseCount}</span> case theo checklist +{" "}
        <span className="font-semibold">{plan.extraCaseNames.length}</span> case AI đề xuất thêm
      </p>
      <p className="mt-1 text-muted-foreground">
        Checklist do hệ thống tự tính từ OpenAPI spec theo kỹ thuật EP/BVA, riêng cho từng loại test case. AI phải làm
        đủ checklist trước rồi mới đề xuất thêm. Hệ thống tự đọc nội dung từng test case để chấm (không dựa vào tên
        case hay lời AI tự khai); mục AI làm thiếu hoặc sai thì hệ thống tự tạo case bù.
      </p>
      {plan.checklistTotal > 0 && (
        <p className="mt-1">
          Kết quả: <span className="font-semibold">đủ {covered}/{applicable} mục</span> · AI làm đạt{" "}
          {plan.aiSatisfiedCount}/{plan.aiAskedCount} mục được giao · hệ thống bù {plan.systemFilledCount}
          {plan.alreadyCoveredCount > 0 && <> · {plan.alreadyCoveredCount} mục đã có sẵn từ case khoá/tự thêm</>}
          {plan.notFillableCount > 0 && (
            <span className="text-destructive"> · {plan.notFillableCount} mục chưa có case</span>
          )}
        </p>
      )}

      {groups.length === 0 && (
        <p className="mt-3 text-amber-600 dark:text-amber-400">
          Spec của endpoint này không có field/tham số nào tạo được mục checklist cho loại case đã chọn — chỉ còn phần
          AI đề xuất thêm bên dưới.
        </p>
      )}

      {groups.map((group, index) => (
        <section key={group.prefix} className="mt-4">
          <h4 className="text-sm font-semibold">
            {CIRCLED[index]} {group.title} — {group.items.length} mục
          </h4>
          <p className="text-muted-foreground">{group.rule}</p>
          <ol className="mt-2 flex flex-col gap-2">
            {group.items.map((item, itemIndex) => (
              <ChecklistRow key={`${item.type}-${item.field}-${itemIndex}`} item={item} index={itemIndex} />
            ))}
          </ol>
        </section>
      ))}

      <section className="mt-4">
        <h4 className="text-sm font-semibold">
          {CIRCLED[groups.length]} AI đề xuất thêm — {plan.extraCaseNames.length} test case
        </h4>
        <p className="text-muted-foreground">
          Chỉ dành cho tình huống không có trong checklist nào ở trên: tổ hợp nhiều field, luật nghiệp vụ, dữ liệu đặc
          biệt (Unicode/emoji), injection. Không bắt buộc nên số lượng có thể khác giữa các lần sinh — hệ thống không có
          mục nào để chấm phần này, nên đọc lại trước khi tin.
        </p>
        {plan.extraCaseNames.length > 0 ? (
          <ul className="mt-1 pl-4 text-muted-foreground">
            {plan.extraCaseNames.map((name, i) => (
              <li key={i}>• {name}</li>
            ))}
          </ul>
        ) : (
          <p className="mt-1 text-muted-foreground">Lần sinh này AI không đề xuất thêm case nào.</p>
        )}
      </section>

      <p className="mt-3 border-t border-border pt-2">
        Tổng cộng: <span className="font-semibold">{total} test case</span>
        {plan.checklistCaseCount !==
          plan.items.reduce((sum, i) => sum + (i.status === "ALREADY_COVERED" ? 0 : i.coveredBy.length), 0) && (
          <span className="text-muted-foreground"> (1 case có thể đạt nhiều mục cùng lúc)</span>
        )}
      </p>
    </>
  )
}

function ChecklistRow({ item, index }: { item: ChecklistItemResponse; index: number }) {
  // Biên ngầm của body/query kỳ vọng thành công; ở tham số path (id không tồn tại) kỳ vọng lỗi nên không gắn nhãn.
  const implicit = IMPLICIT_TYPES.has(item.type) && item.description.includes("thành công")
  return (
    <li>
      <div className="flex flex-wrap items-start gap-2">
        <span className="shrink-0 font-medium">Mục {index + 1}.</span>
        <span className="min-w-0 flex-1">
          {item.description}
          {implicit && (
            <span
              className="ml-1.5 rounded-full bg-amber-500/10 px-1.5 py-0.5 font-medium text-amber-600 dark:text-amber-400"
              title="Spec không khai ràng buộc nên giá trị này không bị cấm - kỳ vọng thành công. Nếu API trả 400 thì spec đang khai thiếu ràng buộc hoặc API có lỗi."
            >
              ⚠ biên ngầm
            </span>
          )}
        </span>
        <span className={cn("shrink-0 rounded-full px-2 py-0.5 font-medium", STATUS_DISPLAY[item.status].className)}>
          {STATUS_DISPLAY[item.status].label}
        </span>
      </div>
      {item.coveredBy.length > 0 ? (
        <ul className="mt-0.5 pl-12 text-muted-foreground">
          {item.coveredBy.map((name, i) => (
            <li key={i}>
              • {name}
              {item.status === "ALREADY_COVERED" && " (case có sẵn)"}
            </li>
          ))}
        </ul>
      ) : (
        item.reason && <p className="mt-0.5 pl-12 text-amber-600 dark:text-amber-400">{item.reason}</p>
      )}
    </li>
  )
}
