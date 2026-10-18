import { useState } from "react"
import { useParams } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { KeyRound, Upload } from "lucide-react"

import { Button } from "@/components/ui/button"
import { EndpointList } from "@/components/projects/EndpointList"
import { ImportOpenApiDialog } from "@/components/projects/ImportOpenApiDialog"
import { NextStepBanner } from "@/components/projects/NextStepBanner"
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

  const { data: project } = useQuery({
    queryKey: ["projects", id],
    queryFn: () => getProject(id!),
    enabled: Boolean(id),
  })

  return (
    <div>
      {project && (
        <div>
          <NextStepBanner
            projectId={id!}
            targetAuthType={project.targetAuthType}
            onOpenImport={() => setImportOpen(true)}
            onOpenTargetAuth={() => setTargetAuthOpen(true)}
          />

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

          <div className="mt-4 flex justify-end">
            <Button size="sm" onClick={() => setImportOpen(true)}>
              <Upload className="h-4 w-4" />
              Import OpenAPI
            </Button>
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
