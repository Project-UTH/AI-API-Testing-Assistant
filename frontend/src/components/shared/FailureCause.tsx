import { Link } from "react-router-dom"
import { AlertTriangle, ShieldAlert } from "lucide-react"

import { cn } from "@/lib/utils"
import type { FailureCause, FailureCauseCode, TestResult } from "@/lib/executions"

const CAUSE_STYLES: Record<FailureCauseCode, string> = {
  TEST_SETUP: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  ENVIRONMENT: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  AUTH_CONFIG: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  FLAKY: "bg-orange-500/10 text-orange-600 dark:text-orange-400",
  ASSERTION_FAILED: "bg-violet-500/10 text-violet-600 dark:text-violet-400",
  DATA_STATE: "bg-sky-500/10 text-sky-600 dark:text-sky-400",
  STABLE_FAILURE: "bg-destructive/10 text-destructive",
  RESOURCE_NOT_FOUND: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  EXPECTATION_MISMATCH: "bg-violet-500/10 text-violet-600 dark:text-violet-400",
  UNDETERMINED: "bg-muted text-muted-foreground",
  STATE_CONFLICT: "bg-sky-500/10 text-sky-600 dark:text-sky-400",
  AUTHZ_VIOLATION: "bg-destructive/10 text-destructive",
  TEST_DATA_SUSPECT: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  MASS_ASSIGNMENT: "bg-destructive/10 text-destructive",
  TOKEN_FORBIDDEN: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  API_WRONG_STATUS: "bg-destructive/10 text-destructive",
}

/** Gợi ý việc nên làm cho từng nguyên nhân - chỉ là gợi ý cho nghi ngờ, không phải kết luận. */
const CAUSE_HINT: Record<FailureCauseCode, string> = {
  TEST_SETUP: "Mở Sửa test case, kiểm tra tham số còn thiếu giá trị dự phòng hoặc liên kết dữ liệu.",
  ENVIRONMENT: "Kiểm tra hệ thống đích có đang chạy và tới được không (Target Base URL, mạng), rồi chạy lại.",
  AUTH_CONFIG: "Kiểm tra token của hệ thống đích ở nút \"Sửa xác thực\" của project (có thể đã hết hạn), rồi chạy lại.",
  FLAKY: "Đừng tin 1 lần chạy riêng lẻ của test này: kết quả đổi qua lại giữa Pass và Fail dù nội dung test không đổi.",
  ASSERTION_FAILED: "Mã trạng thái đã đúng - mở Sửa test case, kiểm tra assertion (đường dẫn trường, giá trị kỳ vọng) có khớp nội dung phản hồi bên dưới không.",
  DATA_STATE: "Dữ liệu/trạng thái của hệ thống đích có thể đã đổi (tài nguyên bị xoá, dữ liệu trùng) - kiểm tra rồi chạy lại.",
  STABLE_FAILURE: "Fail lặp lại nhiều lần: xem lại kỳ vọng của test hoặc điều tra API - hệ thống không phân biệt được 2 khả năng này.",
  RESOURCE_NOT_FOUND:
    "Test gọi bằng id dự phòng nên có thể id này không tồn tại. Mở Sửa test case: ở mục \"Phụ thuộc dữ liệu\" (Test Data Chaining) lấy id từ kết quả của 1 test POST tạo tài nguyên, hoặc đổi giá trị dự phòng thành id có thật.",
  EXPECTATION_MISMATCH:
    "Kết quả vẫn là Fail. API trả mã khác kỳ vọng nhưng cùng nhóm nghĩa (vd 401/403, 400/422): nếu đồng ý với cách API chọn mã thì sửa kỳ vọng của test.",
  UNDETERMINED: "Chạy lại vài lần để có thêm bằng chứng trước khi kết luận.",
  STATE_CONFLICT:
    "API từ chối vì trạng thái dữ liệu (409: trùng/đã tồn tại). Xoá hoặc đổi dữ liệu trên hệ thống đích rồi chạy lại, hoặc đặt test tạo ở đầu chuỗi để mỗi test dùng dữ liệu riêng.",
  AUTHZ_VIOLATION:
    "Kết quả lệch ma trận phân quyền: vai trò được khai là bị cấm nhưng gọi được (thiếu kiểm quyền), hoặc được phép nhưng bị từ chối. Kiểm tra API, hoặc sửa ô tương ứng trong ma trận nếu khai sai.",
  TEST_DATA_SUSPECT:
    "API từ chối đầu vào (400/422) và phản hồi nói dữ liệu không hợp lệ (vd ngày quá khứ, giá trị không có trong danh mục). Ưu tiên Sửa test case: đổi dữ liệu thành giá trị hợp lệ rồi chạy lại. Chỉ tạo Bug Report nếu đã chắc dữ liệu hợp lệ mà API vẫn từ chối.",
  MASS_ASSIGNMENT:
    "Phát hiện bảo mật: API nhận field không khai báo trong schema (giá trị đánh dấu xuất hiện lại trong phản hồi) hoặc lỗi 5xx khi nhận chúng. Kiểm tra whitelist/DTO ở backend, rồi xoá dữ liệu probe đã ghi trên hệ thống đích. Đây là lỗ hổng, không phải lỗi test.",
  API_WRONG_STATUS:
    "API trả 401 cho request đầu vào sai (kỳ vọng 400/422) dù token đã được API chấp nhận ở test khác. Nhiều khả năng đây là lỗi của API đích (xử lý lỗi đầu vào sai mã): nên tạo Bug Report. Không sửa kỳ vọng cho khớp 401.",
  TOKEN_FORBIDDEN:
    "API trả 403 (đã xác thực nhưng không đủ quyền) trong khi token mặc định dùng được ở test khác. Mở Sửa test case và chọn profile vai trò đúng cho endpoint này (nút Vai trò của project). Chỉ coi là lỗi API nếu chắc vai trò mặc định phải được phép.",
}

