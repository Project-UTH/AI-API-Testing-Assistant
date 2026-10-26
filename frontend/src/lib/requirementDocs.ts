import { apiFetch } from "@/lib/api"

/** 17q G3.3 - tài liệu nghiệp vụ (.md/.txt) làm nguồn cho AI trích mục. */
export interface RequirementDocument {
  id: string
  fileName: string
  sizeBytes: number
  lineCount: number
  active: boolean
  createdAt: string
  /** Cảnh báo chuỗi giống bí mật: chỉ có số dòng + mã quy tắc, không bao giờ có giá trị. */
  warnings: { line: number; rule: string }[]
}

export interface RequirementDocumentContent {
  id: string
  fileName: string
  content: string
}

export const MAX_DOC_BYTES = 200_000
export const MAX_DOCS = 5

export function listRequirementDocs(projectId: string): Promise<RequirementDocument[]> {
  return apiFetch<RequirementDocument[]>(`/projects/${projectId}/requirement-documents`)
}

export function uploadRequirementDoc(projectId: string, file: File): Promise<RequirementDocument> {
  const form = new FormData()
  form.append("file", file)
  return apiFetch<RequirementDocument>(`/projects/${projectId}/requirement-documents`, { method: "POST", body: form })
}

export function deleteRequirementDoc(projectId: string, documentId: string): Promise<void> {
  return apiFetch<void>(`/projects/${projectId}/requirement-documents/${documentId}`, { method: "DELETE" })
}

export function getRequirementDocContent(projectId: string, documentId: string): Promise<RequirementDocumentContent> {
  return apiFetch<RequirementDocumentContent>(`/projects/${projectId}/requirement-documents/${documentId}/content`)
}

/** Ước tính token một lần trích mục (khớp công thức backend: ký tự / 2,8 cộng 10%), chỉ để người dùng đặt ngân sách. */
export function estimateTokens(chars: number): number {
  return Math.ceil((chars * 1.1) / 2.8)
}
