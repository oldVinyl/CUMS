import MemberRetentionSettings from "./MemberRetentionSettings";
import type { Profile } from "./auth-api";
import { useEffect, useState } from "react";
import { Landmark } from "lucide-react";
import RolloverSettings from "./RolloverSettings";
import { csrfCookie } from "./auth-api";
import { usePromiseToast } from "./toast-context";
type Policy = {
  organisationName: string;
  contactEmail: string | null;
  contactPhone: string | null;
  requiredApprovals: number;
  feeBearer: string;
  paymentMin: string;
  paymentMax: string;
};
export default function FinancialPolicySettings({ profile }: { profile?: Profile }) {
  const [policy, setPolicy] = useState<Policy | null>(null),
    [error, setError] = useState(""),
    [retry, setRetry] = useState(0),
    [busy, setBusy] = useState(false);
  const promiseToast = usePromiseToast();
  useEffect(() => {
    const controller = new AbortController();
    setError("");
    fetch("/api/v1/payments/policy", { signal: controller.signal, credentials: "same-origin" })
      .then(async (r) => {
        if (!r.ok) throw new Error("Could not load union policies.");
        return r.json() as Promise<Policy>;
      })
      .then((p) => {
        if (!controller.signal.aborted) setPolicy(p);
      })
      .catch((e) => {
        if (!controller.signal.aborted) setError(e.message);
      });
    return () => controller.abort();
  }, [retry]);
  const save = async () => {
    if (!policy) return;
    setBusy(true);
    try {
      await promiseToast(
        (async () => {
          const r = await fetch("/api/v1/payments/policy", {
            method: "POST",
            credentials: "same-origin",
            headers: { "Content-Type": "application/json", "X-CSRF-Token": csrfCookie() },
            body: JSON.stringify(policy),
          });
          if (!r.ok) {
            const e = (await r.json()) as { message?: string };
            throw new Error(e.message ?? "Could not save policies.");
          }
          setPolicy((await r.json()) as Policy);
        })(),
        {
          loading: "Saving union policies…",
          success: "Union policies saved",
          error: "Could not save policies",
        },
      );
    } catch {
      /*toast*/
    } finally {
      setBusy(false);
    }
  };
  if (error)
    return (
      <section className="surface setting-card" role="alert">
        <h2>Union policies</h2>
        <p>{error}</p>
        <button className="secondary-button" onClick={() => setRetry((v) => v + 1)}>
          Retry
        </button>
      </section>
    );
  if (!policy)
    return (
      <section className="surface setting-card" role="status">
        Loading union policies…
      </section>
    );
  return (
    <section
      className="surface setting-card organization-settings"
      aria-labelledby="organization-title"
    >
      <span className="settings-icon">
        <Landmark size={24} />
      </span>
      <h2 id="organization-title">Organization policies</h2>
      <p>Union details, payment limits, approval requirements and investment defaults.</p>
      <form
        className="organization-form"
        onSubmit={(e) => {
          e.preventDefault();
          void save();
        }}
      >
        <fieldset disabled={busy}>
          <legend>Union details</legend>
          <div className="investment-form-grid">
            {(
              [
                ["organisationName", "Union name", "text", 200],
                ["contactEmail", "Contact email", "email", 320],
                ["contactPhone", "Contact phone", "tel", 40],
              ] as const
            ).map(([key, label, type, max]) => (
              <label key={key}>
                {label}
                <input
                  type={type}
                  required={key === "organisationName"}
                  value={policy[key] ?? ""}
                  maxLength={max}
                  onChange={(e) => setPolicy({ ...policy, [key]: e.target.value })}
                />
              </label>
            ))}
          </div>
        </fieldset>
        <fieldset disabled={busy}>
          <legend>Payment limits</legend>
          <p>Limits apply to new online payments, in Ghana cedis.</p>
          <div className="investment-form-grid">
            {(
              [
                ["paymentMin", "Minimum online payment (GHS)"],
                ["paymentMax", "Maximum online payment (GHS)"],
              ] as const
            ).map(([key, label]) => (
              <label key={key}>
                {label}
                <input
                  required
                  inputMode="decimal"
                  pattern="[0-9]+(\.[0-9]{1,2})?"
                  value={policy[key]}
                  maxLength={20}
                  onChange={(e) => setPolicy({ ...policy, [key]: e.target.value })}
                />
              </label>
            ))}
          </div>
        </fieldset>
        <fieldset disabled={busy}>
          <legend>Approvals and fees</legend>
          <div className="investment-form-grid">
            <label>
              Required approvals
              <select
                value={policy.requiredApprovals}
                onChange={(e) =>
                  setPolicy({ ...policy, requiredApprovals: Number(e.target.value) })
                }
              >
                {[1, 2, 3].map((n) => (
                  <option key={n} value={n}>
                    {n}
                  </option>
                ))}
              </select>
              <span className="field-help">
                Distinct approvers only. Makers are excluded; pending requests keep their original
                requirement.
              </span>
            </label>
            <label>
              Fee bearer
              <select
                value={policy.feeBearer}
                onChange={(e) => setPolicy({ ...policy, feeBearer: e.target.value })}
              >
                <option value="UNION">Union pays</option>
                <option value="MEMBER">Member pays</option>
              </select>
              <span className="field-help">
                Union pays credits gross. Member pays credits gross less verified fees. Existing
                payments keep their starting policy.
              </span>
            </label>
          </div>
        </fieldset>
        <button className="primary-button" disabled={busy}>
          {busy ? "Saving policies…" : "Save organization policies"}
        </button>
      </form>
      <div className="organization-rollover">
        <h3>Investment rollover</h3>
        <RolloverSettings />
      </div>
      {profile && <MemberRetentionSettings profile={profile} />}
    </section>
  );
}
