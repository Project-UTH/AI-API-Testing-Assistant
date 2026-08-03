import { Navigate, Route, Routes } from "react-router-dom"

import { AppLayout } from "@/components/layout/AppLayout"
import { RequireAuth } from "@/components/RequireAuth"
import { DashboardPage } from "@/pages/DashboardPage"
import { ProjectsPage } from "@/pages/ProjectsPage"
import { LoginPage } from "@/pages/LoginPage"
import { RegisterPage } from "@/pages/RegisterPage"

function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />

      <Route element={<RequireAuth />}>
        <Route element={<AppLayout />}>
          <Route index element={<DashboardPage />} />
          <Route path="/projects" element={<ProjectsPage />} />
        </Route>
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

export default App
