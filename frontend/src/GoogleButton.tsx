import { useEffect, useRef, useState } from "react";

type GoogleApi = {
  accounts: {
    id: {
      initialize: (options: {
        client_id: string;
        nonce: string;
        callback: (response: { credential: string }) => void;
      }) => void;
      renderButton: (
        element: HTMLElement,
        options: { theme: string; size: string; shape: string; text: string },
      ) => void;
    };
  };
};

declare global {
  interface Window {
    google?: GoogleApi;
  }
}

const clientId = import.meta.env.VITE_GOOGLE_CLIENT_ID;
let scriptPromise: Promise<void> | null = null;

function loadGoogleScript() {
  if (window.google) return Promise.resolve();
  if (!scriptPromise)
    scriptPromise = new Promise<void>((resolve, reject) => {
      const script = document.createElement("script");
      script.src = "https://accounts.google.com/gsi/client";
      script.async = true;
      script.onload = () => resolve();
      script.onerror = () => {
        scriptPromise = null;
        reject(new Error("Google sign-in could not load."));
      };
      document.head.appendChild(script);
    });
  return scriptPromise;
}

export default function GoogleButton({
  onCredential,
  mode,
}: {
  onCredential: (idToken: string) => void;
  mode: "login" | "enroll" | "link";
}) {
  const container = useRef<HTMLDivElement>(null);
  const callback = useRef(onCredential);
  const [error, setError] = useState("");
  callback.current = onCredential;

  useEffect(() => {
    if (!clientId) return;
    let cancelled = false;
    void (async () => {
      try {
        const response = await fetch("/api/v1/auth/google/options", {
          method: "POST",
          credentials: "same-origin",
        });
        if (!response.ok) throw new Error("Google sign-in is unavailable.");
        const { nonce } = (await response.json()) as { nonce: string };
        await loadGoogleScript();
        if (cancelled || !container.current || !window.google) return;
        window.google.accounts.id.initialize({
          client_id: clientId,
          nonce,
          callback: (response) => callback.current(response.credential),
        });
        window.google.accounts.id.renderButton(container.current, {
          theme: "outline",
          size: "large",
          shape: "rectangular",
          text: mode === "link" ? "continue_with" : "signin_with",
        });
      } catch (cause) {
        if (!cancelled)
          setError(
            cause instanceof Error
              ? cause.message
              : "Google sign-in is unavailable.",
          );
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [mode]);

  if (!clientId)
    return (
      <p className="auth-method-unavailable">
        Google sign-in needs a Google client ID.
      </p>
    );
  return (
    <div className="google-signin">
      <div ref={container} />
      {error && <p role="alert">{error}</p>}
    </div>
  );
}
