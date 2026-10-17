import { useCallback } from "react";
import type { ReactNode } from "react";
import toast, { Toaster } from "react-hot-toast";
import { ToastContext } from "./toast-context";
import type { PromiseToastOptions, ToastOptions } from "./toast-context";

function ToastCopy({ title, detail }: { title: string; detail?: string }) {
  return (
    <span className="hot-toast-copy">
      <strong>{title}</strong>
      {detail && <span>{detail}</span>}
    </span>
  );
}

function errorDetail(error: unknown) {
  return error instanceof Error ? error.message : "Please try again.";
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const showToast = useCallback((title: string, options: ToastOptions = {}) => {
    const message = <ToastCopy title={title} detail={options.detail} />;
    if (options.tone === "success") toast.success(message);
    else if (options.tone === "error") toast.error(message);
    else toast(message);
  }, []);

  const showPromiseToast = useCallback(
    <T,>(promise: Promise<T>, options: PromiseToastOptions) =>
      toast.promise(
        promise,
        {
          loading: <ToastCopy title={options.loading} />,
          success: <ToastCopy title={options.success} />,
          error: (error) => (
            <ToastCopy title={options.error} detail={errorDetail(error)} />
          ),
        },
        { style: { minWidth: "270px" } },
      ),
    [],
  );

  return (
    <ToastContext.Provider value={{ showToast, showPromiseToast }}>
      {children}
      <Toaster
        position="bottom-right"
        gutter={10}
        containerStyle={{ bottom: "var(--toast-bottom)", right: 12 }}
        toastOptions={{
          className: "app-hot-toast",
          duration: 5000,
          style: {
            maxWidth: "min(390px, calc(100vw - 24px))",
            background: "var(--toast-info-bg)",
            color: "var(--toast-info-text)",
            border: "1px solid var(--toast-info-border)",
          },
          success: {
            duration: 4000,
            style: {
              background: "var(--toast-success-bg)",
              color: "var(--toast-success-text)",
              border: "1px solid var(--toast-success-border)",
            },
            iconTheme: {
              primary: "var(--toast-success-icon)",
              secondary: "var(--toast-success-bg)",
            },
          },
          error: {
            duration: 7000,
            style: {
              background: "var(--toast-error-bg)",
              color: "var(--toast-error-text)",
              border: "1px solid var(--toast-error-border)",
            },
            iconTheme: {
              primary: "var(--toast-error-icon)",
              secondary: "var(--toast-error-bg)",
            },
            ariaProps: { role: "alert", "aria-live": "assertive" },
          },
          loading: {
            style: {
              background: "var(--toast-info-bg)",
              color: "var(--toast-info-text)",
              border: "1px solid var(--toast-info-border)",
            },
          },
        }}
      />
    </ToastContext.Provider>
  );
}
