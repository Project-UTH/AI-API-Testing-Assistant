import { useState } from "react"
import { useMutation, useQuery } from "@tanstack/react-query"
import { Loader2, Sparkles } from "lucide-react"

import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { useGenerationJobs } from "@/components/generation/GenerationJobsProvider"
import { ApiError } from "@/lib/api"
import { pathParamNames, WRITE_METHODS } from "@/lib/baseline"
import type { Endpoint } from "@/lib/endpoints"
import { startJob } from "@/lib/generationJobs"
import { getProject } from "@/lib/projects"
import { listRequirementDocs } from "@/lib/requirementDocs"
import {
  BusinessChecklistFields,
  budgetOf,
  EMPTY_CHECKLIST_INPUT,
  estimateChecklistTokens,
  type ChecklistInput,
} from "@/components/generation/BusinessChecklistFields"

interface Props {
  projectId: string
  /** 1 hoặc nhiều endpoint: job chạy lần lượt từng endpoint, dùng chung cấu hình và trần token. */
  endpoints: Endpoint[]
  open: boolean
  onOpenChange: (open: boolean) => void
}

type GroupKey = "positive" | "negative" | "boundary" | "security" | "owasp"

const GROUPS: { key: GroupKey; label: string; hint: string }[] = [
  { key: "positive", label: "Positive (luôn có)", hint: "test gốc: dữ liệu hợp lệ, API phải trả thành công; các nhóm khác dựa vào test này" },
  { key: "negative", label: "Negative", hint: "dữ liệu sai: thiếu trường bắt buộc, sai kiểu, sai định dạng; API phải báo lỗi 4xx" },
  { key: "boundary", label: "Boundary", hint: "giá trị biên: min/max, độ dài, rỗng, theo spec" },
  { key: "security", label: "Security", hint: "kiểm tra API chặn khi thiếu hoặc sai token; cần cấu hình xác thực của project" },
  {
    key: "owasp",
    label: "OWASP probe (0 token)",
    hint: "field lạ (mass assignment), JSON hỏng, sai Content-Type, phương thức TRACE. Chỉ gửi thêm request tới API đích, không sửa dữ liệu thật; cần test gốc hợp lệ",
  },
]

const DEFAULT_GROUPS: Record<GroupKey, boolean> = { positive: true, negative: true, boundary: true, security: false, owasp: false }

function loadGroups(projectId: string): Record<GroupKey, boolean> {
  try {
    const raw = localStorage.getItem(`gen-groups-${projectId}`)
    return raw ? { ...DEFAULT_GROUPS, ...(JSON.parse(raw) as Partial<Record<GroupKey, boolean>>), positive: true } : DEFAULT_GROUPS
  } catch {
    return DEFAULT_GROUPS
  }
}

function saveGroups(projectId: string, groups: Record<GroupKey, boolean>) {
  try {
    localStorage.setItem(`gen-groups-${projectId}`, JSON.stringify(groups))
  } catch {
    // trình duyệt chặn lưu trữ: bỏ qua
  }
}

/**
 * Cấu hình rồi bắt đầu job "Sinh test case" chạy nền. Hộp thoại đóng ngay khi job bắt đầu; tiến trình hiện ở khung giám sát (thu nhỏ được).
 * Test gốc được chạy trên API đích nên với method ghi bắt buộc người dùng xác nhận.
 */
