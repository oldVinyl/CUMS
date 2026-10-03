import { useEffect, useState } from "react";
import { Fingerprint, KeyRound, LockKeyhole, RefreshCw } from "lucide-react";
import ResponsiveOverlay from "./ResponsiveOverlay";
import { csrfCookie } from "./auth-api";
import { passkeyProof, registerPasskey } from "./passkey-api";
import { usePromiseToast, useToast } from "./toast-context";

type Key = { id: string; createdAt: string; lastUsedAt: string | null; backedUp: boolean };
type Summary = { totpEnabled: boolean; recoveryCodesRemaining: number; passkeys: Key[] };
type Action = { kind: "password" | "codes" | "remove"; id?: string };
async function request<T>(path = "", body?: object): Promise<T> {
  const response = await fetch(`/api/v1/auth/security${path}`, {
    method: body ? "POST" : "GET", credentials: "same-origin", cache: "no-store",
    headers: body ? { "Content-Type": "application/json", "X-CSRF-Token": csrfCookie() } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!response.ok) {
    const error = await response.json().catch(() => null) as { message?: string } | null;
    throw new Error(error?.message ?? "Account security could not be updated. Please retry.");
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>;
}
const dateLabel = (value: string) => new Date(value).toLocaleDateString("en-GH", { day: "numeric", month: "short", year: "numeric", timeZone: "Africa/Accra" });
export default function AccountSecurity({ onSessionEnded }: { onSessionEnded: () => void }) {
  const toast = useToast(); const promiseToast = usePromiseToast();
  const [data, setData] = useState<Summary | null>(null);
  const [failed, setFailed] = useState(false); const [retry, setRetry] = useState(0);
  const [action, setAction] = useState<Action | null>(null); const [busy, setBusy] = useState(false);
  const [password, setPassword] = useState(""); const [code, setCode] = useState("");
  const [newPassword, setNewPassword] = useState(""); const [confirmation, setConfirmation] = useState("");
  const [factor, setFactor] = useState<"code" | "passkey">("code");
  const [codes, setCodes] = useState<string[]>([]); const [saved, setSaved] = useState(false);
  useEffect(() => { let active = true; setFailed(false); void request<Summary>().then(value => { if (active) setData(value); }).catch(() => { if (active) setFailed(true); }); return () => { active = false; }; }, [retry]);
  const close = () => { setAction(null); setCodes([]); setSaved(false); setPassword(""); setCode(""); setNewPassword(""); setConfirmation(""); };
  const open = (value: Action) => { close(); setFactor(data?.totpEnabled ? "code" : "passkey"); setAction(value); };
  const submit = async () => {
    if (!action || !data) return;
    if (action.kind === "password" && newPassword !== confirmation) { toast("Passwords do not match", { tone: "error" }); return; }
    setBusy(true);
    let deviceVerified = factor !== "passkey";
    try {
      const assertion = factor === "passkey" ? await passkeyProof() : null;
      deviceVerified = true;
      const proof = { password, code: factor === "code" ? code : undefined, passkeyToken: assertion?.token, credential: assertion?.credential };
      const path = action.kind === "password" ? "/password" : action.kind === "codes" ? "/recovery-codes" : `/passkeys/${action.id}/remove`;
      const result = await promiseToast(request<{ recoveryCodes: string[] }>(path, { proof, newPassword: action.kind === "password" ? newPassword : undefined }), {
        loading: "Verifying your account…", success: action.kind === "codes" ? "Recovery codes replaced" : action.kind === "password" ? "Password changed" : "Passkey removed", error: "Account security was not changed",
      });
      setPassword(""); setCode(""); setNewPassword(""); setConfirmation("");
      if (action.kind === "codes") { setAction({ kind: "codes" }); setCodes(result.recoveryCodes); setRetry(value => value + 1); }
      else { close(); onSessionEnded(); }
    } catch (error) {
      if (!deviceVerified && error instanceof Error) toast("Security verification could not be completed", { detail: error.message, tone: "error" });
    } finally { setBusy(false); }
  };
  const title = codes.length ? "Save your new recovery codes" : action?.kind === "password" ? "Change password" : action?.kind === "codes" ? "Replace recovery codes" : "Remove passkey";
  return <>
    <section className="surface setting-card"><span className="settings-icon"><Fingerprint size={24} /></span><h2>Passkeys</h2><p>Use your fingerprint, face or device screen lock. Keep a backup sign-in method for a lost device.</p>
      {failed ? <p role="alert">Security details could not be loaded. <button className="text-button" onClick={() => setRetry(value => value + 1)}>Try again</button></p> : !data ? <p role="status">Loading security details…</p> : <>
        {data.passkeys.length ? <ul className="security-key-list">{data.passkeys.map((key, index) => <li key={key.id}><div><strong>Passkey {index + 1}</strong><span>Added {dateLabel(key.createdAt)} · {key.lastUsedAt ? `Last used ${dateLabel(key.lastUsedAt)}` : "Not used yet"}</span><span>{key.backedUp ? "Backed up by your passkey provider" : "Backup not reported by your device"}</span></div><button className="secondary-button" disabled={busy} onClick={() => open({ kind: "remove", id: key.id })}>Remove</button></li>)}</ul> : <p className="setting-help">No passkeys added yet.</p>}
        <button className="secondary-button" disabled={busy} onClick={() => { setBusy(true); void promiseToast(registerPasskey(), { loading: "Waiting for your device…", success: "Passkey added", error: "Could not add passkey" }).then(() => setRetry(value => value + 1)).catch(() => {}).finally(() => setBusy(false)); }}>Add a passkey</button>
      </>}
    </section>
    <section className="surface setting-card"><span className="settings-icon"><KeyRound size={24} /></span><h2>Recovery codes</h2><p>Use a one-time code with your password if your authenticator device is unavailable.</p>{data ? <><p className="setting-help">{data.totpEnabled ? `${data.recoveryCodesRemaining} unused codes remain. Replacing them invalidates every old code.` : "Set up an authenticator to receive recovery codes. You can choose authenticator setup when signing in with your password."}</p><button className="secondary-button" disabled={!data.totpEnabled || busy} onClick={() => open({ kind: "codes" })}><RefreshCw size={16} /> Replace recovery codes</button></> : <p className="setting-help">{failed ? "Security details unavailable." : "Loading security details…"}</p>}</section>
    <section className="surface setting-card"><span className="settings-icon"><LockKeyhole size={24} /></span><h2>Password</h2><p>Changing your password signs you out on every device. You will sign in again with your new password.</p><button className="secondary-button" disabled={!data || busy} onClick={() => open({ kind: "password" })}>Change password</button></section>
    <ResponsiveOverlay open={!!action} onClose={close} title={title} description={codes.length ? "These codes are shown once. Keep them somewhere private." : "Confirm your password and a security verification before continuing."}>
      {codes.length ? <><ul className="recovery-codes security-recovery-codes">{codes.map(value => <li key={value}><code>{value}</code></li>)}</ul><button className="secondary-button" onClick={() => { void navigator.clipboard.writeText(codes.join("\n")).then(() => toast("Recovery codes copied", { tone: "success" })).catch(() => toast("Could not copy codes", { detail: "Save them manually before closing this panel.", tone: "error" })); }}>Copy codes</button><label className="security-saved"><input type="checkbox" checked={saved} onChange={event => setSaved(event.target.checked)} /> I have saved these codes securely</label><button className="primary-button" disabled={!saved} onClick={close}>Done</button></> : <form className="security-form" onSubmit={event => { event.preventDefault(); void submit(); }}>
        <p>{action?.kind === "remove" ? "This passkey will stop working for this account, and all sessions will be signed out. Another protected sign-in method must remain." : action?.kind === "codes" ? "All old recovery codes will stop working. Save the replacement codes before closing the panel." : "All active sessions will end after the password changes."}</p>
        <label>Current password<input type="password" autoComplete="current-password" required maxLength={1024} value={password} onChange={event => setPassword(event.target.value)} /></label>
        {action?.kind === "password" && <><label>New password<input type="password" autoComplete="new-password" required minLength={12} maxLength={1024} value={newPassword} onChange={event => setNewPassword(event.target.value)} /></label><label>Confirm new password<input type="password" autoComplete="new-password" required minLength={12} maxLength={1024} value={confirmation} onChange={event => setConfirmation(event.target.value)} /></label></>}
        <label>Security verification<select value={factor} onChange={event => setFactor(event.target.value as "code" | "passkey")}><option value="code" disabled={!data?.totpEnabled}>Authenticator or recovery code</option><option value="passkey" disabled={!data?.passkeys.length}>Passkey</option></select></label>
        {factor === "code" ? <label>Authenticator or recovery code<input autoComplete="one-time-code" required maxLength={22} value={code} onChange={event => setCode(event.target.value.trim())} /></label> : <p className="setting-help">Your device will ask you to verify your passkey when you confirm.</p>}
        <div className="security-actions"><button type="button" className="secondary-button" onClick={close}>Cancel</button><button className="primary-button" disabled={busy}>{busy ? "Verifying…" : "Confirm"}</button></div>
      </form>}
    </ResponsiveOverlay>
  </>;
}
