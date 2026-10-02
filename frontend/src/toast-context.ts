import { createContext, useContext } from 'react'

export type ToastTone = 'success' | 'info' | 'error'
export type ToastOptions = { detail?: string; tone?: ToastTone }
export type PromiseToastOptions = { loading: string; success: string; error: string }
export type ToastContextValue = {
  showToast: (title: string, options?: ToastOptions) => void
  showPromiseToast: <T>(promise: Promise<T>, options: PromiseToastOptions) => Promise<T>
}

export const ToastContext = createContext<ToastContextValue | null>(null)

export function useToast() {
  const context = useContext(ToastContext)
  if (!context) throw new Error('useToast must be used inside ToastProvider')
  return context.showToast
}

export function usePromiseToast() {
  const context = useContext(ToastContext)
  if (!context) throw new Error('usePromiseToast must be used inside ToastProvider')
  return context.showPromiseToast
}
