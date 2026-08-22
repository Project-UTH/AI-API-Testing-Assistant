import { Navigate, Outlet } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"

import { getCurrentUserInfo } from "@/lib/auth"

/**
 * Ngược lại RequireAdmin - Admin không dùng các trang dữ liệu cá nhân (Tổng quan/Project/Lịch
 * sử, đã bỏ khỏi Sidebar) nên nếu cố truy cập thẳng bằng URL cũng điều hướng sang "/admin".
 */
export function RequireNotAdmin() {
  const { data } = useQuery({
    queryKey: ["current-user-info"],
    queryFn: () => getCurrentUserInfo(),
  })

  if (data?.role === "ADMIN") {
    return <Navigate to="/admin" replace />
  }

  return <Outlet />
}
