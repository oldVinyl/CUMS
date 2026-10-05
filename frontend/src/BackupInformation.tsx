import { useEffect, useState } from "react";
import { HardDrive } from "lucide-react";
type Operations = {
  receiptStorage: string;
  scannerConfigured: boolean;
  emailEnabled: boolean;
  backupRetention: string;
  backupVerification: string;
};
export default function BackupInformation() {
  const [value, setValue] = useState<Operations | null>(null),
    [error, setError] = useState(""),
    [retry, setRetry] = useState(0);
  useEffect(() => {
    const c = new AbortController();
    setError("");
    fetch("/api/v1/organization/operations", { credentials: "same-origin", signal: c.signal })
      .then(async (r) => {
        if (!r.ok) throw new Error("Could not load storage information.");
        return r.json() as Promise<Operations>;
      })
      .then(setValue)
      .catch((e) => {
        if (!c.signal.aborted) setError(e.message);
      });
    return () => c.abort();
  }, [retry]);
  return (
    <section className="surface setting-card">
      <span className="settings-icon">
        <HardDrive size={24} />
      </span>
      <h2>Storage and backups</h2>
      {error ? (
        <>
          <p role="alert">{error}</p>
          <button className="secondary-button" onClick={() => setRetry((n) => n + 1)}>
            Retry
          </button>
        </>
      ) : !value ? (
        <p role="status">Loading storage configuration…</p>
      ) : (
        <>
          <dl className="member-facts">
            <div>
              <dt>Private receipt storage</dt>
              <dd>
                {value.receiptStorage === "s3" ? "Private object storage" : "Private local volume"}
              </dd>
            </div>
            <div>
              <dt>Malware scanner</dt>
              <dd>{value.scannerConfigured ? "Configured" : "Not configured"}</dd>
            </div>
            <div>
              <dt>Email delivery</dt>
              <dd>{value.emailEnabled ? "Enabled" : "Disabled; messages queue securely"}</dd>
            </div>
            <div>
              <dt>Encrypted backup retention</dt>
              <dd>{value.backupRetention}</dd>
            </div>
          </dl>
          <p>{value.backupVerification}</p>
          <p>
            Ask the union operator for the last successful off-site backup and restore-drill record.
          </p>
        </>
      )}
    </section>
  );
}
