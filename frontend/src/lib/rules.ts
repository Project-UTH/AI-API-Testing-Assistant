import { apiFetch, apiFetchPaged, type PagedResult } from "@/lib/api"

/** 17q G3 - checklist nghiệp vụ "mục trước, test sau". Backend tính trạng thái/câu mục; frontend chỉ hiển thị + nhập tham số. */

export type RuleTemplate =
  | "COMPARE_FIELDS"
  | "REQUIRES"
  | "ALL_OR_NONE"
  | "ONLY_ONE"
  | "ZERO_OR_ONE"
  | "OR"
  | "DATE_RELATIVE"
  | "VALUE_RANGE"
  | "ARRAY_AGGREGATE"
  | "UNIQUE"

export type RuleStatus = "PROPOSED" | "APPROVED" | "REJECTED" | "STALE" | "CONFLICT"
export type RuleSource = "USER" | "SYSTEM_DETECTED" | "DOC" | "INSTRUCTION" | "INFERRED"
export type RuleRejectReason = "WRONG_MEANING" | "DUPLICATE" | "NOT_APPLICABLE" | "DUPLICATE_OF_SPEC"
export type RuleOp = "LT" | "LTE" | "EQ" | "GTE" | "GT"

export interface RuleParams {
  left?: string
  op?: string
  right?: string
  field?: string
  fieldValue?: string
  fields?: string[]
  offset?: number
  unit?: "DAY" | "HOUR"
  min?: number
  max?: number
  array?: string
  item?: string
  fn?: "SUM" | "COUNT" | "DISTINCT"
  value?: number
}

export interface BusinessRule {
  id: string
  endpointKey: string
  endpointId: string | null
  template: RuleTemplate
  params: RuleParams
  /** Câu mục tiếng Việt do backend dựng - luôn dùng bản này để hiển thị. */
  sentence: string
  expectedStatus: number | null
  /** Mã lỗi AI GỢI Ý cho mục GIẢ ĐỊNH (chỉ điền sẵn khi duyệt; không có hiệu lực cho tới khi người duyệt xác nhận). */
  suggestedStatus: number | null
  status: RuleStatus
  source: RuleSource
  rationale: string | null
  quote: string | null
  /** Vị trí (ký tự) của đoạn trích trong tài liệu đã chuẩn hoá; dùng để tô sáng chính xác. */
  quoteOffset: number | null
  documentId: string | null
  extractionJobId: string | null
  rejectReason: RuleRejectReason | null
  statusNote: string | null
  reviewedAt: string | null
  /** Số test hệ thống đã sinh từ mục này. */
  testCount: number
  createdAt: string
  version: number
}

interface Display {
  label: string
  className: string
  title: string
}

export const RULE_TEMPLATE_ORDER: RuleTemplate[] = [
  "COMPARE_FIELDS",
  "REQUIRES",
  "ALL_OR_NONE",
  "ONLY_ONE",
  "ZERO_OR_ONE",
  "OR",
  "DATE_RELATIVE",
  "VALUE_RANGE",
  "ARRAY_AGGREGATE",
  "UNIQUE",
]

/** Mẫu có dùng được với endpoint này không, và ví dụ ghép từ CHÍNH field của endpoint (thay cho ví dụ chung chung). */
export interface TemplateFit {
  usable: boolean
  example: string
  /** Khi không dùng được: vì sao. */
  reason: string
}

const DATE_NAME = /date|time|deadline|expire|due|at$|ngay|han/i

