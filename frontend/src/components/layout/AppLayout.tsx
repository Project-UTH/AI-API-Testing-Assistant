import { Outlet } from "react-router-dom"
import { ErrorBoundary } from "@/components/ErrorBoundary"
import { Sidebar } from "@/components/layout/Sidebar"
import { Header } from "@/components/layout/Header"
import { useSidebarOpen } from "@/hooks/useSidebarOpen"

export function AppLayout() {
  const [sidebarOpen, toggleSidebar] = useSidebarOpen()
  return (
    <>
      <div className="flex min-h-svh">
        {sidebarOpen && <Sidebar />}
        <div className="flex min-w-0 flex-1 flex-col">
          <Header sidebarOpen={sidebarOpen} onToggleSidebar={toggleSidebar} />
          <main className="flex-1 p-6">
            <ErrorBoundary>
              <Outlet />
            </ErrorBoundary>
          </main>
        </div>
      </div>
    </>
  )
}
