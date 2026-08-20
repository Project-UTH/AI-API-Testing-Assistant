import { Navigate, Route, Routes } from "react-router-dom"

import { AppLayout } from "@/components/layout/AppLayout"
import { RequireAuth } from "@/components/RequireAuth"
import { DashboardPage } from "@/pages/DashboardPage"
import { ProjectsPage } from "@/pages/ProjectsPage"
import { ProjectDetailPage } from "@/pages/ProjectDetailPage"
import { TestCasesPage } from "@/pages/TestCasesPage"
import { TestExecutionPage } from "@/pages/TestExecutionPage"
import { TestHistoryPage } from "@/pages/TestHistoryPage"
import { GlobalHistoryPage } from "@/pages/GlobalHistoryPage"
import { BugReportPage } from "@/pages/BugReportPage"
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
          <Route path="/history" element={<GlobalHistoryPage />} />
          <Route path="/projects/:id" element={<ProjectDetailPage />} />
          <Route path="/projects/:id/test-cases" element={<TestCasesPage />} />
          <Route path="/projects/:id/history" element={<TestHistoryPage />} />
          <Route path="/projects/:id/bug-reports" element={<BugReportPage />} />
          <Route path="/projects/:id/executions/:executionId" element={<TestExecutionPage />} />
        </Route>
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

export default App
