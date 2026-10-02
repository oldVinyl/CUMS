export type AuthStage = 'AUTHENTICATED' | 'PASSWORD_CHANGE' | 'TOTP_SETUP' | 'TOTP_VERIFY'
export type AuthStep = { stage: AuthStage; challenge: string | null; secret: string | null; recoveryCodes: string[] | null }
export type Profile = { userId: string; email: string }

export async function authPost(path: string, body?: object): Promise<AuthStep> {
  const response = await fetch(`/api/v1/auth/${path}`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body ?? {}),
  })
  if (!response.ok) {
    const error = await response.json().catch(() => null) as { message?: string } | null
    throw new Error(error?.message ?? 'Could not complete authentication. Please try again.')
  }
  return response.json() as Promise<AuthStep>
}

export async function currentProfile(): Promise<Profile | null> {
  const response = await fetch('/api/v1/auth/me', { credentials: 'same-origin' })
  return response.ok ? response.json() as Promise<Profile> : null
}

function csrfCookie() {
  return document.cookie.split('; ').find(cookie => cookie.startsWith('cu_csrf='))?.split('=')[1] ?? ''
}

export async function logout(allDevices = false) {
  const response = await fetch(`/api/v1/auth/${allDevices ? 'logout-all' : 'logout'}`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'X-CSRF-Token': csrfCookie() },
  })
  if (!response.ok) throw new Error('Could not sign out. Please try again.')
}