export function templateFit(template: RuleTemplate, fields: EndpointField[]): TemplateFit {
  const numeric = fields.filter((f) => f.numeric)
  const textual = fields.filter((f) => !f.numeric && (f.type === null || f.type === "string"))
  const arrays = fields.filter((f) => f.type === "array")
  const comparable = [...numeric, ...textual]
  const name = (f: EndpointField) => f.ref
  const no = (reason: string): TemplateFit => ({ usable: false, example: "", reason })
  const ok = (example: string): TemplateFit => ({ usable: true, example, reason: "" })
  switch (template) {
    case "COMPARE_FIELDS": {
      const pool = numeric.length >= 2 ? numeric : comparable
      return pool.length < 2 ? no("Cần ít nhất 2 field số hoặc chuỗi để so sánh") : ok(`${name(pool[0])} ≤ ${name(pool[1])}`)
    }
    case "REQUIRES":
      return fields.length < 2 ? no("Cần ít nhất 2 field") : ok(`Có ${name(fields[0])} thì phải có ${name(fields[1])}`)
    case "ALL_OR_NONE":
      return fields.length < 2 ? no("Cần ít nhất 2 field") : ok(`${name(fields[0])}, ${name(fields[1])} phải đi cùng nhau`)
    case "ONLY_ONE":
      return fields.length < 2 ? no("Cần ít nhất 2 field") : ok(`${name(fields[0])} hoặc ${name(fields[1])}, đúng một`)
    case "ZERO_OR_ONE":
      return fields.length < 2 ? no("Cần ít nhất 2 field") : ok(`không gửi cả ${name(fields[0])} lẫn ${name(fields[1])}`)
    case "OR":
      return fields.length < 2 ? no("Cần ít nhất 2 field") : ok(`${name(fields[0])} hoặc ${name(fields[1])}, ít nhất một`)
    case "DATE_RELATIVE": {
      const f = textual.find((x) => DATE_NAME.test(x.name)) ?? textual[0]
      return f ? ok(`${name(f)} không ở quá khứ`) : no("Không có field chuỗi (ngày/giờ) nào")
    }
    case "VALUE_RANGE": {
      const f = numeric[0]
      return f ? ok(`${name(f)} trong [${f.minimum ?? 0}, ${f.maximum ?? "…"}]`) : no("Không có field số nào")
    }
    case "ARRAY_AGGREGATE": {
      const arr = arrays.find((a) => fields.some((f) => f.numeric && f.ref.startsWith(`${a.ref}[0].`)))
      const item = arr ? fields.find((f) => f.numeric && f.ref.startsWith(`${arr.ref}[0].`)) : undefined
      return arr && item ? ok(`Tổng ${item.ref.slice(arr.ref.length + 4)} của ${arr.ref}[] = …`) : no("Không có mảng nào chứa field số")
    }
    case "UNIQUE":
      return fields.length < 1 ? no("Endpoint không có field nào") : ok(`không tạo trùng theo ${name(fields[0])}`)
  }
}

export const RULE_TEMPLATE_DISPLAY: Record<RuleTemplate, { label: string; example: string }> = {
  COMPARE_FIELDS: { label: "So sánh 2 field", example: "salaryMin ≤ salaryMax" },
  REQUIRES: { label: "Có A thì phải có B", example: "Có type = REMOTE thì phải có timezone" },
  ALL_OR_NONE: { label: "Có đủ cả nhóm hoặc không có field nào", example: "city, country phải đi cùng nhau" },
  ONLY_ONE: { label: "Đúng 1 field trong nhóm", example: "email hoặc phone, đúng một" },
  ZERO_OR_ONE: { label: "Tối đa 1 field trong nhóm", example: "không gửi cả percent lẫn amount" },
  OR: { label: "Ít nhất 1 field trong nhóm", example: "email hoặc phone, ít nhất một" },
  DATE_RELATIVE: { label: "Ngày so với hôm nay", example: "deadline không ở quá khứ" },
  VALUE_RANGE: { label: "Khoảng giá trị nghiệp vụ", example: "years trong [0, 50]" },
  ARRAY_AGGREGATE: { label: "Tổng hợp trên mảng", example: "Tổng criteria[].weight = 100" },
  UNIQUE: { label: "Không được tạo trùng", example: "không tạo trùng theo [name]" },
}

