import { lazy, Suspense } from "react"
import { Navigate, Route, Routes } from "react-router-dom"

import { AppLayout } from "@/components/layout/AppLayout"
import { ProjectLayout } from "@/components/layout/ProjectLayout"
import { RequireAuth } from "@/components/RequireAuth"
import { RequireAdmin } from "@/components/RequireAdmin"
import { RequireNotAdmin } from "@/components/RequireNotAdmin"

// Mỗi trang là 1 chunk riêng: lần đầu vào app chỉ tải trang đang xem (bundle cũ 679KB gộp 1 file).
const DashboardPage = lazy(() => import("@/pages/DashboardPage").then((m) => ({ default: m.DashboardPage })))
const AdminDashboardPage = lazy(() => import("@/pages/AdminDashboardPage").then((m) => ({ default: m.AdminDashboardPage })))
const AdminUsersPage = lazy(() => import("@/pages/AdminUsersPage").then((m) => ({ default: m.AdminUsersPage })))
const AdminUserDetailPage = lazy(() => import("@/pages/AdminUserDetailPage").then((m) => ({ default: m.AdminUserDetailPage })))
const AdminProjectDataPage = lazy(() => import("@/pages/AdminProjectDataPage").then((m) => ({ default: m.AdminProjectDataPage })))
const AdminProjectBugReportsPage = lazy(() => import("@/pages/AdminProjectBugReportsPage").then((m) => ({ default: m.AdminProjectBugReportsPage })))
const AdminAuditLogPage = lazy(() => import("@/pages/AdminAuditLogPage").then((m) => ({ default: m.AdminAuditLogPage })))
const ProjectsPage = lazy(() => import("@/pages/ProjectsPage").then((m) => ({ default: m.ProjectsPage })))
const ProjectDetailPage = lazy(() => import("@/pages/ProjectDetailPage").then((m) => ({ default: m.ProjectDetailPage })))
const TestCasesPage = lazy(() => import("@/pages/TestCasesPage").then((m) => ({ default: m.TestCasesPage })))
const CoveragePage = lazy(() => import("@/pages/CoveragePage").then((m) => ({ default: m.CoveragePage })))
const CiIntegrationPage = lazy(() => import("@/pages/CiIntegrationPage").then((m) => ({ default: m.CiIntegrationPage })))
const ExecutionComparePage = lazy(() => import("@/pages/ExecutionComparePage").then((m) => ({ default: m.ExecutionComparePage })))
const TestExecutionPage = lazy(() => import("@/pages/TestExecutionPage").then((m) => ({ default: m.TestExecutionPage })))
const TestHistoryPage = lazy(() => import("@/pages/TestHistoryPage").then((m) => ({ default: m.TestHistoryPage })))
const GlobalHistoryPage = lazy(() => import("@/pages/GlobalHistoryPage").then((m) => ({ default: m.GlobalHistoryPage })))
const BugReportPage = lazy(() => import("@/pages/BugReportPage").then((m) => ({ default: m.BugReportPage })))
const LoginPage = lazy(() => import("@/pages/LoginPage").then((m) => ({ default: m.LoginPage })))
const RegisterPage = lazy(() => import("@/pages/RegisterPage").then((m) => ({ default: m.RegisterPage })))
const ForgotPasswordPage = lazy(() => import("@/pages/ForgotPasswordPage").then((m) => ({ default: m.ForgotPasswordPage })))
const ResetPasswordPage = lazy(() => import("@/pages/ResetPasswordPage").then((m) => ({ default: m.ResetPasswordPage })))

function App() {
  return (
    <Suspense fallback={<div className="p-6 text-sm text-muted-foreground">Đang tải...</div>}>
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route path="/forgot-password" element={<ForgotPasswordPage />} />
      <Route path="/reset-password" element={<ResetPasswordPage />} />

      <Route element={<RequireAuth />}>
        <Route element={<AppLayout />}>
          <Route element={<RequireNotAdmin />}>
            <Route index element={<DashboardPage />} />
            <Route path="/projects" element={<ProjectsPage />} />
            <Route path="/history" element={<GlobalHistoryPage />} />
            <Route path="/projects/:id" element={<ProjectLayout />}>
              <Route index element={<ProjectDetailPage />} />
              <Route path="test-cases" element={<TestCasesPage />} />
              <Route path="coverage" element={<CoveragePage />} />
              <Route path="history" element={<TestHistoryPage />} />
              <Route path="bug-reports" element={<BugReportPage />} />
              <Route path="ci" element={<CiIntegrationPage />} />
              <Route path="executions/:executionId" element={<TestExecutionPage />} />
              <Route path="executions/:executionId/compare" element={<ExecutionComparePage />} />
            </Route>
          </Route>

          <Route element={<RequireAdmin />}>
            <Route path="/admin" element={<AdminDashboardPage />} />
            <Route path="/admin/users" element={<AdminUsersPage />} />
            <Route path="/admin/users/:userId" element={<AdminUserDetailPage />} />
            <Route path="/admin/users/:userId/projects/:projectId" element={<AdminProjectDataPage />} />
            <Route path="/admin/users/:userId/projects/:projectId/bug-reports" element={<AdminProjectBugReportsPage />} />
            <Route path="/admin/audit-log" element={<AdminAuditLogPage />} />
          </Route>
        </Route>
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
    </Suspense>
  )
}

export default App
