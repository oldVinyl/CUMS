import { Copy, ExternalLink, Fingerprint, Globe, LogOut } from "lucide-react";
import type { Profile } from "./auth-api";
import { linkGoogle } from "./auth-api";
import AccountSecurity from "./AccountSecurity";
import { useToast } from "./toast-context";
import GoogleButton from "./GoogleButton";
import FinancialPolicySettings from "./FinancialPolicySettings";

export default function SettingsPage({ profile, signOutAll, onSessionEnded = () => {} }: {
  profile: Profile | null; signOutAll: () => void; onSessionEnded?: () => void;
}) {
  const showToast = useToast();
  const github = "https://github.com/oldVinyl";
  const copyGithub = async () => {
    try { await navigator.clipboard.writeText(github); showToast("GitHub link copied", { tone: "success" }); }
    catch { showToast("Could not copy the link", { detail: "Use the GitHub link below instead.", tone: "error" }); }
  };
  return <>
    <header className="page-heading"><div><div className="eyebrow">HARBOUR CREDIT UNION</div>
      <h1>Settings</h1><p>Your union’s policies and workspace details.</p></div></header>
    <div className="settings-grid setting-cards">
      {profile?.permissions.includes("SETTINGS_WRITE") && <FinancialPolicySettings />}
      {profile ? <AccountSecurity onSessionEnded={onSessionEnded} /> : <section className="surface setting-card"><span className="settings-icon"><Fingerprint size={24} /></span><h2>Passkeys</h2><p>Sign in to manage your security methods.</p></section>}
      <section className="surface setting-card"><span className="settings-icon"><Globe size={24} /></span>
        <h2>Google sign-in</h2><p>Link your Google account as another sign-in option.</p>
        {profile ? <GoogleButton mode="link" onCredential={idToken => { void linkGoogle(idToken)
          .then(() => showToast("Google account linked", { tone: "success" }))
          .catch(error => showToast("Could not link Google", { detail: error instanceof Error ? error.message : "Please retry.", tone: "error" })); }} />
          : <p className="setting-help">Sign in to link a Google account.</p>}</section>
      <section className="surface setting-card"><span className="settings-icon"><LogOut size={24} /></span>
        <h2>Active sessions</h2><p>Sign out on every device if you have lost a device or want a fresh start.</p>
        {profile ? <><p className="setting-help">Signed in as {profile.email}. Preferred sign-in: {profile.preferredSignInMethod === "PASSKEY" ? "passkey" : profile.preferredSignInMethod === "GOOGLE" ? "Google" : "authenticator app"}.</p>
          <button className="secondary-button" onClick={signOutAll}>Sign out on all devices</button></>
          : <p className="setting-help">Sign in to manage active sessions.</p>}</section>
    </div>
    <section className="surface creator-card" aria-labelledby="creator-title">
      <div className="creator-mark" aria-hidden="true">V<span>.</span></div>
      <div className="creator-credits"><div className="creator-description"><span className="creator-eyebrow">MADE WITH CARE</span>
        <h2 id="creator-title">Built by Vinette</h2><p>By Vinyl. Thoughtful tools for the people who keep communities moving.</p></div>
        <div className="creator-actions"><a href={github} target="_blank" rel="noopener noreferrer" className="creator-link">github.com/oldVinyl <ExternalLink size={16} /></a>
          <button className="copy-link" onClick={() => void copyGithub()}><Copy size={16} /> Copy link</button></div></div>
    </section>
  </>;
}
