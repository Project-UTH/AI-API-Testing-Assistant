import { useCallback, useMemo, useState } from "react"
import { useParams } from "react-router-dom"
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Loader2, Plus, Sparkles, Wand2 } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { BusinessRuleFormDialog } from "@/components/rules/BusinessRuleFormDialog"
import { RejectRuleDialog } from "@/components/rules/RejectRuleDialog"
import { RuleItem } from "@/components/rules/RuleItem"
import { ApproveGuessDialog } from "@/components/rules/ApproveGuessDialog"
import { BatchApproveDialog } from "@/components/rules/BatchApproveDialog"
import { DocViewerDialog } from "@/components/rules/DocViewerDialog"
import { ExtractRulesPanel } from "@/components/rules/ExtractRulesPanel"
import { ListToolbar, type ListFilterSelect } from "@/components/shared/ListToolbar"
import { ListEmptyState } from "@/components/shared/ListEmptyState"
import { Pagination } from "@/components/shared/Pagination"
import { useListFilters } from "@/hooks/useListFilters"
import { ApiError } from "@/lib/api"
import {
  approveBusinessRule,
  approveBusinessRulesBatch,
  deleteBusinessRule,
  detectBusinessRules,
  generateRuleTests,
  listBusinessRules,
  RULE_SOURCE_DISPLAY,
  RULE_STATUS_DISPLAY,
  type BusinessRule,
  type GenerateRuleTestsResult,
  type RuleSource,
  type RuleStatus,
} from "@/lib/rules"

function errorMessage(error: unknown): string {
  return error instanceof ApiError ? error.message : "Đã xảy ra lỗi, vui lòng thử lại"
}

const FILTER_KEYS = ["status", "source", "endpointId"] as const
const PAGE_SIZE = 50

/**
 * Tab "Nghiệp vụ" (17q G3): luật nghiệp vụ theo endpoint - hệ thống đề xuất (cặp min/max), bạn tự nhập hoặc duyệt/loại; luật ĐÃ DUYỆT mới
 * được sinh test (tất định, 0 token). Danh sách nhóm theo endpoint; luật bị loại giữ lại (ẩn khi lọc) để đo độ chính xác.
 */
