import { useState } from "react";
import { Download } from "lucide-react";
import { usePromiseToast } from "./toast-context";

export default function ReportDownloads({ path, filename }: { path: string; filename: string }) {
  const [busy, setBusy] = useState(false);
  const showPromiseToast = usePromiseToast();
  const download = async (format: "xlsx" | "pdf") => {
    setBusy(true);
    try {
      await showPromiseToast((async () => {
        const response = await fetch(`${path}${path.includes("?") ? "&" : "?"}format=${format}`, { credentials: "same-origin" });
        if (!response.ok) {
          const error = await response.json().catch(() => null) as { message?: string } | null;
          throw new Error(error?.message ?? "The report could not be downloaded.");
        }
        const url = URL.createObjectURL(await response.blob());
        const link = document.createElement("a");
        link.href = url; link.download = `${filename}.${format}`;
        document.body.appendChild(link); link.click(); link.remove();
        window.setTimeout(() => URL.revokeObjectURL(url), 1000);
      })(), { loading: "Preparing report…", success: "Report downloaded", error: "Could not download report" });
    } catch { /* Promise toast includes server feedback. */ }
    finally { setBusy(false); }
  };
  return <div className="investment-action-bar" role="group" aria-label="Download report">
    <button className="secondary-button" disabled={busy} onClick={() => void download("xlsx")}><Download size={16} /> Excel</button>
    <button className="secondary-button" disabled={busy} onClick={() => void download("pdf")}><Download size={16} /> PDF</button>
  </div>;
}
