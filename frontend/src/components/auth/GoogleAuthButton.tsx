import { useState } from "react"
import { useNavigate } from "react-router-dom"
import { useMutation } from "@tanstack/react-query"
import { GoogleLogin } from "@react-oauth/google"

import { ApiError, setToken } from "@/lib/api"
import { loginWithGoogle } from "@/lib/auth"

export function GoogleAuthButton() {
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (idToken: string) => loginWithGoogle(idToken),
    onSuccess: (data) => {
      setToken(data.token)
      navigate("/", { replace: true })
    },
    onError: (err) => {
      setError(
        err instanceof ApiError ? err.message : "Đã xảy ra lỗi, vui lòng thử lại"
      )
    },
  })

  return (
    <div className="flex flex-col items-center gap-2">
      <GoogleLogin
        onSuccess={(credentialResponse) => {
          setError(null)
          if (credentialResponse.credential) {
            mutation.mutate(credentialResponse.credential)
          }
        }}
        onError={() => setError("Đăng nhập Google thất bại, vui lòng thử lại")}
      />
      {error && <p className="text-sm text-destructive">{error}</p>}
    </div>
  )
}