export function GenerateWizardDialog({ projectId, endpoints, open, onOpenChange }: Props) {
  const { track } = useGenerationJobs()
  const isWrite = endpoints.some((e) => WRITE_METHODS.includes(e.method.toUpperCase()))
  const params = Array.from(new Set(endpoints.flatMap((e) => pathParamNames(e.path))))
  const many = endpoints.length > 1
  const { data: project } = useQuery({ queryKey: ["project", projectId], queryFn: () => getProject(projectId), enabled: open })

  const [confirmWrite, setConfirmWrite] = useState(false)
  const [rounds, setRounds] = useState(2)
  // "AI": AI sinh test gốc rồi hệ thống bổ sung. "SYSTEM": chỉ bổ sung từ test gốc đã Pass có sẵn (0 token).
  const [mode, setMode] = useState<"AI" | "SYSTEM">("AI")
  const [groups, setGroups] = useState<Record<GroupKey, boolean>>(() => loadGroups(projectId))
  const [dryRun, setDryRun] = useState(false)
  // "AI bổ sung checklist nghiệp vụ": AI lập mục checklist để bạn duyệt (không sinh test). Consent luôn bắt đầu lại ở trạng thái chưa tick.
  const [checklist, setChecklist] = useState(false)
  const [checklistInput, setChecklistInput] = useState<ChecklistInput>(EMPTY_CHECKLIST_INPUT)
  const [tokenCap, setTokenCap] = useState(120000)
  const [values, setValues] = useState<Record<string, string>>({})

  const { data: docs = [] } = useQuery({
    queryKey: ["requirement-docs", projectId],
    queryFn: () => listRequirementDocs(projectId),
    enabled: open && checklist,
  })
  const selectedDocs = docs.filter((d) => !checklistInput.excluded.has(d.id))
  const hasChecklistInput = selectedDocs.length > 0 || checklistInput.instruction.trim().length > 0
  const effectiveSuggest = !hasChecklistInput || checklistInput.suggest
  const useChecklist = mode === "AI" && checklist

  const mutation = useMutation({
    mutationFn: () =>
      startJob(projectId, {
        endpointIds: endpoints.map((e) => e.id),
        type: mode === "AI" ? "FULL" : "SYSTEM",
        dryRun,
        confirmWrite,
        maxRepairRounds: rounds,
        pathParamValues: Object.fromEntries(Object.entries(values).filter(([, v]) => v.trim() !== "")),
        fillCoverage: true,
        includePositive: true,
        includeNegative: groups.negative,
        includeBoundary: groups.boundary,
        includeSecurity: groups.security,
        includeOwasp: groups.owasp,
        businessChecklist: useChecklist,
        documentIds: useChecklist ? selectedDocs.map((d) => d.id) : undefined,
        instruction: useChecklist ? checklistInput.instruction.trim() || undefined : undefined,
        suggest: useChecklist ? effectiveSuggest : undefined,
        rulesTokenBudget: useChecklist ? budgetOf(checklistInput) : undefined,
        tokenCap,
      }),
    onSuccess: (job) => {
      saveGroups(projectId, groups)
      track(job)
      onOpenChange(false)
    },
  })

  const targetUrl = project?.targetBaseUrl ?? null
  const checklistEstimate = useChecklist
    ? estimateChecklistTokens(endpoints.length, selectedDocs.reduce((sum, d) => sum + d.sizeBytes, 0) + checklistInput.instruction.length, effectiveSuggest, hasChecklistInput)
    : 0
  const canRun = !mutation.isPending && !!targetUrl && (!isWrite || confirmWrite) && (!useChecklist || checklistInput.consent)

  return (
    <AlertDialog open={open} onOpenChange={(next) => !mutation.isPending && onOpenChange(next)}>
      <AlertDialogContent className="max-h-[90vh] overflow-y-auto data-[size=default]:sm:max-w-2xl">
        <AlertDialogHeader>
          <AlertDialogTitle>
            {many ? `Sinh test case cho ${endpoints.length} endpoint` : `Sinh test case: ${endpoints[0].method} ${endpoints[0].path}`}
          </AlertDialogTitle>
          <AlertDialogDescription>
            AI sinh 1 test gốc hợp lệ, hệ thống chạy thử trên API thật để xác nhận (AI tự sửa theo lỗi thật nếu cần), rồi tự bổ sung toàn bộ checklist
            (0 token). Chạy nền: bạn xem từng bước, huỷ hoặc thu nhỏ khung giám sát để làm việc khác.
          </AlertDialogDescription>
        </AlertDialogHeader>

        <div className="flex flex-col gap-3 text-sm">
          <p>
            Đích: <code className="font-mono text-xs">{targetUrl ?? "(chưa cấu hình Target URL)"}</code>
          </p>
          <fieldset className="flex flex-col gap-1.5">
            <legend className="mb-1 text-xs font-medium">Nhóm test cần sinh</legend>
            {GROUPS.map((g) => (
              <label key={g.key} className="flex items-start gap-2 text-xs" title={g.hint}>
                <Checkbox
                  checked={g.key === "positive" ? true : groups[g.key]}
                  disabled={g.key === "positive"}
                  onCheckedChange={(c) => setGroups((prev) => ({ ...prev, [g.key]: c === true }))}
                />
                <span>
                  <span className="font-medium">{g.label}</span>
                  <span className="text-muted-foreground"> - {g.hint}</span>
                </span>
              </label>
            ))}
          </fieldset>
          <fieldset className="flex flex-col gap-1.5 text-xs">
            <legend className="mb-1 font-medium">Cách thực hiện</legend>
            <label className="flex items-start gap-2">
              <input type="radio" name="gw-mode" checked={mode === "AI"} onChange={() => setMode("AI")} className="mt-0.5" />
              <span>AI sinh test gốc hợp lệ, chạy thật xác nhận, rồi hệ thống bổ sung các nhóm đã chọn (tốn token)</span>
            </label>
            <label className="flex items-start gap-2">
              <input type="radio" name="gw-mode" checked={mode === "SYSTEM"} onChange={() => setMode("SYSTEM")} className="mt-0.5" />
              <span>Chỉ bổ sung từ test gốc đã Pass có sẵn của endpoint (0 token, không gọi AI)</span>
            </label>
          </fieldset>
          {isWrite && (
            <div role="note" className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-xs">
              <p className="font-medium text-destructive">Hệ thống sẽ GHI THẬT vào API đích ({Array.from(new Set(endpoints.filter((e) => WRITE_METHODS.includes(e.method.toUpperCase())).map((e) => e.method))).join("/")})</p>
              <p className="mt-1 text-muted-foreground">
                Test gốc được chạy thật, có thể tạo hoặc sửa dữ liệu (và chạy lại nếu AI phải sửa). Chỉ dùng với môi trường test/staging, không dùng với
                production.
              </p>
              <label className="mt-2 flex items-start gap-2">
                <Checkbox checked={confirmWrite} onCheckedChange={(c) => setConfirmWrite(c === true)} />
                <span>Tôi hiểu và đồng ý ghi dữ liệu thật lên môi trường này</span>
              </label>
            </div>
          )}
          {params.length > 0 && (
            <div className="flex flex-col gap-2">
              <p className="text-xs text-muted-foreground">
                Giá trị thật cho tham số đường dẫn (bỏ trống nếu đã có test POST tạo tài nguyên để hệ thống tự nối chuỗi):
              </p>
              {params.map((name) => (
                <div key={name} className="flex items-center gap-2">
                  <Label htmlFor={`gw-${name}`} className="w-28 shrink-0 font-mono text-xs">
                    {"{" + name + "}"}
                  </Label>
                  <Input id={`gw-${name}`} value={values[name] ?? ""} onChange={(e) => setValues((prev) => ({ ...prev, [name]: e.target.value }))} />
                </div>
              ))}
            </div>
          )}
          <div hidden={mode === "SYSTEM"}>
            <label className="flex items-start gap-2 text-xs" title="AI lập checklist nghiệp vụ để bạn duyệt; chưa duyệt thì chưa có test nào">
              <Checkbox checked={checklist} onCheckedChange={(c) => setChecklist(c === true)} className="mt-0.5" />
              <span>
                <span className="font-medium">+ AI bổ sung checklist nghiệp vụ</span>
                <span className="text-muted-foreground"> - từ tài liệu/chỉ dẫn của bạn và/hoặc AI gợi ý thêm; bạn duyệt rồi hệ thống mới sinh test (0 token)</span>
              </span>
            </label>
            {checklist && <BusinessChecklistFields projectId={projectId} value={checklistInput} onChange={setChecklistInput} />}
          </div>
          <div className="flex flex-wrap items-center gap-3" hidden={mode === "SYSTEM"}>
            <Label htmlFor="gw-rounds" className="text-xs">
              Số lần AI sửa tối đa
            </Label>
            <select
              id="gw-rounds"
              className="h-8 rounded-lg border border-input bg-transparent px-2 text-sm dark:bg-input/30"
              value={rounds}
              onChange={(e) => setRounds(Number(e.target.value))}
            >
              {[0, 1, 2, 3, 4, 5].map((n) => (
                <option key={n} value={n}>
                  {n}
                </option>
              ))}
            </select>
            <label className="flex items-center gap-2 text-xs" title="Chỉ liệt kê các bước sẽ làm, không gọi AI và không gọi API đích">
              <Checkbox checked={dryRun} onCheckedChange={(c) => setDryRun(c === true)} />
              Chạy thử (không gọi AI/API đích)
            </label>
          </div>
          <div className="flex flex-wrap items-center gap-2 text-xs" hidden={mode === "SYSTEM"}>
            <Label htmlFor="gw-cap" className="text-xs">
              Trần token AI của lần chạy
            </Label>
            <select
              id="gw-cap"
              className="h-8 rounded-lg border border-input bg-transparent px-2 text-sm dark:bg-input/30"
              value={tokenCap}
              onChange={(e) => setTokenCap(Number(e.target.value))}
            >
              {[30000, 60000, 120000, 250000, 500000].map((n) => (
                <option key={n} value={n}>
                  {n.toLocaleString("vi-VN")}
                </option>
              ))}
            </select>
          </div>
          <p className="text-xs text-muted-foreground" hidden={mode === "SYSTEM"}>
            Ước tính {(endpoints.length * 10000).toLocaleString("vi-VN")}–{(endpoints.length * 10000 * (1 + rounds)).toLocaleString("vi-VN")} token cho test gốc (mỗi lần gọi AI ~6.500–27.000)
            {useChecklist ? ` + khoảng ${checklistEstimate.toLocaleString("vi-VN")} token cho checklist nghiệp vụ (ngân sách riêng, trần cứng ${budgetOf(checklistInput).toLocaleString("vi-VN")})` : ""}.
            Không giới hạn thời gian: bước AI có thể kéo dài vài phút, bạn theo dõi từng bước và bấm Huỷ khi cần. Chạm trần token thì job dừng ở bước an toàn.
          </p>
          {useChecklist && !checklistInput.consent && (
            <p className="text-xs text-muted-foreground">Tick đồng ý gửi tài liệu/chỉ dẫn tới AI bên ngoài (ở khối checklist nghiệp vụ) để bắt đầu.</p>
          )}
          {!targetUrl && <p className="text-xs text-destructive">Project chưa có Target URL: import lại spec và điền URL trước.</p>}
          {mutation.isError && (
            <p role="alert" className="text-sm text-destructive">
              {mutation.error instanceof ApiError ? mutation.error.message : "Không bắt đầu được job, vui lòng thử lại."}
            </p>
          )}
        </div>

        <AlertDialogFooter>
          <AlertDialogCancel disabled={mutation.isPending}>Đóng</AlertDialogCancel>
          <Button onClick={() => mutation.mutate()} disabled={!canRun} aria-busy={mutation.isPending}>
            {mutation.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Sparkles className="h-4 w-4" />}
            {dryRun ? "Chạy thử" : "Bắt đầu sinh"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
