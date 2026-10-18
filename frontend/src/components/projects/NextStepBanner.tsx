import { useState } from "react"
import { Link } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { X } from "lucide-react"

import { Button } from "@/components/ui/button"
import { getImportImpact, type ImportImpact, type TargetAuthType } from "@/lib/endpoints"

const TOTAL_STEPS = 5

interface NextStepBannerProps {
  projectId: string
  targetAuthType: TargetAuthType
  onOpenImport: () => void
  onOpenTargetAuth: () => void
}

interface Step {
  /** 1..5 - cũng là mốc lưu vào localStorage khi người dùng ẩn gợi ý. */
  number: number
  title: string
  description: string
  optional?: boolean
}

// localStorage chỉ là tiện ích theo từng người dùng - mọi truy cập đều bọc try/catch, hỏng thì banner vẫn hiện bình thường.
function readStorage(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

function writeStorage(key: string, value: string) {
  try {
    localStorage.setItem(key, value)
  } catch {
    // bỏ qua
  }
}

/**
 * Bước đầu tiên CHƯA xong của project, suy từ số liệu thật (cùng API đếm với cảnh báo Import) - không lưu tiến độ riêng.
 * Trả null khi không còn gợi ý nào (đã có kết quả chạy và bug report, hoặc người dùng đã bỏ qua).
 */
function resolveStep(impact: ImportImpact, authType: TargetAuthType, authSkipped: boolean): Step | null {
  if (impact.endpointCount === 0) {
    return {
      number: 1,
      title: "Nạp API của bạn",
      description: "Import OpenAPI/Swagger để hệ thống đọc danh sách endpoint.",
    }
  }
  if (authType === "NONE" && !authSkipped) {
    return {
      number: 2,
      title: "API đích có cần đăng nhập không?",
      description: "Nếu cần token, đặt xác thực để test chạy không bị lỗi 401.",
    }
  }
  if (impact.testCaseCount === 0) {
    return {
      number: 3,
      title: "Tạo test case",
      description: "Tick endpoint ở danh sách bên dưới rồi bấm Sinh Test Case.",
    }
  }
  if (impact.testResultCount === 0 && impact.executionCount === 0) {
    return {
      number: 4,
      title: "Chạy test",
      description: "Xem lại các test case, tick những test muốn chạy rồi bấm Chạy Test.",
    }
  }
  // Chỉ gợi ý bug report khi thật sự có test fail (toàn pass thì không có gì để báo).
  if (impact.failedResultCount > 0 && impact.bugReportCount === 0) {
    return {
      number: 5,
      title: "Tạo Bug Report từ test fail",
      description: `Có ${impact.failedResultCount} kết quả fail. Tạo bug report để theo dõi.`,
      optional: true,
    }
  }
  return null
}

export function NextStepBanner({ projectId, targetAuthType, onOpenImport, onOpenTargetAuth }: NextStepBannerProps) {
  const dismissedKey = `next-step-dismissed:${projectId}`
  const skipAuthKey = `skip-auth-step:${projectId}`
  const [dismissedStep, setDismissedStep] = useState(() => Number(readStorage(dismissedKey)) || 0)
  const [authSkipped, setAuthSkipped] = useState(() => readStorage(skipAuthKey) === "1")

  // Key riêng (không dùng chung "import-impact" của dialog Import: key đó bị invalidate/gcTime 0). Luôn tải lại khi tab
  // Endpoint được mở nên số liệu tươi mà không cần invalidate ở mọi nơi sinh/chạy/xoá. Lỗi tải = ẩn banner (không phá hoại gì).
  const { data: impact } = useQuery({
    queryKey: ["project-progress", projectId],
    queryFn: () => getImportImpact(projectId),
    staleTime: 0,
    refetchOnMount: "always",
  })

  if (!impact) return null
  const step = resolveStep(impact, targetAuthType, authSkipped)
  // Chỉ ẩn đúng bước đã bấm ẩn: sang bước khác (tiến hoặc lùi, vd xoá hết test case/import lại) thì hiện lại.
  if (!step || step.number === dismissedStep) return null

  function dismiss(current: Step) {
    writeStorage(dismissedKey, String(current.number))
    setDismissedStep(current.number)
  }

  function skipAuth() {
    writeStorage(skipAuthKey, "1")
    setAuthSkipped(true)
  }

  return (
    <div
      role="status"
      className="flex flex-wrap items-center gap-3 rounded-lg border border-primary/20 bg-primary/5 px-4 py-3"
    >
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium">
          <span className="mr-2 text-xs font-normal text-muted-foreground">
            Bước {step.number}/{TOTAL_STEPS}
            {step.optional ? " (tuỳ chọn)" : ""}
          </span>
          {step.title}
        </p>
        <p className="mt-0.5 text-sm text-muted-foreground">{step.description}</p>
      </div>

      <div className="flex items-center gap-2">
        {step.number === 1 && <Button size="sm" onClick={onOpenImport}>Import OpenAPI</Button>}
        {step.number === 2 && (
          <>
            <Button size="sm" onClick={onOpenTargetAuth}>Đặt xác thực</Button>
            <Button size="sm" variant="ghost" onClick={skipAuth}>API này không cần</Button>
          </>
        )}
        {step.number === 3 && (
          <Button size="sm" variant="ghost" nativeButton={false} render={<Link to={`/projects/${projectId}/test-cases`} />}>
            Hoặc thêm thủ công
          </Button>
        )}
        {step.number === 4 && (
          <Button size="sm" nativeButton={false} render={<Link to={`/projects/${projectId}/test-cases`} />}>
            Mở tab Test case
          </Button>
        )}
        {step.number === 5 && (
          <>
            <Button size="sm" nativeButton={false} render={<Link to={`/projects/${projectId}/bug-reports`} />}>
              Mở Bug Report
            </Button>
            <Button size="sm" variant="ghost" onClick={() => dismiss(step)}>Để sau</Button>
          </>
        )}
        {step.number !== 5 && step.number !== 2 && (
          <button
            type="button"
            aria-label="Ẩn gợi ý này"
            title="Ẩn gợi ý này"
            className="rounded-md p-1 text-muted-foreground hover:bg-muted hover:text-foreground"
            onClick={() => dismiss(step)}
          >
            <X className="h-4 w-4" />
          </button>
        )}
      </div>
    </div>
  )
}
