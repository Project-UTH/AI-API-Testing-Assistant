
/** Tên tham số đường dẫn OpenAPI, vd "/jobs/{jobId}/rubric/{criterionId}" -> ["jobId", "criterionId"]. */
export function pathParamNames(path: string): string[] {
  return Array.from(path.matchAll(/\{(\w+)\}/g)).map((m) => m[1])
}

export const WRITE_METHODS = ["POST", "PUT", "PATCH", "DELETE"]
