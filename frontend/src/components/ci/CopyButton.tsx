import { useState } from "react"
import { Check, Copy } from "lucide-react"

import { Button } from "@/components/ui/button"

/** Sao chép văn bản; fallback textarea ẩn khi `navigator.clipboard` không có (http không phải localhost). */
async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // rơi xuống fallback
  }
  try {
    const area = document.createElement("textarea")
    area.value = text
    area.setAttribute("readonly", "")
    area.style.position = "fixed"
    area.style.opacity = "0"
    document.body.appendChild(area)
    area.select()
    const ok = document.execCommand("copy")
    document.body.removeChild(area)
    return ok
  } catch {
    return false
  }
}

export function CopyButton({ text, label = "Sao chép" }: { text: string; label?: string }) {
  const [state, setState] = useState<"idle" | "copied" | "failed">("idle")

  async function handleCopy() {
    const ok = await copyText(text)
    setState(ok ? "copied" : "failed")
    window.setTimeout(() => setState("idle"), 2000)
  }

  return (
    <Button type="button" size="sm" variant="outline" onClick={handleCopy}>
      {state === "copied" ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
      <span aria-live="polite">
        {state === "copied" ? "Đã sao chép" : state === "failed" ? "Hãy bôi đen và copy tay" : label}
      </span>
    </Button>
  )
}
