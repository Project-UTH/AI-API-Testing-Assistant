import { Link } from "react-router-dom"
import { AlertTriangle } from "lucide-react"

import { cn } from "@/lib/utils"
import type { FailureCause, FailureCauseCode, TestResult } from "@/lib/executions"

const CAUSE_STYLES: Record<FailureCauseCode, string> = {
  TEST_SETUP: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  ENVIRONMENT: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  AUTH_CONFIG: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  ASSERTION_FAILED: "bg-violet-500/10 text-violet-600 dark:text-violet-400",
  DATA_STATE: "bg-sky-500/10 text-sky-600 dark:text-sky-400",
  STABLE_FAILURE: "bg-destructive/10 text-destructive",
  UNDETERMINED: "bg-muted text-muted-foreground",
}

/** Gợi ý việc nên làm cho từng nguyên nhân - chỉ là gợi ý cho nghi ngờ, không phải kết luận. */
const CAUSE_HINT: Record<FailureCauseCode, string> = {
  TEST_SETUP: "Mở Sửa test case, kiểm tra tham số còn thiếu giá trị dự phòng hoặc liên kết dữ liệu.",
  ENVIRONMENT: "Kiểm tra hệ thống đích có đang chạy và tới được không (Target Base URL, mạng), rồi chạy lại.",
  AUTH_CONFIG: "Kiểm tra token của hệ thống đích ở nút \"Sửa xác thực\" của project (có thể đã hết hạn), rồi chạy lại.",
  ASSERTION_FAILED: "Mã trạng thái đã đúng - mở Sửa test case, kiểm tra assertion (đường dẫn trường, giá trị kỳ vọng) có khớp nội dung phản hồi bên dưới không.",
  DATA_STATE: "Dữ liệu/trạng thái của hệ thống đích có thể đã đổi (tài nguyên bị xoá, dữ liệu trùng) - kiểm tra rồi chạy lại.",
  STABLE_FAILURE: "Fail lặp lại nhiều lần: xem lại kỳ vọng của test hoặc điều tra API - hệ thống không phân biệt được 2 khả năng này.",
  UNDETERMINED: "Chạy lại vài lần để có thêm bằng chứng trước khi kết luận.",
}

/** Nhãn ngắn cạnh badge Fail/Lỗi - tooltip là bằng chứng đầy đủ. */
export function FailureCauseChip({ cause, className }: { cause: FailureCause; className?: string }) {
  return (
    <span
      className={cn("shrink-0 rounded-full px-2 py-0.5 text-xs font-medium", CAUSE_STYLES[cause.code], className)}
      title={cause.evidence.join("\n")}
    >
      {cause.label}
    </span>
  )
}

/** Khối chi tiết trong phần mở rộng của 1 kết quả: nguyên nhân nghi ngờ + bằng chứng + gợi ý. */
export function FailureCauseDetail({ result }: { result: TestResult }) {
  const cause = result.failureCause
  if (!cause) return null
  return (
    <div className="rounded-md border border-border bg-muted/30 p-2.5 text-xs">
      <p className="mb-1 flex flex-wrap items-center gap-2 font-medium text-muted-foreground">
        Nghi ngờ nguyên nhân
        <FailureCauseChip cause={cause} />
        {result.durationMs !== null && <span className="font-normal">· phản hồi sau {result.durationMs} ms</span>}
      </p>
      <ul className="flex flex-col gap-1 pl-3">
        {cause.evidence.map((line, index) => (
          <li key={index}>• {line}</li>
        ))}
      </ul>
      <p className="mt-1.5 text-muted-foreground">
        <span className="font-medium text-foreground">Gợi ý: </span>
        {CAUSE_HINT[cause.code]}
      </p>
      <p className="mt-1 text-[11px] text-muted-foreground">
        Đây là nghi ngờ dựa trên dấu hiệu quan sát được khi gọi API từ bên ngoài, không phải kết luận chắc chắn.
      </p>
    </div>
  )
}

/** Từ ngần này kết quả cùng 1 nguyên nhân "không phải do API" thì hiện banner gom lại. */
const BANNER_MIN_RESULTS = 3

const BANNER_TEXT: Partial<Record<FailureCauseCode, string>> = {
  AUTH_CONFIG: "cùng bị từ chối xác thực - nghi token của hệ thống đích sai hoặc hết hạn.",
  ENVIRONMENT: "cùng lỗi khi gọi hệ thống đích - nghi hệ thống đích không sẵn sàng hoặc không tới được.",
  TEST_SETUP: "không chạy được vì cấu hình test chưa đủ.",
}

/**
 * Banner đầu trang kết quả: khi nhiều test cùng fail vì 1 nguyên nhân KHÔNG phải do API, nói thẳng một lần thay vì
 * để người dùng tự đọc từng dòng Fail rồi điều tra từng test. Gom ở frontend từ nhãn backend đã chấm sẵn.
 */
export function FailureCauseBanner({ results, projectId }: { results: TestResult[]; projectId: string }) {
  const groups = new Map<FailureCauseCode, number>()
  for (const r of results) {
    if (r.failureCause?.notApiFault) {
      groups.set(r.failureCause.code, (groups.get(r.failureCause.code) ?? 0) + 1)
    }
  }
  const notable = [...groups.entries()].filter(([, count]) => count >= BANNER_MIN_RESULTS)
  if (notable.length === 0) return null

  return (
    <div className="mt-4 flex flex-col gap-1.5 rounded-md border border-amber-500/30 bg-amber-500/5 p-3 text-sm">
      {notable.map(([code, count]) => (
        <p key={code} className="flex flex-wrap items-start gap-2">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-600 dark:text-amber-400" />
          <span>
            <span className="font-semibold">
              {count}/{results.length} test
            </span>{" "}
            {BANNER_TEXT[code]} {CAUSE_HINT[code]}
            {code === "AUTH_CONFIG" && (
              <>
                {" "}
                <Link to={`/projects/${projectId}`} className="text-primary underline-offset-4 hover:underline">
                  Mở trang Project
                </Link>
              </>
            )}
          </span>
        </p>
      ))}
      <p className="text-xs text-muted-foreground">
        Các lượt fail này không bị tính là "API lệch kỳ vọng" ở trang Coverage và không làm tăng điểm rủi ro hay tự mở lại
        Bug Report.
      </p>
    </div>
  )
}
