import { useEffect, useState } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { ApiError } from "@/lib/api"
import { createTestCase, updateTestCase, type TestCase } from "@/lib/testcases"

interface TestCaseFormDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: string
  endpointId: string
  testCase?: TestCase | null
}

export function TestCaseFormDialog({
  open,
  onOpenChange,
  projectId,
  endpointId,
  testCase,
}: TestCaseFormDialogProps) {
  const queryClient = useQueryClient()
  const isEdit = Boolean(testCase)
  const [name, setName] = useState("")
  const [description, setDescription] = useState("")
  const [requestHeaders, setRequestHeaders] = useState("")
  const [requestBody, setRequestBody] = useState("")
  const [expectedStatus, setExpectedStatus] = useState("200")

  useEffect(() => {
    if (open) {
      setName(testCase?.name ?? "")
      setDescription(testCase?.description ?? "")
      setRequestHeaders(testCase?.requestHeaders ?? "")
      setRequestBody(testCase?.requestBody ?? "")
      setExpectedStatus(testCase ? String(testCase.expectedStatus) : "200")
    }
  }, [open, testCase])

  const mutation = useMutation({
    mutationFn: () => {
      const input = {
        name,
        description,
        requestHeaders,
        requestBody,
        expectedStatus: Number(expectedStatus),
      }
      return testCase
        ? updateTestCase(projectId, endpointId, testCase.id, input)
        : createTestCase(projectId, endpointId, input)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["test-cases", projectId] })
      queryClient.invalidateQueries({ queryKey: ["endpoints", projectId] })
      onOpenChange(false)
    },
  })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    mutation.mutate()
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <form onSubmit={handleSubmit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{isEdit ? "Sửa Test Case" : "Thêm Test Case"}</DialogTitle>
            <DialogDescription>
              {isEdit
                ? "Cập nhật thông tin test case"
                : "Nhập thông tin test case mới cho endpoint này"}
            </DialogDescription>
          </DialogHeader>

          <div className="flex flex-col gap-2">
            <Label htmlFor="test-case-name">Tên</Label>
            <Input
              id="test-case-name"
              required
              maxLength={255}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="test-case-description">Mô tả</Label>
            <Textarea
              id="test-case-description"
              maxLength={2000}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="test-case-expected-status">Mã trạng thái kỳ vọng</Label>
            <Input
              id="test-case-expected-status"
              type="number"
              required
              min={100}
              max={599}
              value={expectedStatus}
              onChange={(e) => setExpectedStatus(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="test-case-headers">Request Headers (JSON, tuỳ chọn)</Label>
            <Textarea
              id="test-case-headers"
              placeholder='{"Content-Type": "application/json"}'
              value={requestHeaders}
              onChange={(e) => setRequestHeaders(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="test-case-body">Request Body (JSON, tuỳ chọn)</Label>
            <Textarea
              id="test-case-body"
              value={requestBody}
              onChange={(e) => setRequestBody(e.target.value)}
            />
          </div>

          {mutation.isError && (
            <p className="text-sm text-destructive">
              {mutation.error instanceof ApiError
                ? mutation.error.message
                : "Đã xảy ra lỗi, vui lòng thử lại"}
            </p>
          )}

          <DialogFooter>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending
                ? "Đang lưu..."
                : isEdit
                  ? "Lưu thay đổi"
                  : "Thêm Test Case"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
