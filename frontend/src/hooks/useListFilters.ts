import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { useSearchParams } from "react-router-dom"

export interface UseListFiltersOptions<K extends string> {
  /** Các bộ lọc dạng chọn (mỗi khoá là 1 tham số trên URL, "" = chưa lọc). Ô tìm kiếm luôn là `q`. */
  filterKeys: readonly K[]
  defaultPageSize?: number
  debounceMs?: number
}

export interface ListFilters<K extends string> {
  /** Giá trị để gắn vào <input> (đổi ngay khi gõ). */
  searchInput: string
  setSearchInput: (value: string) => void
  /** Từ khoá đã debounce (nằm trên URL) - dùng cho queryKey và gọi API. */
  q: string
  filters: Record<K, string>
  /** Đổi 1 bộ lọc: luôn đưa về trang đầu. */
  setFilter: (key: K, value: string) => void
  /** Trang tính từ 0 (URL ghi từ 1, bỏ khi là trang đầu). */
  page: number
  setPage: (page: number, options?: { replace?: boolean }) => void
  /** Gọi khi biết tổng số trang: nếu trang hiện tại vượt quá (vd xoá dòng cuối) thì lùi về trang cuối. */
  clampPage: (totalPages: number) => void
  size: number
  /** Số điều kiện đang lọc (tính cả ô tìm kiếm) - để hiện nút "Xoá bộ lọc". */
  activeCount: number
  reset: () => void
  /**
   * Gộp MỌI thay đổi URL cần làm cùng lúc vào ĐÚNG 1 lần cập nhật. react-router KHÔNG xếp hàng các hàm cập nhật của
   * setSearchParams: gọi 2 lần liền nhau thì lần sau đè lần trước. Cần đổi nhiều tham số (kể cả tham số ngoài danh sách bộ lọc)
   * thì dùng hàm này thay vì gọi setFilter/setPage/reset nối tiếp.
   */
  applyParams: (mutate: (params: URLSearchParams) => void, options?: { replace?: boolean }) => void
  /** Đối tượng ổn định cho queryKey / gọi API: {q, page, size, ...filters}. */
  params: { q: string; page: number; size: number } & Record<K, string>
}

function parsePage(raw: string | null): number {
  const value = Number.parseInt(raw ?? "", 10)
  // Chặn số quá lớn (server từ chối số ngoài khoảng int bằng 400 làm trang không tải được).
  return Number.isFinite(value) && value >= 1 ? Math.min(value - 1, 100000) : 0
}

/**
 * Bộ lọc + phân trang của danh sách, lấy URL làm nguồn sự thật duy nhất (tải lại trang, nút Back, chia sẻ link đều giữ nguyên).
 * Chỉ ô tìm kiếm có state cục bộ (để gõ mượt), ghi lên URL sau khi dừng gõ `debounceMs` ms.
 * Đổi URL bằng dạng hàm để KHÔNG làm mất các tham số khác không thuộc danh sách này (vd `endpoint`). Lưu ý: mỗi thao tác chỉ được
 * gọi setSearchParams MỘT lần (react-router không xếp hàng các hàm cập nhật) - cần gộp nhiều thay đổi thì dùng `applyParams`.
 */
export function useListFilters<K extends string>(options: UseListFiltersOptions<K>): ListFilters<K> {
  const { filterKeys, defaultPageSize = 50, debounceMs = 300 } = options
  const [searchParams, setSearchParams] = useSearchParams()
  const urlQ = searchParams.get("q") ?? ""
  const [searchInput, setSearchInput] = useState(urlQ)
  // Giá trị q do CHÍNH hook này vừa ghi lên URL - phân biệt với thay đổi từ ngoài (Back/Forward, reset).
  const lastWrittenQ = useRef(urlQ)

  useEffect(() => {
    if (urlQ !== lastWrittenQ.current) {
      lastWrittenQ.current = urlQ
      setSearchInput(urlQ)
    }
  }, [urlQ])

  useEffect(() => {
    const next = searchInput.trim()
    if (next === urlQ) return
    const timer = window.setTimeout(() => {
      lastWrittenQ.current = next
      setSearchParams(
        (prev) => {
          const params = new URLSearchParams(prev)
          if (next) params.set("q", next)
          else params.delete("q")
          params.delete("page")
          return params
        },
        { replace: true }
      )
    }, debounceMs)
    return () => window.clearTimeout(timer)
  }, [searchInput, urlQ, debounceMs, setSearchParams])

  const filterSignature = filterKeys.map((key) => `${key}=${searchParams.get(key) ?? ""}`).join("&")
  const filters = useMemo(() => {
    const result = {} as Record<K, string>
    for (const key of filterKeys) result[key] = searchParams.get(key) ?? ""
    return result
    // filterSignature đã gói đủ giá trị các khoá cần đọc
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterSignature])

  const page = parsePage(searchParams.get("page"))

  const setFilter = useCallback(
    (key: K, value: string) => {
      setSearchParams((prev) => {
        const params = new URLSearchParams(prev)
        if (value) params.set(key, value)
        else params.delete(key)
        params.delete("page")
        return params
      })
    },
    [setSearchParams]
  )

  const setPage = useCallback(
    (nextPage: number, pageOptions?: { replace?: boolean }) => {
      setSearchParams(
        (prev) => {
          const params = new URLSearchParams(prev)
          if (nextPage > 0) params.set("page", String(nextPage + 1))
          else params.delete("page")
          return params
        },
        { replace: pageOptions?.replace }
      )
    },
    [setSearchParams]
  )

  const applyParams = useCallback(
    (mutate: (params: URLSearchParams) => void, applyOptions?: { replace?: boolean }) => {
      setSearchParams(
        (prev) => {
          const params = new URLSearchParams(prev)
          mutate(params)
          return params
        },
        { replace: applyOptions?.replace }
      )
    },
    [setSearchParams]
  )

  const clampPage = useCallback(
    (totalPages: number) => {
      if (totalPages > 0 && page >= totalPages) setPage(totalPages - 1, { replace: true })
    },
    [page, setPage]
  )

  const reset = useCallback(() => {
    lastWrittenQ.current = ""
    setSearchInput("")
    setSearchParams((prev) => {
      const params = new URLSearchParams(prev)
      params.delete("q")
      params.delete("page")
      for (const key of filterKeys) params.delete(key)
      return params
    })
  }, [filterKeys, setSearchParams])

  const activeCount = (urlQ ? 1 : 0) + filterKeys.filter((key) => filters[key]).length

  const params = useMemo(
    () => ({ q: urlQ, page, size: defaultPageSize, ...filters }),
    [urlQ, page, defaultPageSize, filters]
  )

  return { searchInput, setSearchInput, q: urlQ, filters, setFilter, page, setPage, clampPage, size: defaultPageSize, activeCount, reset, applyParams, params }
}
