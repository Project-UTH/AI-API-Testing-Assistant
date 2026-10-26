import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { RULE_OP_SYMBOL, type EndpointField, type RuleOp, type RuleParams, type RuleTemplate } from "@/lib/rules"

const SELECT_CLASS = "h-8 w-full min-w-0 rounded-lg border border-input bg-transparent px-2.5 text-sm outline-none dark:bg-input/30"

interface FieldSelectProps {
  id: string
  label: string
  value: string | undefined
  fields: EndpointField[]
  onChange: (value: string) => void
}

function FieldSelect({ id, label, value, fields, onChange }: FieldSelectProps) {
  return (
    <div className="flex flex-col gap-1">
      <Label htmlFor={id} className="text-xs">
        {label}
      </Label>
      <select id={id} className={SELECT_CLASS} value={value ?? ""} onChange={(e) => onChange(e.target.value)}>
        <option value="">Chọn field…</option>
        {fields.map((f) => (
          <option key={f.ref} value={f.ref}>
            {f.ref}
            {f.type ? ` (${f.type})` : ""}
          </option>
        ))}
      </select>
    </div>
  )
}

function OpSelect({ id, value, ops, onChange }: { id: string; value: string | undefined; ops: RuleOp[]; onChange: (value: string) => void }) {
  return (
    <div className="flex flex-col gap-1">
      <Label htmlFor={id} className="text-xs">
        Phép so sánh
      </Label>
      <select id={id} className={SELECT_CLASS} value={value ?? ""} onChange={(e) => onChange(e.target.value)}>
        <option value="">Chọn…</option>
        {ops.map((op) => (
          <option key={op} value={op}>
            {RULE_OP_SYMBOL[op]} ({op})
          </option>
        ))}
      </select>
    </div>
  )
}

/** Nhóm field (ALL_OR_NONE/ONLY_ONE/ZERO_OR_ONE/OR/UNIQUE/REQUIRES-then): tick các field thuộc nhóm. */
function GroupPicker({ legend, fields, selected, onChange }: { legend: string; fields: EndpointField[]; selected: string[]; onChange: (next: string[]) => void }) {
  return (
    <fieldset className="flex flex-col gap-1.5">
      <legend className="text-xs font-medium">{legend}</legend>
      <div className="flex max-h-36 flex-wrap gap-x-4 gap-y-1.5 overflow-y-auto rounded-md border border-border p-2">
        {fields.length === 0 && <p className="text-xs text-muted-foreground">Endpoint không có field nào trong spec.</p>}
        {fields.map((f) => (
          <label key={f.ref} className="flex items-center gap-1.5 text-sm">
            <input
              type="checkbox"
              checked={selected.includes(f.ref)}
              onChange={(e) => onChange(e.target.checked ? [...selected, f.ref] : selected.filter((r) => r !== f.ref))}
            />
            {f.ref}
          </label>
        ))}
      </div>
    </fieldset>
  )
}

const ALL_OPS: RuleOp[] = ["LT", "LTE", "EQ", "GTE", "GT"]

interface RuleParamsFieldsProps {
  template: RuleTemplate
  params: RuleParams
  fields: EndpointField[]
  onChange: (next: RuleParams) => void
}

