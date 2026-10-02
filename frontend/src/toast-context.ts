import { createContext, useContext } from 'react'

export type ToastTone = 'success' | 'info' | 'error'
export type ToastOptions = { detail?: string; tone?: ToastTone }
export type ToastContextValue = { showToast: (title: string, options?: ToastOptions) => void }

export const ToastContext = createContext<ToastContextValue | null>(null)

export function useToast() {
  const context = useContext(ToastContext)
  if (!context) throw new Error('useToast must be used inside ToastProvider')
  return context.showToast
}

