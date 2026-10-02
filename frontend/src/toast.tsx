import { useCallback, useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { Check, Info, TriangleAlert, X } from 'lucide-react'
import { ToastContext } from './toast-context'
import type { ToastOptions, ToastTone } from './toast-context'

type Toast = { id: number; title: string; detail?: string; tone: ToastTone }

function ToastItem({ toast, dismiss }: { toast: Toast; dismiss: (id: number) => void }) {
  useEffect(() => {
    const timer = window.setTimeout(() => dismiss(toast.id), 5000)
    return () => window.clearTimeout(timer)
  }, [dismiss, toast.id])

  const Icon = toast.tone === 'success' ? Check : toast.tone === 'error' ? TriangleAlert : Info
  return <div className={`toast toast-${toast.tone}`} role={toast.tone === 'error' ? 'alert' : 'status'}>
    <span className="toast-icon"><Icon size={19} aria-hidden="true" /></span>
    <div className="toast-copy"><strong>{toast.title}</strong>{toast.detail && <span>{toast.detail}</span>}</div>
    <button type="button" className="toast-close" aria-label={`Dismiss ${toast.title}`} onClick={() => dismiss(toast.id)}><X size={17} /></button>
  </div>
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])
  const nextId = useRef(0)
  const dismiss = useCallback((id: number) => setToasts(current => current.filter(toast => toast.id !== id)), [])
  const showToast = useCallback((title: string, options: ToastOptions = {}) => {
    const toast: Toast = { id: ++nextId.current, title, detail: options.detail, tone: options.tone ?? 'info' }
    setToasts(current => [...current.slice(-2), toast])
  }, [])

  return <ToastContext.Provider value={{ showToast }}>
    {children}
    <div className="toast-stack" aria-label="Notifications">{toasts.map(toast => <ToastItem key={toast.id} toast={toast} dismiss={dismiss} />)}</div>
  </ToastContext.Provider>
}