/** Nhãn ngắn cạnh badge Fail/Lỗi - tooltip là bằng chứng đầy đủ. */
export function FailureCauseChip({ cause, className }: { cause: FailureCause; className?: string }) {
  return (
    <span
      className={cn("shrink-0 rounded-full px-2 py-0.5 text-xs font-medium", CAUSE_STYLES[cause.code], className)}
      title={cause.evidence.join("\n")}
    >
      {cause.code === "MASS_ASSIGNMENT" && <ShieldAlert className="mr-1 inline size-3 align-[-1px]" aria-hidden />}
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
  RESOURCE_NOT_FOUND: "gọi bằng id dự phòng và nhận 404 - test chưa chạm tới logic cần kiểm.",
}

/**
 * Banner đầu trang kết quả: khi nhiều test cùng fail vì 1 nguyên nhân KHÔNG phải do API, nói thẳng một lần thay vì
 * để người dùng tự đọc từng dòng Fail rồi điều tra từng test. Gom ở frontend từ nhãn backend đã chấm sẵn.
 */
export function FailureCauseBanner({ results, projectId }: { results: TestResult[]; projectId: string }) {
  const counts: Partial<Record<FailureCauseCode, number>> = {}
  for (const r of results) {
    if (r.failureCause?.notApiFault) {
      counts[r.failureCause.code] = (counts[r.failureCause.code] ?? 0) + 1
    }
  }
  return <FailureCauseSummaryBanner counts={counts} total={results.length} projectId={projectId} />
}

/** Các nguyên nhân "không phải do API" (khớp FailureCause.notApiFault ở backend) - chỉ những mã này được gom vào banner. */
const NOT_API_FAULT_CODES: FailureCauseCode[] = ["TEST_SETUP", "ENVIRONMENT", "AUTH_CONFIG", "RESOURCE_NOT_FOUND"]

/**
 * Cùng banner nhưng nhận số đếm theo nguyên nhân tính sẵn trên TOÀN lượt chạy (server): khi kết quả được tải theo trang thì không
 * còn đếm được từ danh sách đang hiển thị.
 */
export function FailureCauseSummaryBanner({
  counts,
  total,
  projectId,
}: {
  counts: Partial<Record<FailureCauseCode, number>>
  total: number
  projectId: string
}) {
  const notable = NOT_API_FAULT_CODES.map((code) => [code, counts[code] ?? 0] as const).filter(([, count]) => count >= BANNER_MIN_RESULTS)
  if (notable.length === 0) return null

  return (
    <div className="mt-4 flex flex-col gap-1.5 rounded-md border border-amber-500/30 bg-amber-500/5 p-3 text-sm">
      {notable.map(([code, count]) => (
        <p key={code} className="flex flex-wrap items-start gap-2">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-600 dark:text-amber-400" />
          <span>
            <span className="font-semibold">
              {count}/{total} test
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
        Các lượt fail này không bị tính là "API lệch kỳ vọng" ở trang Coverage và không tự tạo hay mở lại Bug Report.
      </p>
    </div>
  )
}
