import { Outlet } from "react-router-dom"
import { Sidebar } from "@/components/layout/Sidebar"
import { Header } from "@/components/layout/Header"
import { GenerationJobsProvider } from "@/components/generation/GenerationJobsProvider"

export function AppLayout() {
  return (
    <GenerationJobsProvider>
      <div className="flex min-h-svh">
        <Sidebar />
        <div className="flex min-w-0 flex-1 flex-col">
          <Header />
          <main className="flex-1 p-6">
            <Outlet />
          </main>
        </div>
      </div>
    </GenerationJobsProvider>
  )
}
