import { useState } from "react"
import { useParams } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { KeyRound, Upload, Users } from "lucide-react"

import { Button } from "@/components/ui/button"
import { EndpointList } from "@/components/projects/EndpointList"
import { ImportOpenApiDialog } from "@/components/projects/ImportOpenApiDialog"
import { NextStepBanner } from "@/components/projects/NextStepBanner"
import { TargetAuthDialog } from "@/components/projects/TargetAuthDialog"
import { AuthProfilesDialog } from "@/components/projects/AuthProfilesDialog"
import { getProject } from "@/lib/projects"
import { AUTH_TYPE_LABEL } from "@/lib/authTypes"

const TARGET_AUTH_LABEL: Record<string, string> = AUTH_TYPE_LABEL

export function ProjectDetailPage() {
  const { id } = useParams<{ id: string }>()
  const [importOpen, setImportOpen] = useState(false)
  const [targetAuthOpen, setTargetAuthOpen] = useState(false)
  const [profilesOpen, setProfilesOpen] = useState(false)

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
            <div className="ml-auto flex items-center gap-2">
              <Button size="sm" variant="outline" onClick={() => setTargetAuthOpen(true)}>
                <KeyRound className="h-4 w-4" />
                Sửa xác thực
              </Button>
              <Button size="sm" variant="outline" onClick={() => setProfilesOpen(true)}>
                <Users className="h-4 w-4" />
                Vai trò
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
          <AuthProfilesDialog open={profilesOpen} onOpenChange={setProfilesOpen} projectId={id!} />
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
