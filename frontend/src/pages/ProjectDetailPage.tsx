import { useState } from "react"
import { Link, useParams } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { ArrowLeft, Bug, History, KeyRound, ListChecks, Upload } from "lucide-react"

import { Button } from "@/components/ui/button"
import { EndpointList } from "@/components/projects/EndpointList"
import { ImportOpenApiDialog } from "@/components/projects/ImportOpenApiDialog"
import { TargetAuthDialog } from "@/components/projects/TargetAuthDialog"
import { getProject } from "@/lib/projects"

const TARGET_AUTH_LABEL: Record<string, string> = {
  NONE: "Không",
  API_KEY: "API Key",
  BEARER_TOKEN: "Bearer Token",
}

export function ProjectDetailPage() {
  const { id } = useParams<{ id: string }>()
  const [importOpen, setImportOpen] = useState(false)
  const [targetAuthOpen, setTargetAuthOpen] = useState(false)

  const { data: project, isLoading, isError } = useQuery({
    queryKey: ["projects", id],
    queryFn: () => getProject(id!),
    enabled: Boolean(id),
  })

  return (
    <div>
      <Button
        variant="ghost"
        size="sm"
        nativeButton={false}
        render={<Link to="/projects" />}
      >
        <ArrowLeft className="h-4 w-4" />
        Quay lại danh sách Project
      </Button>

      {isLoading && <p className="mt-6 text-muted-foreground">Đang tải...</p>}

      {isError && (
        <p className="mt-6 text-destructive">
          Không tìm thấy project hoặc bạn không có quyền truy cập.
        </p>
      )}

      {project && (
        <div className="mt-4">
          <h1 className="text-2xl font-semibold">{project.name}</h1>
          <p className="mt-2 text-muted-foreground">
            {project.description || "Không có mô tả"}
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            Tạo ngày {new Date(project.createdAt).toLocaleDateString("vi-VN")}
          </p>

          <div className="mt-4 flex flex-wrap items-center gap-2 rounded-md border border-border p-3 text-sm">
            <span className="text-muted-foreground">Target Base URL:</span>
            <span className="font-medium">{project.targetBaseUrl || "Chưa cấu hình"}</span>
            <span className="mx-1 text-muted-foreground">·</span>
            <span className="text-muted-foreground">Xác thực:</span>
            <span className="font-medium">{TARGET_AUTH_LABEL[project.targetAuthType]}</span>
            <Button
              size="sm"
              variant="outline"
              className="ml-auto"
              onClick={() => setTargetAuthOpen(true)}
            >
              <KeyRound className="h-4 w-4" />
              Sửa xác thực
            </Button>
          </div>

          <div className="mt-8 flex items-center justify-between">
            <h2 className="text-lg font-semibold">Endpoint</h2>
            <div className="flex items-center gap-2">
              <Button
                size="sm"
                variant="outline"
                nativeButton={false}
                render={<Link to={`/projects/${id}/history`} />}
              >
                <History className="h-4 w-4" />
                Lịch sử kiểm thử
              </Button>
              <Button
                size="sm"
                variant="outline"
                nativeButton={false}
                render={<Link to={`/projects/${id}/bug-reports`} />}
              >
                <Bug className="h-4 w-4" />
                Bug Report
              </Button>
              <Button
                size="sm"
                variant="outline"
                nativeButton={false}
                render={<Link to={`/projects/${id}/test-cases`} />}
              >
                <ListChecks className="h-4 w-4" />
                Xem tất cả Test Case
              </Button>
              <Button size="sm" onClick={() => setImportOpen(true)}>
                <Upload className="h-4 w-4" />
                Import OpenAPI
              </Button>
            </div>
          </div>

          <EndpointList projectId={id!} />

          <ImportOpenApiDialog
            open={importOpen}
            onOpenChange={setImportOpen}
            projectId={id!}
          />
          <TargetAuthDialog
            open={targetAuthOpen}
            onOpenChange={setTargetAuthOpen}
            projectId={id!}
            currentAuthType={project.targetAuthType}
          />
        </div>
      )}
    </div>
  )
}
