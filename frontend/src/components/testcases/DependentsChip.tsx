import { useMemo, useState } from "react"
import { Link } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { Link2 } from "lucide-react"

import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { listTestCaseDependents } from "@/lib/testcases"

/**
 * "Được N test dùng": test case này đang là NGUỒN dữ liệu (vd id tài nguyên vừa tạo) cho N test khác. Bấm để xem danh sách. Test nguồn Fail thì các test đó bị chặn;
 * xoá test nguồn phải gỡ phụ thuộc ở các test này trước. Dữ liệu cả project tải 1 lần và dùng chung cho mọi dòng.
 */
export function DependentsChip({ projectId, testCaseId, testCaseName }: { projectId: string; testCaseId: string; testCaseName: string }) {
  const [open, setOpen] = useState(false)
  const { data = [] } = useQuery({ queryKey: ["test-case-dependents", projectId], queryFn: () => listTestCaseDependents(projectId), staleTime: 30_000 })
  const mine = useMemo(() => data.filter((d) => d.sourceTestCaseId === testCaseId), [data, testCaseId])
  const distinct = useMemo(() => [...new Map(mine.map((d) => [d.dependentTestCaseId, d])).values()], [mine])
  if (distinct.length === 0) return null
  return (
    <>
      <button
        type="button"
        onClick={(e) => {
          e.stopPropagation()
          setOpen(true)
        }}
        className="inline-flex shrink-0 items-center gap-1 rounded-full bg-violet-500/10 px-2 py-0.5 text-xs font-medium text-violet-700 hover:bg-violet-500/15 dark:text-violet-300"
        title="Test này là nguồn dữ liệu cho các test khác: bấm để xem"
      >
        <Link2 className="size-3" aria-hidden />
        Được {distinct.length} test dùng
      </button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="sm:max-w-xl">
          <DialogHeader>
            <DialogTitle>Test nào đang phụ thuộc test này</DialogTitle>
            <DialogDescription>
              "{testCaseName}" là nguồn dữ liệu cho {distinct.length} test dưới đây (chúng lấy giá trị từ phản hồi của test này). Nếu test này Fail, các test đó bị chặn. Muốn xoá test này,
              hãy gỡ phụ thuộc hoặc xoá các test đó trước.
            </DialogDescription>
          </DialogHeader>
          <ul className="flex max-h-[50vh] flex-col gap-2 overflow-y-auto text-sm">
            {distinct.map((d) => (
              <li key={d.dependentTestCaseId} className="rounded-md border border-border p-2">
                <p className="font-medium">{d.dependentTestCaseName}</p>
                <p className="font-mono text-xs text-muted-foreground">{d.dependentEndpointLabel}</p>
                <p className="mt-0.5 text-xs text-muted-foreground">
                  lấy <span className="font-mono">{"{" + d.placeholderName + "}"}</span> từ <span className="font-mono">{d.jsonPath}</span>
                </p>
                <Link
                  to={`/projects/${projectId}/test-cases?endpointId=${d.dependentEndpointId}`}
                  className="mt-1 inline-block text-xs text-primary underline underline-offset-4"
                  onClick={() => setOpen(false)}
                >
                  Mở các test của endpoint đó
                </Link>
              </li>
            ))}
          </ul>
        </DialogContent>
      </Dialog>
    </>
  )
}
