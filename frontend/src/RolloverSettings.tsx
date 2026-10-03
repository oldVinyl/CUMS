import { useEffect, useState } from "react";
import { csrfCookie } from "./auth-api";
import { usePromiseToast, useToast } from "./toast-context";

const choices = [
  ["ROLL_PRINCIPAL_AND_INCOME", "Principal and income"],
  ["ROLL_PRINCIPAL_ONLY", "Principal only"],
  ["NO_ROLLOVER", "No automatic rollover"],
] as const;

export default function RolloverSettings() {
  const showToast = useToast();
  const showPromiseToast = usePromiseToast();
  const [policy, setPolicy] = useState("");
  const [saved, setSaved] = useState("");
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    fetch("/api/v1/settings/rollover", { credentials: "same-origin", signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) throw new Error("Rollover setting could not be loaded.");
        return response.json() as Promise<{ defaultRolloverPolicy: string }>;
      }).then((result) => {
        if (!controller.signal.aborted) {
          setPolicy(result.defaultRolloverPolicy);
          setSaved(result.defaultRolloverPolicy);
        }
      }).catch((error: unknown) => {
        if (!controller.signal.aborted) showToast("Could not load rollover setting", {
          tone: "error", detail: error instanceof Error ? error.message : "Please retry.",
        });
      });
    return () => controller.abort();
  }, [showToast]);

  const save = async () => {
    setBusy(true);
    try {
      const result = await showPromiseToast(fetch("/api/v1/settings/rollover", {
        method: "PUT", credentials: "same-origin",
        headers: { "Content-Type": "application/json", "X-CSRF-Token": csrfCookie() },
        body: JSON.stringify({ defaultRolloverPolicy: policy }),
      }).then(async (response) => {
        if (!response.ok) {
          const error = await response.json().catch(() => null) as { message?: string } | null;
          throw new Error(error?.message ?? "Setting could not be saved.");
        }
        return response.json() as Promise<{ defaultRolloverPolicy: string }>;
      }), { loading: "Saving rollover default…", success: "Rollover default saved",
        error: "Could not save rollover default" });
      setSaved(result.defaultRolloverPolicy);
    } catch {
      /* The toast includes the error. */
    } finally { setBusy(false); }
  };

  return <div className="rollover-settings">
    <label>Default rollover decision
      <select value={policy} disabled={!policy || busy}
        onChange={(event) => setPolicy(event.target.value)}>
        {choices.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
      </select>
    </label>
    <p>Used as the starting policy for new investments. Each matured investment can still be decided separately.</p>
    <button type="button" className="primary-button" disabled={!policy || policy === saved || busy}
      onClick={() => void save()}>Save rollover default</button>
  </div>;
}