export function BusinessRulesPage() {
  const { id } = useParams<{ id: string }>()
  const projectId = id!
  const queryClient = useQueryClient()
  const lf = useListFilters({ filterKeys: FILTER_KEYS, defaultPageSize: PAGE_SIZE })
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<BusinessRule | null>(null)
  const [rejecting, setRejecting] = useState<BusinessRule | null>(null)
  const [conflictRule, setConflictRule] = useState<BusinessRule | null>(null)
  const [conflictNote, setConflictNote] = useState("")
  const [generated, setGenerated] = useState<GenerateRuleTestsResult | null>(null)
  const [detectedMessage, setDetectedMessage] = useState<string | null>(null)
  const [guessRule, setGuessRule] = useState<BusinessRule | null>(null)
  const [viewing, setViewing] = useState<{ documentId: string; quote: string; offset: number | null } | null>(null)
  const [batchOpen, setBatchOpen] = useState(false)
  const [batchMessage, setBatchMessage] = useState<string | null>(null)

  const { data, isLoading, isError, isFetching, isPlaceholderData } = useQuery({
    queryKey: ["rules", projectId, "page", lf.params],
    queryFn: () =>
      listBusinessRules(projectId, {
        page: lf.page,
        size: lf.size,
        status: lf.filters.status || undefined,
        source: lf.filters.source || undefined,
        endpointId: lf.filters.endpointId || undefined,
      }),
    placeholderData: keepPreviousData,
  })
  const { data: approvedPage } = useQuery({
    queryKey: ["rules", projectId, "approved-count"],
    queryFn: () => listBusinessRules(projectId, { page: 0, size: 1, status: "APPROVED" }),
  })
  const approvedCount = approvedPage?.totalElements ?? 0

  const invalidate = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: ["rules", projectId] })
    queryClient.invalidateQueries({ queryKey: ["project-coverage", projectId] })
    queryClient.invalidateQueries({ queryKey: ["test-cases", projectId] })
  }, [queryClient, projectId])

  const approve = useMutation({
    mutationFn: (input: { rule: BusinessRule; conflictNote?: string; expectedStatus?: number }) =>
      approveBusinessRule(projectId, input.rule.id, { conflictNote: input.conflictNote, expectedStatus: input.expectedStatus }),
    onSuccess: () => {
      setGuessRule(null)
      setConflictRule(null)
      setConflictNote("")
      invalidate()
    },
  })
  const batchApprove = useMutation({
    mutationFn: (ids: string[]) => approveBusinessRulesBatch(projectId, ids),
    onSuccess: (result) => {
      setBatchOpen(false)
      setBatchMessage(
        `Đã duyệt ${result.approvedIds.length} luật.` + (result.notApproved.length > 0 ? ` ${result.notApproved.length} luật không đủ điều kiện duyệt hàng loạt.` : "")
      )
      invalidate()
    },
  })
  // Luật có trích dẫn = nguồn tài liệu hoặc chỉ dẫn: lọc ở backend để không bị 200 luật GIẢ ĐỊNH/hệ thống đứng trước che mất.
  const { data: docPage } = useQuery({
    queryKey: ["rules", projectId, "cited-proposed", "DOC"],
    queryFn: () => listBusinessRules(projectId, { page: 0, size: 200, status: "PROPOSED", source: "DOC" }),
  })
  const { data: instructionPage } = useQuery({
    queryKey: ["rules", projectId, "cited-proposed", "INSTRUCTION"],
    queryFn: () => listBusinessRules(projectId, { page: 0, size: 200, status: "PROPOSED", source: "INSTRUCTION" }),
  })
  const citedProposed = useMemo(
    () => [...(docPage?.data ?? []), ...(instructionPage?.data ?? [])].filter((r) => r.quote !== null),
    [docPage, instructionPage]
  )
  const remove = useMutation({ mutationFn: (rule: BusinessRule) => deleteBusinessRule(projectId, rule.id), onSuccess: invalidate })
  const detect = useMutation({
    mutationFn: () => detectBusinessRules(projectId),
    onSuccess: (result) => {
      setDetectedMessage(
        result.proposedCount > 0
          ? `Hệ thống đề xuất ${result.proposedCount} luật mới (chờ duyệt).`
          : "Không có luật mới để đề xuất (các cặp field đã có luật hoặc đã bị loại)."
      )
      invalidate()
    },
  })
  const generate = useMutation({
    mutationFn: () => generateRuleTests(projectId),
    onSuccess: (result) => {
      setGenerated(result)
      invalidate()
    },
  })

  const rules = useMemo(() => data?.data ?? [], [data])
  const groups = useMemo(() => {
    const byKey = new Map<string, BusinessRule[]>()
    for (const rule of rules) {
      byKey.set(rule.endpointKey, [...(byKey.get(rule.endpointKey) ?? []), rule])
    }
    return [...byKey.entries()]
  }, [rules])

  const onApprove = useCallback(
    (rule: BusinessRule) => {
      if (rule.status === "CONFLICT") {
        setConflictRule(rule)
        return
      }
      if (rule.source === "INFERRED") {
        setGuessRule(rule)
        return
      }
      approve.mutate({ rule })
    },
    [approve]
  )
  const onViewQuote = useCallback((rule: BusinessRule) => {
    if (rule.documentId && rule.quote) setViewing({ documentId: rule.documentId, quote: rule.quote, offset: rule.quoteOffset })
  }, [])
  const onEdit = useCallback((rule: BusinessRule) => {
    setEditing(rule)
    setFormOpen(true)
  }, [])
  const onDelete = useCallback(
    (rule: BusinessRule) => {
      if (window.confirm(`Xoá luật "${rule.sentence}"?`)) remove.mutate(rule)
    },
    [remove]
  )

  const selects: ListFilterSelect[] = [
    {
      key: "status",
      label: "Lọc theo trạng thái luật",
      allLabel: "Mọi trạng thái",
      value: lf.filters.status,
      className: "sm:w-44",
      options: (Object.keys(RULE_STATUS_DISPLAY) as RuleStatus[]).map((value) => ({ value, label: RULE_STATUS_DISPLAY[value].label })),
    },
    {
      key: "source",
      label: "Lọc theo nguồn luật",
      allLabel: "Mọi nguồn",
      value: lf.filters.source,
      className: "sm:w-48",
      options: (Object.keys(RULE_SOURCE_DISPLAY) as RuleSource[]).map((value) => ({ value, label: RULE_SOURCE_DISPLAY[value].label })),
    },
  ]
  const error = [approve, batchApprove, remove, detect, generate].map((m) => m.error).find(Boolean)

  return (
    <div>
      <h2 className="text-lg font-semibold">Luật nghiệp vụ</h2>
      <p className="mt-1 text-sm text-muted-foreground">
        Luật "luật trước, test sau": bạn duyệt luật (và kỳ vọng), hệ thống sinh test từ luật đã duyệt - không dùng AI, không tốn token. Luật không có
        trong spec nên phải do người xác nhận.
      </p>

      <ExtractRulesPanel projectId={projectId} />

      <div className="mt-4 flex flex-wrap items-center gap-2">
        <Button
          type="button"
          size="sm"
          onClick={() => {
            setEditing(null)
            setFormOpen(true)
          }}
        >
          <Plus className="h-3.5 w-3.5" />
          Thêm luật
        </Button>
        <Button type="button" size="sm" variant="outline" disabled={detect.isPending} onClick={() => detect.mutate()}>
          {detect.isPending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Wand2 className="h-3.5 w-3.5" />}
          Hệ thống phát hiện luật
        </Button>
        <Button
          type="button"
          size="sm"
          variant="outline"
          disabled={citedProposed.length === 0}
          title={citedProposed.length === 0 ? "Không có luật có trích dẫn nào đang chờ duyệt" : "Đối chiếu từng luật với đoạn trích rồi duyệt một lượt"}
          onClick={() => setBatchOpen(true)}
        >
          Duyệt {citedProposed.length} luật có trích dẫn…
        </Button>
        <Button
          type="button"
          size="sm"
          variant="outline"
          className="ml-auto"
          disabled={approvedCount === 0 || generate.isPending}
          title={approvedCount === 0 ? "Chưa có luật nào được duyệt" : "Sinh tất định, 0 token; cần test gốc đã Pass"}
          onClick={() => generate.mutate()}
        >
          {generate.isPending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Sparkles className="h-3.5 w-3.5" />}
          Sinh test từ {approvedCount} luật đã duyệt
        </Button>
      </div>

      {batchMessage && (
        <p role="status" className="mt-3 rounded-md bg-muted p-2 text-sm">
          {batchMessage}
        </p>
      )}
      {detectedMessage && (
        <p role="status" className="mt-3 rounded-md bg-muted p-2 text-sm">
          {detectedMessage}
        </p>
      )}
      {error && (
        <p role="alert" className="mt-3 text-sm text-destructive">
          {error instanceof ApiError ? error.message : "Đã xảy ra lỗi, vui lòng thử lại"}
        </p>
      )}
      {generated && (
        <div role="status" className="mt-3 rounded-md border border-border p-3 text-sm">
          <p className="font-medium">Đã sinh {generated.createdCount} test từ luật nghiệp vụ.</p>
          {generated.notGenerated.length > 0 && (
            <ul className="mt-1.5 list-disc pl-5 text-xs text-muted-foreground">
              {generated.notGenerated.map((n) => (
                <li key={n.ruleId}>
                  <span className="text-foreground">{n.sentence}</span>: {n.reason}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      <div className="mt-4">
        <ListToolbar
          hideSearch
          searchValue=""
          onSearchChange={() => undefined}
          searchPlaceholder=""
          searchLabel=""
          selects={selects}
          onSelectChange={(key, value) => lf.setFilter(key as (typeof FILTER_KEYS)[number], value)}
          activeFilterCount={lf.activeCount}
          onReset={lf.reset}
        />
      </div>

      {isLoading && <p className="mt-6 text-muted-foreground">Đang tải luật...</p>}
      {isError && <p className="mt-6 text-destructive">Không tải được danh sách luật, vui lòng thử lại.</p>}

      {data && rules.length === 0 && (
        <ListEmptyState
          filtered={lf.activeCount > 0}
          emptyText='Chưa có luật nghiệp vụ nào. Bấm "Hệ thống phát hiện luật" để lấy đề xuất từ spec, hoặc "Thêm luật" để tự nhập.'
          filteredText="Không có luật nào khớp bộ lọc."
          onReset={lf.reset}
        />
      )}

      <div className={isPlaceholderData ? "mt-4 flex flex-col gap-5 opacity-60" : "mt-4 flex flex-col gap-5"}>
        {groups.map(([endpointKey, items]) => (
          <section key={endpointKey} aria-label={endpointKey}>
            <h3 className="mb-2 font-mono text-sm font-medium">{endpointKey}</h3>
            <ul className="flex flex-col gap-2">
              {items.map((rule) => (
                <RuleItem
                  key={rule.id}
                  rule={rule}
                  busy={isPlaceholderData || approve.isPending || remove.isPending}
                  onApprove={onApprove}
                  onEdit={onEdit}
                  onReject={setRejecting}
                  onDelete={onDelete}
                  onViewQuote={onViewQuote}
                />
              ))}
            </ul>
          </section>
        ))}
      </div>

      {data && data.totalPages > 1 && (
        <Pagination
          className="mt-4"
          page={data.page}
          totalPages={data.totalPages}
          totalElements={data.totalElements}
          shownCount={rules.length}
          size={data.size}
          onPageChange={(p) => lf.setPage(p)}
          isFetching={isFetching}
          itemLabel="luật"
        />
      )}

      <BusinessRuleFormDialog
        projectId={projectId}
        open={formOpen}
        onOpenChange={(open) => {
          setFormOpen(open)
          if (!open) setEditing(null)
        }}
        rule={editing}
      />
      <RejectRuleDialog projectId={projectId} rule={rejecting} onClose={() => setRejecting(null)} />
      <ApproveGuessDialog
        key={guessRule?.id ?? "none"}
        rule={guessRule}
        error={approve.error ? errorMessage(approve.error) : null}
        pending={approve.isPending}
        onCancel={() => setGuessRule(null)}
        onConfirm={(rule, expectedStatus) => approve.mutate({ rule, expectedStatus })}
      />
      <BatchApproveDialog
        open={batchOpen}
        rules={citedProposed}
        error={batchApprove.error ? errorMessage(batchApprove.error) : null}
        pending={batchApprove.isPending}
        onClose={() => setBatchOpen(false)}
        onConfirm={(ids) => batchApprove.mutate(ids)}
      />
      <DocViewerDialog projectId={projectId} target={viewing} onClose={() => setViewing(null)} />

      <Dialog open={conflictRule !== null} onOpenChange={(open) => !open && setConflictRule(null)}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Duyệt luật mâu thuẫn spec</DialogTitle>
            <DialogDescription>{conflictRule?.statusNote}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-1">
            <Label htmlFor="conflict-note" className="text-xs">
              Vì sao vẫn duyệt? (bắt buộc)
            </Label>
            <Textarea id="conflict-note" rows={3} value={conflictNote} onChange={(e) => setConflictNote(e.target.value)} />
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setConflictRule(null)}>
              Huỷ
            </Button>
            <Button
              type="button"
              disabled={!conflictNote.trim() || approve.isPending}
              onClick={() => conflictRule && approve.mutate({ rule: conflictRule, conflictNote })}
            >
              Duyệt
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
