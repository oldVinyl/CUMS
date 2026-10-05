import { useEffect, useState } from "react";
import { csrfCookie, type Profile } from "./auth-api";
import ResponsiveOverlay from "./ResponsiveOverlay";
import { usePromiseToast } from "./toast-context";

type Candidate = {
  memberId: string;
  memberNumber: string;
  leftOn: string | null;
  eligibleOn: string | null;
  version: number;
};
type Pending = {
  id: string;
  memberId: string;
  memberNumber: string;
  makerId: string;
  approvals: number;
  requiredApprovals: number;
  reason: string;
};
type Overview = {
  retentionYears: number;
  candidates: Candidate[];
  unconfirmedDepartures: Candidate[];
  pending: Pending[];
  pendingReceiptDeletions: number;
};
type Preview = {
  memberId: string;
  memberNumber: string;
  leftOn: string;
  eligibleOn: string;
  sourceFingerprint: string;
  privateImportRows: number;
  receipts: number;
  consequence: string;
};
async function request<T>(path: string, body?: object, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`/api/v1/retention${path}`, {
    method: body ? "POST" : "GET",
    credentials: "same-origin",
    signal,
    headers: body
      ? { "Content-Type": "application/json", "X-CSRF-Token": csrfCookie() }
      : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!response.ok) {
    const error = (await response.json().catch(() => null)) as { message?: string } | null;
    throw new Error(error?.message ?? "Could not complete retention request.");
  }
  return response.status === 204 ? (undefined as T) : (response.json() as Promise<T>);
}
export default function MemberRetentionSettings({ profile }: { profile: Profile }) {
  const maker = ["SETTINGS_WRITE", "USER_ADMIN", "MEMBER_WRITE"].every((p) =>
    profile.permissions.includes(p),
  );
  const checker =
    profile.permissions.includes("APPROVE") && profile.permissions.includes("SETTINGS_WRITE");
  const [overview, setOverview] = useState<Overview | null>(null),
    [error, setError] = useState(""),
    [retry, setRetry] = useState(0),
    [busy, setBusy] = useState(false);
  const [years, setYears] = useState(7),
    [departure, setDeparture] = useState(""),
    [leftOn, setLeftOn] = useState("");
  const [preview, setPreview] = useState<Preview | null>(null),
    [pending, setPending] = useState<Pending | null>(null),
    [reason, setReason] = useState(""),
    [checked, setChecked] = useState(false);
  const toast = usePromiseToast();
  useEffect(() => {
    const controller = new AbortController();
    setError("");
    void request<Overview>("", undefined, controller.signal)
      .then((value) => {
        if (!controller.signal.aborted) {
          setOverview(value);
          setYears(value.retentionYears);
        }
      })
      .catch((failure: Error) => {
        if (!controller.signal.aborted) setError(failure.message);
      });
    return () => controller.abort();
  }, [retry]);
  const mutate = async (work: Promise<unknown>, success: string) => {
    setBusy(true);
    try {
      await toast(work, {
        loading: "Saving retention request…",
        success,
        error: "Could not complete retention request",
      });
      setRetry((v) => v + 1);
      setPreview(null);
      setPending(null);
    } catch {
      /* Toast contains feedback. */
    } finally {
      setBusy(false);
    }
  };
  const review = async (memberId: string, requestToReview: Pending | null) => {
    setBusy(true);
    try {
      const value = await toast(request<Preview>(`/members/${memberId}/preview`), {
        loading: "Reviewing retained records…",
        success: "Retention preview ready",
        error: "Could not prepare retention preview",
      });
      setPreview(value);
      setPending(requestToReview);
      setReason("");
      setChecked(false);
    } catch {
      /*toast*/
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="organization-rollover">
      <h3>Member data retention</h3>
      <p>
        Retain personal data for {overview?.retentionYears ?? 7} years after a confirmed leaving
        date. Financial amounts and audit metadata remain after anonymization.
      </p>
      {error ? (
        <div role="alert">
          <p>{error}</p>
          <button className="secondary-button" onClick={() => setRetry((v) => v + 1)}>
            Retry retention settings
          </button>
        </div>
      ) : !overview ? (
        <p role="status">Loading retention settings…</p>
      ) : (
        <>
          {maker && (
            <form
              className="investment-form"
              onSubmit={(e) => {
                e.preventDefault();
                void mutate(
                  request("/policy", { retentionYears: years }),
                  "Retention policy saved",
                );
              }}
            >
              <label>
                Personal data retention (years)
                <input
                  type="number"
                  required
                  min={1}
                  max={30}
                  value={years}
                  onChange={(e) => setYears(Number(e.target.value))}
                />
              </label>
              <button className="secondary-button" disabled={busy}>
                Save retention period
              </button>
            </form>
          )}
          {maker && overview.unconfirmedDepartures.length > 0 && (
            <form
              className="investment-form"
              onSubmit={(e) => {
                e.preventDefault();
                const member = overview.unconfirmedDepartures.find((c) => c.memberId === departure);
                if (member)
                  void mutate(
                    request("/departure", {
                      memberId: member.memberId,
                      version: member.version,
                      leftOn,
                    }),
                    "Leaving date recorded",
                  );
              }}
            >
              <h4>Confirm a legacy leaving date</h4>
              <p>
                Existing inactive memberships have no inferred leaving date. Confirm it from the
                union's records.
              </p>
              <label>
                Inactive membership
                <select required value={departure} onChange={(e) => setDeparture(e.target.value)}>
                  <option value="">Choose a member number</option>
                  {overview.unconfirmedDepartures.map((c) => (
                    <option key={c.memberId} value={c.memberId}>
                      {c.memberNumber}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Confirmed leaving date
                <input
                  required
                  type="date"
                  min="2000-01-01"
                  max={new Date().toISOString().slice(0, 10)}
                  value={leftOn}
                  onChange={(e) => setLeftOn(e.target.value)}
                />
              </label>
              <button className="secondary-button" disabled={busy}>
                Record leaving date
              </button>
            </form>
          )}
          <h4>Memberships past the retention period</h4>
          {!overview.candidates.length ? (
            <p>No memberships are due for anonymization.</p>
          ) : (
            overview.candidates.map((c) => (
              <article className="payment-record" key={c.memberId}>
                <p>
                  Member {c.memberNumber} · Left {c.leftOn} · Eligible since {c.eligibleOn}
                </p>
                {maker && (
                  <button
                    className="secondary-button"
                    disabled={busy}
                    onClick={() => void review(c.memberId, null)}
                  >
                    Preview anonymization
                  </button>
                )}
              </article>
            ))
          )}
          <h4>Pending independent decisions</h4>
          {!overview.pending.length ? (
            <p>No retention requests awaiting approval.</p>
          ) : (
            overview.pending.map((p) => (
              <article className="payment-record" key={p.id}>
                <p>
                  Member {p.memberNumber} · {p.approvals} of {p.requiredApprovals} approvals
                </p>
                <p className="payment-instructions">{p.reason}</p>
                {p.makerId === profile.userId ? (
                  <button
                    className="secondary-button"
                    disabled={busy}
                    onClick={() =>
                      void mutate(
                        request(`/requests/${p.id}/cancel`, {}),
                        "Anonymization request cancelled",
                      )
                    }
                  >
                    Cancel my retention request
                  </button>
                ) : (
                  checker && (
                    <button
                      className="secondary-button"
                      disabled={busy}
                      onClick={() => void review(p.memberId, p)}
                    >
                      Review anonymization
                    </button>
                  )
                )}
              </article>
            ))
          )}
          {overview.pendingReceiptDeletions > 0 && (
            <p role="status">
              {overview.pendingReceiptDeletions} receipt files awaiting deletion. Downloads are
              blocked immediately; failed storage deletions retry automatically.
            </p>
          )}
          <p className="field-help">
            Lists show up to 100 memberships at a time. Original source workbooks and older backups
            remain subject to the owner's retention process.
          </p>
        </>
      )}
      {preview && (
        <ResponsiveOverlay
          open
          title="Review member anonymization"
          onClose={() => {
            setPreview(null);
            setPending(null);
          }}
        >
          <section className="investment-panel">
            <h3>Member {preview.memberNumber}</h3>
            <p>{preview.consequence}</p>
            <p>
              Left {preview.leftOn} · Eligible {preview.eligibleOn}
            </p>
            <p>
              {preview.privateImportRows} private import rows · {preview.receipts} receipt files
            </p>
            {pending && <p className="payment-instructions">Request reason: {pending.reason}</p>}
            <label className="investment-action-bar">
              <input
                type="checkbox"
                checked={checked}
                onChange={(e) => setChecked(e.target.checked)}
              />
              I understand this permanently removes personal data and cannot be undone.
            </label>
            <label>
              {pending ? "Reason to reject" : "Source and reason for anonymization"}
              <textarea
                maxLength={1000}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
            </label>
            {pending ? (
              <div className="investment-action-bar">
                <button
                  className="primary-button"
                  disabled={busy || !checked}
                  onClick={() =>
                    void mutate(
                      request(`/requests/${pending.id}/decide`, { decision: "APPROVE" }),
                      "Retention approval recorded",
                    )
                  }
                >
                  Approve permanent anonymization
                </button>
                <button
                  className="secondary-button"
                  disabled={busy || !reason.trim()}
                  onClick={() =>
                    void mutate(
                      request(`/requests/${pending.id}/decide`, { decision: "REJECT", reason }),
                      "Retention request rejected",
                    )
                  }
                >
                  Reject retention request
                </button>
              </div>
            ) : (
              <button
                className="primary-button"
                disabled={busy || !checked || !reason.trim()}
                onClick={() =>
                  void mutate(
                    request("", {
                      memberId: preview.memberId,
                      sourceFingerprint: preview.sourceFingerprint,
                      reason,
                    }),
                    "Anonymization sent for independent approval",
                  )
                }
              >
                Request permanent anonymization
              </button>
            )}
          </section>
        </ResponsiveOverlay>
      )}
    </div>
  );
}