/** Form tham số THAY ĐỔI theo mẫu mục; ô chọn field lọc theo kiểu (số / chuỗi ngày / mảng) để người dùng khó nhập sai. */
export function RuleParamsFields({ template, params, fields, onChange }: RuleParamsFieldsProps) {
  const set = (patch: Partial<RuleParams>) => onChange({ ...params, ...patch })
  const numeric = fields.filter((f) => f.numeric)
  const textual = fields.filter((f) => !f.numeric && (f.type === null || f.type === "string"))
  const arrays = fields.filter((f) => f.type === "array")
  const group = params.fields ?? []

  switch (template) {
    case "COMPARE_FIELDS":
      return (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <FieldSelect id="rule-left" label="Field vế trái" value={params.left} fields={[...numeric, ...textual]} onChange={(left) => set({ left })} />
          <OpSelect id="rule-op" value={params.op} ops={ALL_OPS} onChange={(op) => set({ op })} />
          <FieldSelect id="rule-right" label="Field vế phải" value={params.right} fields={[...numeric, ...textual]} onChange={(right) => set({ right })} />
        </div>
      )
    case "REQUIRES":
      return (
        <div className="flex flex-col gap-3">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <FieldSelect id="rule-field" label="Nếu có field" value={params.field} fields={fields} onChange={(field) => set({ field })} />
            <div className="flex flex-col gap-1">
              <Label htmlFor="rule-field-value" className="text-xs">
                …bằng giá trị (để trống = chỉ cần có mặt)
              </Label>
              <Input id="rule-field-value" value={params.fieldValue ?? ""} onChange={(e) => set({ fieldValue: e.target.value })} />
            </div>
          </div>
          <GroupPicker legend="thì phải có các field" fields={fields.filter((f) => f.ref !== params.field)} selected={group} onChange={(next) => set({ fields: next })} />
        </div>
      )
    case "ALL_OR_NONE":
    case "ONLY_ONE":
    case "ZERO_OR_ONE":
    case "OR":
      return <GroupPicker legend="Nhóm field (chọn từ 2 field)" fields={fields} selected={group} onChange={(next) => set({ fields: next })} />
    case "UNIQUE":
      return <GroupPicker legend="Các field làm khoá duy nhất" fields={fields} selected={group} onChange={(next) => set({ fields: next })} />
    case "DATE_RELATIVE":
      return (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <FieldSelect id="rule-date-field" label="Field ngày/giờ" value={params.field} fields={textual} onChange={(field) => set({ field })} />
          <OpSelect id="rule-date-op" value={params.op} ops={["GTE", "GT", "LTE", "LT"]} onChange={(op) => set({ op })} />
          <div className="flex flex-col gap-1">
            <Label htmlFor="rule-date-offset" className="text-xs">
              Lệch so với bây giờ
            </Label>
            <Input id="rule-date-offset" type="number" value={params.offset ?? 0} onChange={(e) => set({ offset: Number(e.target.value) })} />
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor="rule-date-unit" className="text-xs">
              Đơn vị
            </Label>
            <select id="rule-date-unit" className={SELECT_CLASS} value={params.unit ?? "DAY"} onChange={(e) => set({ unit: e.target.value as "DAY" | "HOUR" })}>
              <option value="DAY">Ngày</option>
              <option value="HOUR">Giờ</option>
            </select>
          </div>
        </div>
      )
    case "VALUE_RANGE":
      return (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <FieldSelect id="rule-range-field" label="Field số" value={params.field} fields={numeric} onChange={(field) => set({ field })} />
          <div className="flex flex-col gap-1">
            <Label htmlFor="rule-range-min" className="text-xs">
              Nhỏ nhất (bao gồm)
            </Label>
            <Input id="rule-range-min" type="number" value={params.min ?? ""} onChange={(e) => set({ min: e.target.value === "" ? undefined : Number(e.target.value) })} />
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor="rule-range-max" className="text-xs">
              Lớn nhất (bao gồm)
            </Label>
            <Input id="rule-range-max" type="number" value={params.max ?? ""} onChange={(e) => set({ max: e.target.value === "" ? undefined : Number(e.target.value) })} />
          </div>
        </div>
      )
    case "ARRAY_AGGREGATE": {
      const items = fields.filter((f) => params.array && f.ref.startsWith(`${params.array}[0].`))
      return (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-5">
          <FieldSelect id="rule-array" label="Field mảng" value={params.array} fields={arrays} onChange={(array) => set({ array, item: undefined })} />
          <div className="flex flex-col gap-1">
            <Label htmlFor="rule-fn" className="text-xs">
              Hàm
            </Label>
            <select id="rule-fn" className={SELECT_CLASS} value={params.fn ?? ""} onChange={(e) => set({ fn: e.target.value as RuleParams["fn"] })}>
              <option value="">Chọn…</option>
              <option value="SUM">Tổng (SUM)</option>
              <option value="COUNT">Số phần tử (COUNT)</option>
              <option value="DISTINCT">Không trùng nhau (DISTINCT)</option>
            </select>
          </div>
          {params.fn !== "COUNT" && (
            <div className="flex flex-col gap-1">
              <Label htmlFor="rule-item" className="text-xs">
                Field con của phần tử
              </Label>
              <select
                id="rule-item"
                className={SELECT_CLASS}
                value={params.item ?? ""}
                onChange={(e) => set({ item: e.target.value })}
              >
                <option value="">Chọn…</option>
                {items.map((f) => (
                  <option key={f.ref} value={f.ref.slice(`${params.array}[0].`.length)}>
                    {f.ref.slice(`${params.array}[0].`.length)}
                  </option>
                ))}
              </select>
            </div>
          )}
          {params.fn !== "DISTINCT" && (
            <>
              <OpSelect id="rule-agg-op" value={params.op} ops={ALL_OPS} onChange={(op) => set({ op })} />
              <div className="flex flex-col gap-1">
                <Label htmlFor="rule-agg-value" className="text-xs">
                  Hằng số
                </Label>
                <Input id="rule-agg-value" type="number" value={params.value ?? ""} onChange={(e) => set({ value: e.target.value === "" ? undefined : Number(e.target.value) })} />
              </div>
            </>
          )}
        </div>
      )
    }
    default:
      return null
  }
}