export const RULE_STATUS_DISPLAY: Record<RuleStatus, Display> = {
  PROPOSED: { label: "Chờ duyệt", className: "bg-sky-500/10 text-sky-700 dark:text-sky-400", title: "Đề xuất - chưa sinh test cho tới khi bạn duyệt" },
  APPROVED: { label: "Đã duyệt", className: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400", title: "Bạn đã duyệt - hệ thống sinh test từ mục này" },
  REJECTED: { label: "Đã loại", className: "bg-muted text-muted-foreground line-through", title: "Đã loại (giữ lại để đo độ chính xác, không sinh test)" },
  STALE: { label: "Lỗi thời", className: "bg-zinc-500/10 text-zinc-700 dark:text-zinc-400", title: "Field hoặc endpoint của mục không còn trong spec" },
  CONFLICT: { label: "Mâu thuẫn spec", className: "bg-destructive/10 text-destructive", title: "Mục mâu thuẫn ràng buộc spec - duyệt phải ghi chú vì sao" },
}

export const RULE_SOURCE_DISPLAY: Record<RuleSource, Display> = {
  USER: { label: "Bạn nhập", className: "bg-slate-500/10 text-slate-700 dark:text-slate-300", title: "Mục do bạn tự nhập" },
  SYSTEM_DETECTED: { label: "Hệ thống phát hiện", className: "bg-teal-500/10 text-teal-700 dark:text-teal-400", title: "Hệ thống tự phát hiện từ spec (0 token)" },
  DOC: { label: "Có trích dẫn", className: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400", title: "AI trích từ tài liệu nghiệp vụ, có đoạn trích nguyên văn" },
  INSTRUCTION: { label: "Từ chỉ dẫn", className: "bg-sky-500/10 text-sky-700 dark:text-sky-400", title: "AI trích từ ô chỉ dẫn của bạn" },
  INFERRED: { label: "GIẢ ĐỊNH", className: "bg-amber-500/10 text-amber-700 dark:text-amber-400", title: "AI tự suy đoán, không có trích dẫn hợp lệ" },
}

export const RULE_REJECT_REASON_LABEL: Record<RuleRejectReason, string> = {
  WRONG_MEANING: "Sai nghĩa",
  DUPLICATE: "Trùng mục khác",
  NOT_APPLICABLE: "Không áp dụng",
  DUPLICATE_OF_SPEC: "Trùng ràng buộc spec (tự loại)",
}

/** Luôn đi qua các hàm get…Display (không index thẳng Record): giá trị lạ từ backend hiện mã thô thay vì làm trắng trang. */
const FALLBACK: Display = { label: "", className: "bg-muted text-muted-foreground", title: "" }

export function getRuleStatusDisplay(status: string | null | undefined): Display | null {
  if (!status) return null
  return RULE_STATUS_DISPLAY[status as RuleStatus] ?? { ...FALLBACK, label: status, title: status }
}

export function getRuleSourceDisplay(source: string | null | undefined): Display | null {
  if (!source) return null
  return RULE_SOURCE_DISPLAY[source as RuleSource] ?? { ...FALLBACK, label: source, title: source }
}

export function getRuleTemplateLabel(template: string | null | undefined): string {
  if (!template) return ""
  return RULE_TEMPLATE_DISPLAY[template as RuleTemplate]?.label ?? template
}

export function getRejectReasonLabel(reason: string | null | undefined): string {
  if (!reason) return ""
  return RULE_REJECT_REASON_LABEL[reason as RuleRejectReason] ?? reason
}

export const RULE_OP_SYMBOL: Record<RuleOp, string> = { LT: "<", LTE: "≤", EQ: "=", GTE: "≥", GT: ">" }

function op(value: string | undefined): string {
  return RULE_OP_SYMBOL[value as RuleOp] ?? "?"
}

/** Câu mục xem trước khi nhập (cùng ý với RuleSentence của backend; sau khi lưu luôn hiện `sentence` do backend trả). */
export function ruleSentence(template: RuleTemplate, p: RuleParams): string {
  const group = (p.fields ?? []).filter(Boolean).join(", ")
  switch (template) {
    case "COMPARE_FIELDS":
      return `${p.left || "…"} ${op(p.op)} ${p.right || "…"}`
    case "REQUIRES":
      return `Có ${p.field || "…"}${p.fieldValue ? ` = ${p.fieldValue}` : ""} thì phải có ${group || "…"}`
    case "ALL_OR_NONE":
      return `Có đủ cả nhóm [${group}] hoặc không có field nào`
    case "ONLY_ONE":
      return `Đúng 1 field trong nhóm [${group}]`
    case "ZERO_OR_ONE":
      return `Tối đa 1 field trong nhóm [${group}]`
    case "OR":
      return `Ít nhất 1 field trong nhóm [${group}]`
    case "DATE_RELATIVE": {
      const offset = p.offset ?? 0
      const suffix = offset === 0 ? "" : ` ${offset > 0 ? "+" : "-"} ${Math.abs(offset)} ${p.unit === "HOUR" ? "giờ" : "ngày"}`
      return `${p.field || "…"} ${op(p.op)} bây giờ${suffix}`
    }
    case "VALUE_RANGE":
      return `${p.field || "…"} trong khoảng [${p.min ?? "-"}, ${p.max ?? "-"}]`
    case "ARRAY_AGGREGATE":
      if (p.fn === "SUM") return `Tổng ${p.array || "…"}[].${p.item || "…"} ${op(p.op)} ${p.value ?? "…"}`
      if (p.fn === "COUNT") return `Số phần tử ${p.array || "…"} ${op(p.op)} ${p.value ?? "…"}`
      return `${p.array || "…"}[].${p.item || "…"} không trùng nhau`
    case "UNIQUE":
      return `Không được tạo trùng theo [${group}]`
    default:
      return template
  }
}

export interface RuleListParams {
  page: number
  size: number
  endpointId?: string
  status?: string
  source?: string
  template?: string
}

export function listBusinessRules(projectId: string, params: RuleListParams): Promise<PagedResult<BusinessRule>> {
  const query = new URLSearchParams({ page: String(params.page), size: String(params.size) })
  if (params.endpointId) query.set("endpointId", params.endpointId)
  if (params.status) query.set("status", params.status)
  if (params.source) query.set("source", params.source)
  if (params.template) query.set("template", params.template)
  return apiFetchPaged<BusinessRule>(`/projects/${projectId}/business-rules?${query.toString()}`)
}

export interface CreateRuleInput {
  endpointId: string
  template: RuleTemplate
  params: RuleParams
  expectedStatus?: number | null
  rationale?: string
  approve?: boolean
}

export function createBusinessRule(projectId: string, input: CreateRuleInput): Promise<BusinessRule> {
  return apiFetch<BusinessRule>(`/projects/${projectId}/business-rules`, { method: "POST", body: JSON.stringify(input) })
}

export function updateBusinessRule(
  projectId: string,
  ruleId: string,
  input: { params: RuleParams; expectedStatus?: number | null; rationale?: string }
): Promise<BusinessRule> {
  return apiFetch<BusinessRule>(`/projects/${projectId}/business-rules/${ruleId}`, { method: "PUT", body: JSON.stringify(input) })
}

export function approveBusinessRule(
  projectId: string,
  ruleId: string,
  input: { expectedStatus?: number | null; conflictNote?: string } = {}
): Promise<BusinessRule> {
  return apiFetch<BusinessRule>(`/projects/${projectId}/business-rules/${ruleId}/approve`, { method: "POST", body: JSON.stringify(input) })
}

export function rejectBusinessRule(projectId: string, ruleId: string, input: { reason: RuleRejectReason; note?: string }): Promise<BusinessRule> {
  return apiFetch<BusinessRule>(`/projects/${projectId}/business-rules/${ruleId}/reject`, { method: "POST", body: JSON.stringify(input) })
}

export function deleteBusinessRule(projectId: string, ruleId: string): Promise<void> {
  return apiFetch<void>(`/projects/${projectId}/business-rules/${ruleId}`, { method: "DELETE" })
}

export interface DetectRulesResult {
  proposedCount: number
  skippedExistingCount: number
}

export function detectBusinessRules(projectId: string): Promise<DetectRulesResult> {
  return apiFetch<DetectRulesResult>(`/projects/${projectId}/business-rules/detect`, { method: "POST" })
}

export interface GenerateRuleTestsResult {
  createdCount: number
  createdTestCaseIds: string[]
  notGenerated: { ruleId: string; sentence: string; reason: string }[]
}

export function generateRuleTests(projectId: string, input: { ruleIds?: string[]; endpointIds?: string[] } = {}): Promise<GenerateRuleTestsResult> {
  return apiFetch<GenerateRuleTestsResult>(`/projects/${projectId}/business-rules/generate-tests`, { method: "POST", body: JSON.stringify(input) })
}

/** Field của endpoint (từ spec, cùng nguồn với checklist) để chọn khi nhập mục; `ref` là chuỗi dùng trong tham số mục (body: tên, khác: "query:x"). */
export interface EndpointField {
  ref: string
  name: string
  location: "BODY" | "QUERY" | "PATH" | "HEADER"
  type: string | null
  required: boolean
  nested: boolean
  numeric: boolean
  enumValues: string[] | null
  minimum: number | null
  maximum: number | null
}

export function getEndpointFields(projectId: string, endpointId: string): Promise<EndpointField[]> {
  return apiFetch<EndpointField[]>(`/projects/${projectId}/business-rules/endpoint-fields?endpointId=${endpointId}`)
}

/** "REJECTED" = kỳ vọng "bị từ chối (4xx)" của test sinh từ mục. */
export function expectedLabel(expectedStatus: number | null): string {
  return expectedStatus == null ? "Bị từ chối (4xx)" : `Mã ${expectedStatus}`
}

export interface ApproveBatchResult {
  approvedIds: string[]
  notApproved: { ruleId: string; reason: string }[]
}

/** Duyệt hàng loạt: mục có trích dẫn; mục GIẢ ĐỊNH chỉ khi kèm mã lỗi bạn xác nhận riêng cho mục đó (expectedStatuses). Mục khác được trả lại kèm lý do. */
export function approveBusinessRulesBatch(
  projectId: string,
  ruleIds: string[],
  expectedStatuses?: Record<string, number>
): Promise<ApproveBatchResult> {
  return apiFetch<ApproveBatchResult>(`/projects/${projectId}/business-rules/approve-batch`, {
    method: "POST",
    body: JSON.stringify({ ruleIds, expectedStatuses }),
  })
}

/** Hoàn tác duyệt: chỉ khi chưa sinh test từ mục này. */
export function revertBusinessRule(projectId: string, ruleId: string): Promise<BusinessRule> {
  return apiFetch<BusinessRule>(`/projects/${projectId}/business-rules/${ruleId}/revert`, { method: "POST" })
}

/** Mã lỗi hay gặp để người duyệt chọn cho mục GIẢ ĐỊNH (backend nhận mọi mã 4xx). */
export const COMMON_REJECT_STATUSES = [400, 401, 403, 404, 409, 422] as const
