import { financialFetch } from "./financial-fetch";
import { useEffect, useRef, useState } from "react";
import type { Profile } from "./auth-api";
import { csrfCookie } from "./auth-api";
import ResponsiveOverlay from "./ResponsiveOverlay";
import { ContributionImpact, type Impact } from "./ContributionForm";
import { formatMoney } from "./money";
import { usePromiseToast } from "./toast-context";

type Pending = {
  id: string;
  investmentId: string;
  makerId: string;
  actualProceeds: string;
  reason: string;
  approvals: number;
  requiredApprovals: number;
  archivedRevision: number | null;
};
async function request<T>(path: string, body?: object, signal?: AbortSignal): Promise<T> {
  const r = await financialFetch(`/api/v1/investment-corrections${path}`, {
    method: body ? "POST" : "GET",
    credentials: "same-origin",
    signal,
    headers: body
      ? { "Content-Type": "application/json", "X-CSRF-Token": csrfCookie() }
      : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!r.ok) {
    const error = (await r.json().catch(() => null)) as { message?: string } | null;
    throw new Error(error?.message ?? "Could not complete historical proceeds correction.");
  }
  return r.status === 204 ? (undefined as T) : (r.json() as Promise<T>);
}
export default function HistoricalIncomeCorrection({
  profile,
  investment,
  onChanged,
  archivedRevision,
  compact = false,
}: {
  profile: Profile;
  investment: { id: string; status: string; actualProceeds: string | null } | null;
  onChanged: () => void;
  archivedRevision?: number;
  compact?: boolean;
}) {
  const maker = ["INVESTMENT_WRITE", "CYCLE_REPOST", "LEDGER_BACKDATE"].every((p) =>
    profile.permissions.includes(p),
  );
  const checker = profile.permissions.includes("APPROVE");
  const [pending, setPending] = useState<Pending[]>([]),
    [error, setError] = useState(""),
    [retry, setRetry] = useState(0),
    [busy, setBusy] = useState(false);
  const [open, setOpen] = useState(false),
    [review, setReview] = useState<Pending | null>(null),
    [proceeds, setProceeds] = useState(""),
    [reason, setReason] = useState(""),
    [impact, setImpact] = useState<Impact | null>(null),
    [checked, setChecked] = useState(false);
  const toast = usePromiseToast(),
    generation = useRef(0);
  useEffect(() => {
    if (compact) return;
    const controller = new AbortController();
    setError("");
    void request<Pending[]>("", undefined, controller.signal)
      .then((value) => {
        if (!controller.signal.aborted) setPending(value);
      })
      .catch((failure: Error) => {
        if (!controller.signal.aborted) setError(failure.message);
      });
    return () => controller.abort();
  }, [retry, compact]);
  useEffect(() => {
    if (!review) {
      generation.current++;
      setImpact(null);
      setChecked(false);
    }
  }, [proceeds, reason, investment?.id, review]);
  const mutate = async (work: Promise<unknown>, success: string) => {
    setBusy(true);
    try {
      await toast(work, {
        loading: "Saving historical correction…",
        success,
        error: "Could not save historical correction",
      });
      setOpen(false);
      setReview(null);
      setImpact(null);
      setRetry((v) => v + 1);
      onChanged();
    } catch {
      /*toast*/
    } finally {
      setBusy(false);
    }
  };
  const preview = async (pendingRequest: Pending | null) => {
    const current = ++generation.current;
    setBusy(true);
    setImpact(null);
    setChecked(false);
    try {
      const value = await toast(
        pendingRequest
          ? request<Impact>(`/${pendingRequest.id}/impact`)
          : request<Impact>("/preview", {
              investmentId: investment?.id,
              archivedRevision,
              actualProceeds: proceeds,
              reason,
            }),
        {
          loading: "Calculating original-date income correction…",
          success: "Correction impact ready",
          error: "Could not calculate historical correction",
        },
      );
      if (generation.current === current) {
        setImpact(value);
        setReview(pendingRequest);
        setOpen(true);
      }
    } catch {
      /*toast*/
    } finally {
      setBusy(false);
    }
  };
  const eligible =
    maker && investment && ["MATURED", "ROLLED_OVER", "SETTLED"].includes(investment.status);
  return (
    <section className="surface investment-panel">
      <h2>Historical earned-income corrections</h2>
      <p>
        Correct received proceeds on the original maturity date. Review every affected cycle: the
        system compensates prior income, recalculates later allocations, and recomputes fiscal
        snapshots after independent approval.
      </p>
      {eligible && (
        <button
          className="secondary-button"
          disabled={busy}
          onClick={() => {
            setOpen(true);
            setReview(null);
            setImpact(null);
            setChecked(false);
            setReason("");
            setProceeds(investment.actualProceeds ?? "");
          }}
        >
          {archivedRevision
            ? `Correct archived revision ${archivedRevision} proceeds`
            : "Correct selected cycle’s received proceeds"}
        </button>
      )}
      {!compact &&
        (error ? (
          <div role="alert">
            <p>{error}</p>
            <button className="secondary-button" onClick={() => setRetry((v) => v + 1)}>
              Retry corrections
            </button>
          </div>
        ) : !pending.length ? (
          <p>No historical proceeds corrections awaiting approval.</p>
        ) : (
          pending.map((p) => (
            <article className="payment-record" key={p.id}>
              <h3>Revised received proceeds: {formatMoney(p.actualProceeds)}</h3>
              <p>
                {p.archivedRevision && <>Archived revision {p.archivedRevision} · </>}
                {p.approvals} of {p.requiredApprovals} approvals · Cycle {p.investmentId}
              </p>
              <p className="payment-instructions">{p.reason}</p>
              <div className="investment-action-bar">
                <button
                  className="secondary-button"
                  disabled={busy}
                  onClick={() => void preview(p)}
                >
                  Review correction impact
                </button>
                {p.makerId === profile.userId && (
                  <button
                    className="secondary-button"
                    disabled={busy}
                    onClick={() =>
                      void mutate(request(`/${p.id}/cancel`, {}), "Historical correction cancelled")
                    }
                  >
                    Cancel my correction
                  </button>
                )}
              </div>
            </article>
          ))
        ))}
      {open && (
        <ResponsiveOverlay
          open
          title={review ? "Review historical income correction" : "Correct received proceeds"}
          onClose={() => {
            setOpen(false);
            setReview(null);
          }}
        >
          <section className="investment-panel">
            {review ? (
              <>
                <h3>Received proceeds {formatMoney(review.actualProceeds)}</h3>
                <p className="payment-instructions">{review.reason}</p>
              </>
            ) : (
              <>
                <h3>Original economic dates are preserved</h3>
                <label>
                  Correct received proceeds (GHS)
                  <input
                    required
                    inputMode="decimal"
                    value={proceeds}
                    onChange={(e) => setProceeds(e.target.value)}
                  />
                </label>
                <label>
                  Evidence and reason
                  <textarea
                    required
                    maxLength={1000}
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                  />
                </label>
                <button
                  className="secondary-button"
                  disabled={busy || !proceeds || !reason.trim()}
                  onClick={() => void preview(null)}
                >
                  Preview income and dependent cycles
                </button>
              </>
            )}
            {impact && (
              <>
                <ContributionImpact impact={impact} />
                <label className="investment-action-bar">
                  <input
                    type="checkbox"
                    checked={checked}
                    onChange={(e) => setChecked(e.target.checked)}
                  />
                  I reviewed the received proceeds, original dates and every changed member
                  allocation.
                </label>
                {review ? (
                  review.makerId === profile.userId ? (
                    <p>A different eligible approver must decide this correction.</p>
                  ) : (
                    checker && (
                      <div className="investment-form">
                        <button
                          className="primary-button"
                          disabled={busy || !checked}
                          onClick={() =>
                            void mutate(
                              request(`/${review.id}/decide`, { decision: "APPROVE" }),
                              "Historical correction approval recorded",
                            )
                          }
                        >
                          Approve original-date correction
                        </button>
                        <label>
                          Reason to reject
                          <textarea
                            maxLength={1000}
                            value={reason}
                            onChange={(e) => setReason(e.target.value)}
                          />
                        </label>
                        <button
                          className="secondary-button"
                          disabled={busy || !reason.trim()}
                          onClick={() =>
                            void mutate(
                              request(`/${review.id}/decide`, { decision: "REJECT", reason }),
                              "Historical correction rejected",
                            )
                          }
                        >
                          Reject correction
                        </button>
                      </div>
                    )
                  )
                ) : (
                  <button
                    className="primary-button"
                    disabled={busy || !checked || !reason.trim()}
                    onClick={() =>
                      void mutate(
                        request("", {
                          investmentId: investment?.id,
                          archivedRevision,
                          actualProceeds: proceeds,
                          reason,
                          sourceFingerprint: impact.sourceFingerprint,
                        }),
                        "Historical correction sent for independent approval",
                      )
                    }
                  >
                    Request original-date correction
                  </button>
                )}
              </>
            )}
          </section>
        </ResponsiveOverlay>
      )}
    </section>
  );
}
