import { useQuery } from "@tanstack/react-query"

import { cn } from "@/lib/utils"
import { listEndpoints } from "@/lib/endpoints"

interface EndpointListProps {
  projectId: string
}

const METHOD_STYLES: Record<string, string> = {
  GET: "bg-blue-500/10 text-blue-600 dark:text-blue-400",
  POST: "bg-green-500/10 text-green-600 dark:text-green-400",
  PUT: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  PATCH: "bg-purple-500/10 text-purple-600 dark:text-purple-400",
  DELETE: "bg-red-500/10 text-red-600 dark:text-red-400",
}

export function EndpointList({ projectId }: EndpointListProps) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["endpoints", projectId],
    queryFn: () => listEndpoints(projectId),
  })

  const endpoints = data?.data ?? []

  if (isLoading) {
    return <p className="mt-4 text-muted-foreground">Đang tải danh sách endpoint...</p>
  }

  if (isError) {
    return (
      <p className="mt-4 text-destructive">
        Không tải được danh sách endpoint, vui lòng thử lại.
      </p>
    )
  }

  if (endpoints.length === 0) {
    return (
      <div className="mt-4 rounded-lg border border-dashed border-border p-6 text-center text-muted-foreground">
        Chưa có endpoint nào. Bấm "Import OpenAPI" để nạp định nghĩa API.
      </div>
    )
  }

  return (
    <ul className="mt-4 flex flex-col gap-2">
      {endpoints.map((endpoint) => (
        <li
          key={endpoint.id}
          className="flex items-center gap-3 rounded-lg border border-border p-3"
        >
          <span
            className={cn(
              "w-16 shrink-0 rounded-md px-2 py-0.5 text-center text-xs font-semibold",
              METHOD_STYLES[endpoint.method] ?? "bg-muted text-muted-foreground"
            )}
          >
            {endpoint.method}
          </span>
          <span className="min-w-0 flex-1 truncate font-mono text-sm">{endpoint.path}</span>
          {endpoint.summary && (
            <span className="shrink-0 truncate text-sm text-muted-foreground">
              {endpoint.summary}
            </span>
          )}
        </li>
      ))}
    </ul>
  )
}
