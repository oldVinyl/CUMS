import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Landmark, LockKeyhole } from "lucide-react";
import { usePromiseToast } from "./toast-context";
export default function ResetPasswordPage() {
  const [token, setToken] = useState(
      () => new URLSearchParams(window.location.hash.slice(1)).get("token") ?? "",
    ),
    [email, setEmail] = useState(""),
    [password, setPassword] = useState(""),
    [confirm, setConfirm] = useState(""),
    [busy, setBusy] = useState(false),
    [done, setDone] = useState(false);
  const toast = usePromiseToast();
  useEffect(() => {
    if (window.location.hash) window.history.replaceState(null, "", window.location.pathname);
  }, []);
  async function submit() {
    if (busy) return;
    setBusy(true);
    try {
      await toast(
        (async () => {
          const response = await fetch(
            `/api/v1/auth/password-reset/${token ? "complete" : "request"}`,
            {
              method: "POST",
              credentials: "same-origin",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify(token ? { token, password } : { email }),
            },
          );
          if (!response.ok) {
            const error = await response.json().catch(() => null);
            throw new Error(error?.message ?? "Recovery could not be completed.");
          }
        })(),
        {
          loading: token ? "Updating password…" : "Requesting recovery email…",
          success: token ? "Password updated" : "Recovery request received",
          error: "Could not complete recovery",
        },
      );
      setDone(true);
      setPassword("");
      setConfirm("");
      setToken("");
    } catch {
      /* Promise feedback includes server detail. */
    } finally {
      setBusy(false);
    }
  }
  return (
    <main className="auth-layout">
      <aside className="auth-side">
        <div className="auth-brand">
          <span>
            <Landmark size={25} />
          </span>
          <div>
            <strong>Harbour</strong>
            <small>Credit Union</small>
          </div>
        </div>
        <div className="auth-side-copy">
          <h1>Account recovery</h1>
          <p>Restore access to your credit union account.</p>
        </div>
        <small className="auth-side-foot">Built by Vinette · By Vinyl</small>
      </aside>
      <div className="auth-main">
        <Link to="/sign-in" className="auth-back">
          Back to sign in
        </Link>
        <section className="auth-card" aria-labelledby="reset-title">
          <div className="auth-symbol">
            <LockKeyhole size={24} />
          </div>
          <h2 id="reset-title">
            {done
              ? "Recovery request complete"
              : token
                ? "Set a new password"
                : "Forgot your password?"}
          </h2>
          {done ? (
            <div role="status">
              <p>
                If you submitted your email, an eligible account will receive a link when email
                delivery is enabled. If you set a new password, sign in using it and your protected
                method.
              </p>
              <Link className="primary-button" to="/sign-in">
                Go to sign in
              </Link>
            </div>
          ) : (
            <>
              <p>
                {token
                  ? "Set a strong new password. Existing sign-in methods remain required unless full recovery was independently approved."
                  : "Enter your registered account email. Password recovery preserves your protected sign-in methods."}
              </p>
              <form
                onSubmit={(event) => {
                  event.preventDefault();
                  void submit();
                }}
              >
                {token ? (
                  <>
                    <label>
                      New password
                      <input
                        required
                        type="password"
                        autoComplete="new-password"
                        minLength={12}
                        maxLength={128}
                        value={password}
                        onChange={(event) => setPassword(event.target.value)}
                      />
                    </label>
                    <label>
                      Confirm new password
                      <input
                        required
                        type="password"
                        autoComplete="new-password"
                        value={confirm}
                        onChange={(event) => setConfirm(event.target.value)}
                      />
                    </label>
                  </>
                ) : (
                  <label>
                    Account email
                    <input
                      required
                      type="email"
                      autoComplete="email"
                      maxLength={320}
                      value={email}
                      onChange={(event) => setEmail(event.target.value)}
                    />
                  </label>
                )}
                <button
                  className="primary-button"
                  disabled={busy || (!!token && password !== confirm)}
                >
                  {token ? "Set password" : "Request recovery email"}
                </button>
              </form>
              <p>
                If you have lost every authenticator, passkey and recovery code, contact the union
                administrator for independently approved recovery.
              </p>
            </>
          )}
        </section>
      </div>
    </main>
  );
}
