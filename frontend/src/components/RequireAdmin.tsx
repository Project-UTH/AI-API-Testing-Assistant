import { Navigate, Outlet } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"

import { getCurrentUserInfo } from "@/lib/auth"

/**
 * Lồng bên trong RequireAuth (đã xác nhận có token hợp lệ) - guard này chỉ thêm điều kiện role.
 * Luôn gọi /auth/me thay vì đọc role từ đâu đó lưu sẵn ở client, để không hiện nhầm mục Quản trị
 * cho 1 phiên đăng nhập cũ nếu role vừa bị thu hồi qua SQL trực tiếp (xem skill api-contract 4d).
 */
export function RequireAdmin() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["current-user-info"],
    queryFn: () => getCurrentUserInfo(),
  })

  if (isLoading) {
    return <div className="p-6 text-sm text-muted-foreground">Đang kiểm tra quyền truy cập...</div>
  }

  if (isError || data?.role !== "ADMIN") {
    return <Navigate to="/" replace />
  }

  return <Outlet />
}
