import { useState } from "react";
import { Download } from "lucide-react";
import { usePromiseToast } from "./toast-context";
export default function OwnDataExport() {
  const [busy, setBusy] = useState(false); const promiseToast = usePromiseToast();
  const download = async () => {
    setBusy(true);
    try {
      await promiseToast((async () => {
        const response = await fetch("/api/v1/portal/export.json", { credentials: "same-origin" });
        if (!response.ok) { const error = await response.json().catch(() => null) as { message?: string } | null; throw new Error(error?.message ?? "Your data could not be exported. Please retry."); }
        const url = URL.createObjectURL(await response.blob());
        try { const link = document.createElement("a"); link.href = url; link.download = "membership-data.json"; document.body.append(link); link.click(); link.remove(); }
        finally { window.setTimeout(() => URL.revokeObjectURL(url), 1000); }
      })(), { loading: "Preparing your membership data…", success: "Membership data downloaded", error: "Could not export your data" });
    } catch { /* Promise feedback includes the server error. */ }
    finally { setBusy(false); }
  };
  return <section className="surface investment-panel"><h2>Your data</h2><p>Download your profile, financial records, receipt metadata and account history as JSON. Monetary amounts retain their exact decimal values. Download receipt files separately from Payments.</p>
    <p>This file contains personal information. Store it securely.</p>
    <button className="secondary-button" disabled={busy} onClick={() => void download()}><Download size={18} />{busy ? "Preparing download…" : "Download my data"}</button>
  </section>;
}
