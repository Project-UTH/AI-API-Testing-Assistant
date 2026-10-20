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

interface Props {
  projectId: string
  endpoint: Endpoint
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * Cấu hình rồi bắt đầu job "Sinh đầy đủ" chạy nền. Hộp thoại đóng ngay khi job bắt đầu; tiến trình hiện ở khung giám sát (thu nhỏ được).
 * Test gốc được chạy trên API đích nên với method ghi bắt buộc người dùng xác nhận.
 */
export function GenerateWizardDialog({ projectId, endpoint, open, onOpenChange }: Props) {
  const { track } = useGenerationJobs()
  const isWrite = WRITE_METHODS.includes(endpoint.method.toUpperCase())
  const params = pathParamNames(endpoint.path)
  const { data: project } = useQuery({ queryKey: ["project", projectId], queryFn: () => getProject(projectId), enabled: open })

  const [confirmWrite, setConfirmWrite] = useState(false)
  const [rounds, setRounds] = useState(2)
  const [fillCoverage, setFillCoverage] = useState(true)
  const [dryRun, setDryRun] = useState(false)
  const [includeBusiness, setIncludeBusiness] = useState(false)
  const [tokenCap, setTokenCap] = useState(120000)
  const [values, setValues] = useState<Record<string, string>>({})

  const mutation = useMutation({
    mutationFn: () =>
      startJob(projectId, {
        endpointIds: [endpoint.id],
        type: "FULL",
        dryRun,
        confirmWrite,
        maxRepairRounds: rounds,
        pathParamValues: Object.fromEntries(Object.entries(values).filter(([, v]) => v.trim() !== "")),
        fillCoverage,
        includeBusiness,
        tokenCap,
      }),
    onSuccess: (job) => {
      track(job)
      onOpenChange(false)
    },
  })

  const targetUrl = project?.targetBaseUrl ?? null
  const canRun = !mutation.isPending && !!targetUrl && (!isWrite || confirmWrite)

  return (
    <AlertDialog open={open} onOpenChange={(next) => !mutation.isPending && onOpenChange(next)}>
      <AlertDialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-lg">
        <AlertDialogHeader>
          <AlertDialogTitle>
            Sinh đầy đủ: {endpoint.method} {endpoint.path}
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
          {isWrite && (
            <div role="note" className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-xs">
              <p className="font-medium text-destructive">Hệ thống sẽ GHI THẬT vào API đích ({endpoint.method})</p>
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
          <div className="flex flex-wrap items-center gap-3">
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
            <label className="flex items-center gap-2 text-xs">
              <Checkbox checked={fillCoverage} onCheckedChange={(c) => setFillCoverage(c === true)} />
              Tự bổ sung toàn bộ checklist sau khi xác nhận
            </label>
            <label
              className="flex items-center gap-2 text-xs"
              title="Sau khi checklist đủ, AI đoán thêm luật nghiệp vụ không có trong spec. Là GIẢ ĐỊNH, tốn thêm token, không tính vào độ bao phủ, cần bạn duyệt."
            >
              <Checkbox checked={includeBusiness} onCheckedChange={(c) => setIncludeBusiness(c === true)} />
              + AI bổ sung nghiệp vụ còn thiếu (GIẢ ĐỊNH)
            </label>
            <label className="flex items-center gap-2 text-xs" title="Chỉ liệt kê các bước sẽ làm, không gọi AI và không gọi API đích">
              <Checkbox checked={dryRun} onCheckedChange={(c) => setDryRun(c === true)} />
              Chạy thử (không gọi AI/API đích)
            </label>
          </div>
          <div className="flex flex-wrap items-center gap-2 text-xs">
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
          <p className="text-xs text-muted-foreground">
            Ước tính {(10000 * (1 + (includeBusiness ? 1 : 0))).toLocaleString("vi-VN")}–{(10000 * (1 + rounds + (includeBusiness ? 1 : 0))).toLocaleString("vi-VN")} token (mỗi lần gọi AI ~6.500–27.000).
            Không giới hạn thời gian: bước AI có thể kéo dài vài phút, bạn theo dõi từng bước và bấm Huỷ khi cần. Chạm trần token thì job dừng ở bước an toàn.
          </p>
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
