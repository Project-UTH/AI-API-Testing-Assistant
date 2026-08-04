import type { ReactNode } from "react"
import {
  CheckCircle2,
  FileCode2,
  PlayCircle,
  Sparkles,
  Terminal,
  XCircle,
} from "lucide-react"

import { ModeToggle } from "@/components/mode-toggle"

const FEATURES = [
  { icon: FileCode2, label: "Import & parse OpenAPI" },
  { icon: Sparkles, label: "AI sinh test case" },
  { icon: PlayCircle, label: "Thực thi & phân tích kết quả" },
]

function BrandMark({ className }: { className?: string }) {
  return (
    <div className={"flex items-center gap-2 " + (className ?? "")}>
      <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md border border-border bg-muted">
        <Terminal className="h-4 w-4 text-foreground" />
      </div>
      <span className="font-mono text-xs uppercase tracking-[0.2em] text-muted-foreground">
        AI API Testing Assistant
      </span>
    </div>
  )
}

export function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="relative flex min-h-svh flex-col overflow-hidden bg-background lg:flex-row">
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 opacity-[0.06]"
        style={{
          backgroundImage:
            "radial-gradient(circle, var(--foreground) 1px, transparent 1px)",
          backgroundSize: "24px 24px",
        }}
      />
      <div
        aria-hidden
        className="pointer-events-none absolute -top-24 -left-24 h-[28rem] w-[28rem] rounded-full bg-success/15 blur-3xl"
      />
      <div
        aria-hidden
        className="pointer-events-none absolute -right-24 -bottom-24 h-[28rem] w-[28rem] rounded-full bg-foreground/5 blur-3xl"
      />

      <div className="absolute right-4 top-4 z-20 lg:right-6 lg:top-6">
        <ModeToggle />
      </div>

      <div className="relative z-10 hidden lg:flex lg:w-1/2 lg:flex-col lg:p-12 xl:p-16">
        <div className="flex h-full max-w-3xl flex-col justify-between">
          <BrandMark />

          <div className="flex flex-col gap-6">
            <h1 className="text-4xl leading-[1.1] font-bold tracking-tight text-foreground xl:text-5xl">
              Sinh test case bằng AI.
              <br />
              Thực thi trong vài giây.
            </h1>
            <p className="text-sm leading-relaxed text-muted-foreground">
              Import OpenAPI/Swagger, để AI tự sinh test case, tự tay review
              lại trước khi chạy — rồi xem kết quả pass/fail ngay trên
              dashboard.
            </p>

            <div className="w-full overflow-hidden rounded-xl border border-border bg-card/70 font-mono text-xs backdrop-blur-sm">
              <div className="border-b border-border px-4 py-2 text-muted-foreground">
                test-run.log
              </div>
              <div className="flex flex-col gap-2 p-4">
                <p className="text-muted-foreground">
                  $ import openapi.yaml
                </p>
                <p className="flex items-center gap-2 text-success">
                  <CheckCircle2 className="h-3.5 w-3.5 shrink-0" />
                  24 endpoints parsed
                </p>
                <p className="text-muted-foreground">
                  $ ai generate --endpoint /users
                </p>
                <p className="flex items-center gap-2 text-success">
                  <CheckCircle2 className="h-3.5 w-3.5 shrink-0" />
                  12 test case đã sinh
                </p>
                <p className="text-muted-foreground">$ run tests</p>
                <p className="flex items-center gap-2 text-success">
                  <CheckCircle2 className="h-3.5 w-3.5 shrink-0" />
                  11 passed
                </p>
                <p className="flex items-center gap-2 text-destructive">
                  <XCircle className="h-3.5 w-3.5 shrink-0" />1 failed
                </p>
              </div>
            </div>
          </div>

          <div className="flex flex-wrap gap-x-6 gap-y-3">
            {FEATURES.map(({ icon: Icon, label }) => (
              <div
                key={label}
                className="flex items-center gap-2 text-xs text-muted-foreground"
              >
                <Icon className="h-4 w-4 text-muted-foreground" />
                {label}
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="relative z-10 flex flex-1 flex-col items-center justify-center p-6 sm:p-10">
        <BrandMark className="mb-8 lg:hidden" />
        <div className="relative w-full max-w-sm">
          <div
            aria-hidden
            className="pointer-events-none absolute -inset-6 -z-10 rounded-[2rem] bg-success/10 blur-2xl"
          />
          {children}
        </div>
      </div>
    </div>
  )
}
