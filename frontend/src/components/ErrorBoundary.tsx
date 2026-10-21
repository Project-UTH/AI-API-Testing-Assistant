import { Component, type ErrorInfo, type ReactNode } from "react"
import { useLocation } from "react-router-dom"

import { Button } from "@/components/ui/button"

interface Props {
  children: ReactNode
}

interface State {
  error: Error | null
}

/** Bắt lỗi render để một component hỏng không làm trắng cả app (từng xảy ra khi thiếu 1 giá trị enum ở trang Test case). */
class ErrorBoundaryInner extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("Lỗi hiển thị:", error, info.componentStack)
  }

  render() {
    if (!this.state.error) return this.props.children
    return (
      <div role="alert" className="mx-auto mt-16 max-w-md rounded-lg border p-6 text-center">
        <h2 className="text-lg font-semibold">Trang này gặp lỗi khi hiển thị</h2>
        <p className="mt-2 text-sm text-muted-foreground">
          Dữ liệu của bạn không bị mất. Bạn thử tải lại trang hoặc quay về trang trước.
        </p>
        <pre className="mt-3 max-h-32 overflow-auto rounded bg-muted p-2 text-left text-xs">{this.state.error.message}</pre>
        <div className="mt-4 flex justify-center gap-2">
          <Button variant="outline" onClick={() => this.setState({ error: null })}>
            Thử lại
          </Button>
          <Button onClick={() => window.location.reload()}>Tải lại trang</Button>
        </div>
      </div>
    )
  }
}

/** Tự đặt lại khi đổi route (key theo đường dẫn) để lỗi ở trang này không dính sang trang khác. */
export function ErrorBoundary({ children }: Props) {
  const location = useLocation()
  return <ErrorBoundaryInner key={location.pathname}>{children}</ErrorBoundaryInner>
}
