import { useCallback, useState } from "react"

const STORAGE_KEY = "sidebar-open"

// localStorage chỉ là tiện ích theo từng người dùng: bị chặn/đầy/chế độ riêng tư thì vẫn dùng được (mặc định mở), không chặn giao diện.
function readInitial(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) !== "0"
  } catch {
    return true
  }
}

/** Trạng thái ẩn/hiện thanh bên trái, nhớ lựa chọn của người dùng cho lần mở sau. */
export function useSidebarOpen(): [boolean, () => void] {
  const [open, setOpen] = useState<boolean>(readInitial)

  const toggle = useCallback(() => {
    setOpen((current) => {
      const next = !current
      try {
        localStorage.setItem(STORAGE_KEY, next ? "1" : "0")
      } catch {
        // bỏ qua: chỉ mất việc nhớ lựa chọn
      }
      return next
    })
  }, [])

  return [open, toggle]
}
