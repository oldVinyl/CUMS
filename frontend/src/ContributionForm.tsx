import { useEffect, useRef, useState } from "react";
import MemberPicker from "./MemberPicker";
import type { Profile } from "./auth-api";
import { ledgerRequest } from "./ledger-api";
import { formatMoney } from "./money";
import { usePromiseToast } from "./toast-context";

export type Impact = {
  sourceFingerprint: string;
  earliestNextCycleDate: string;
  repostAllowed: boolean;
  restriction: string | null;
  affectedCycles: {
    id: string;
    revision: number;
    status: string;
    purchaseDate: string;
    maturityDate: string;
    compensationDate?: string | null;
    shares: {
      memberId: string;
      memberNumber: string;
      oldParticipation: string;
      participation: string;
      costShare: string;
      expectedIncome: string;
      oldActualIncome: string | null;
      actualIncome: string | null;
    }[];
  }[];
};
export function ContributionImpact({ impact }: { impact: Impact }) {
  return (
    <div className="investment-edit">
      <h3>Investment impact</h3>
      <p>
        Explicit participation edits and exclusions are preserved. Review every changed share before
        approval.
      </p>
      {impact.restriction && <p role="status">{impact.restriction}</p>}
      {!impact.affectedCycles.length ? (
        <p>No posted cycles are affected.</p>
      ) : (
        impact.affectedCycles.map((c) => (
          <details key={`${c.id}:${c.revision}`} className="ledger-entry">
            <summary>
              {c.purchaseDate} to {c.maturityDate} · {c.status.replaceAll("_", " ")} · revision{" "}
              {c.revision}
            </summary>
            <p className="payment-instructions">Cycle {c.id}</p>
            {c.compensationDate && (
              <p>
                Archived cycle ended {c.compensationDate}. Any earned-income adjustment is offset
                again on that date, preserving the later compensation timeline.
              </p>
            )}
            {c.shares.map((s) => (
              <article key={s.memberId} className="payment-record">
                <h4>Member {s.memberNumber}</h4>
                <p>
                  Participation: {formatMoney(s.oldParticipation)} → {formatMoney(s.participation)}
                </p>
                <p>
                  Cost share: {formatMoney(s.costShare)} · Expected income:{" "}
                  {formatMoney(s.expectedIncome)}
                </p>
                {s.actualIncome !== null && (
                  <p>
                    Actual income: {formatMoney(s.oldActualIncome ?? "0")} →{" "}
                    {formatMoney(s.actualIncome)}
                  </p>
                )}
              </article>
            ))}
          </details>
        ))
      )}
    </div>
  );
}
export function HistoricalRequestImpact({
  requestId,
  onReady,
  reversal = false,
}: {
  reversal?: boolean;
  requestId: string;
  onReady: (ready: boolean) => void;
}) {
  const [impact, setImpact] = useState<Impact | null>(null),
    [error, setError] = useState("");
  useEffect(() => {
    const controller = new AbortController();
    onReady(false);
    setImpact(null);
    setError("");
    ledgerRequest<Impact>(
      `/approvals/${requestId}/${reversal ? "reversal-impact" : "impact"}`,
      "GET",
      undefined,
      controller.signal,
    )
      .then((value) => {
        if (!controller.signal.aborted) {
          setImpact(value);
          onReady(true);
        }
      })
      .catch((failure) => {
        if (!controller.signal.aborted) setError(failure.message);
      });
    return () => controller.abort();
  }, [requestId, onReady, reversal]);
  return error ? (
    <p role="alert">{error}</p>
  ) : impact ? (
    <ContributionImpact impact={impact} />
  ) : (
    <p role="status">Loading correction impact…</p>
  );
}
export default function ContributionForm({
  profile,
  busy,
  onSubmit,
}: {
  profile: Profile;
  busy: boolean;
  onSubmit: (promise: Promise<unknown>, message: string) => Promise<void>;
}) {
  const today = new Date().toISOString().slice(0, 10);
  const [memberId, setMemberId] = useState(""),
    [type, setType] = useState("ADDITION"),
    [amount, setAmount] = useState("");
  const [historical, setHistorical] = useState(false),
    [date, setDate] = useState(today),
    [mode, setMode] = useState("FUTURE_ONLY"),
    [next, setNext] = useState(today),
    [reason, setReason] = useState("");
  const [impact, setImpact] = useState<Impact | null>(null),
    [checking, setChecking] = useState(false),
    [confirmed, setConfirmed] = useState(false);
  const toast = usePromiseToast();
  const generation = useRef(0);
  useEffect(() => {
    generation.current++;
    setImpact(null);
    setConfirmed(false);
  }, [memberId, type, amount, date, historical]);
  const input = { memberId, type, amount, effectiveDate: date, mode, nextCycleDate: next, reason };
  const preview = async () => {
    const current = ++generation.current;
    setChecking(true);
    setImpact(null);
    setConfirmed(false);
    try {
      await toast(
        ledgerRequest<Impact>("/contributions/preview", "POST", input).then((value) => {
          if (generation.current === current) setImpact(value);
        }),
        {
          loading: "Calculating historical impact…",
          success: "Impact ready for review",
          error: "Could not calculate impact",
        },
      );
    } catch {
      /* Toast supplies server detail. */
    } finally {
      setChecking(false);
    }
  };
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        if (historical && !impact) {
          void preview();
          return;
        }
        void onSubmit(
          ledgerRequest(
            historical ? "/contributions/historical" : "/contributions",
            "POST",
            historical
              ? { ...input, sourceFingerprint: impact?.sourceFingerprint }
              : { memberId, type, amount },
          ),
          "Contribution approval requested",
        );
      }}
    >
      {profile.permissions.includes("LEDGER_BACKDATE") && (
        <label className="investment-action-bar">
          <input
            type="checkbox"
            checked={historical}
            onChange={(event) => setHistorical(event.target.checked)}
          />
          Use a historical date
        </label>
      )}
      <div className="investment-form-grid">
        <MemberPicker onSelect={(member) => setMemberId(member?.id ?? "")} />
        <label>
          Contribution type
          <select value={type} onChange={(event) => setType(event.target.value)}>
            <option value="SEED">Seed capital</option>
            <option value="ADDITION">Additional contribution</option>
          </select>
        </label>
        <label>
          Amount (GH₵)
          <input
            required
            type="number"
            min="0.01"
            step="0.01"
            value={amount}
            onChange={(event) => setAmount(event.target.value)}
          />
        </label>
        {historical && (
          <>
            <label>
              Original effective date
              <input
                required
                type="date"
                min="2000-01-01"
                max={today}
                value={date}
                onChange={(event) => setDate(event.target.value)}
              />
            </label>
            <label>
              Correction reason
              <textarea
                required
                maxLength={1000}
                value={reason}
                onChange={(event) => setReason(event.target.value)}
              />
            </label>
          </>
        )}
      </div>
      {historical && impact && (
        <>
          <ContributionImpact impact={impact} />
          <label>
            Handling of existing cycles
            <select
              value={mode}
              onChange={(event) => {
                setMode(event.target.value);
                setConfirmed(false);
              }}
            >
              <option value="FUTURE_ONLY">Apply to future cycles only</option>
              <option value="REPOST" disabled={!impact.repostAllowed}>
                Reverse and repost affected cycles
              </option>
            </select>
          </label>
          {mode === "FUTURE_ONLY" ? (
            <label>
              Next cycle start date
              <input
                required
                type="date"
                min={impact.earliestNextCycleDate}
                max={today}
                value={next}
                onChange={(event) => {
                  setNext(event.target.value);
                  setConfirmed(false);
                }}
              />
              <small>
                Existing allocations stay unchanged. The original date is retained for audit. Wait
                to submit if the next start date has not arrived.
              </small>
            </label>
          ) : (
            <p>
              Approval archives the old allocations, exactly compensates income, recalculates
              affected cycles in purchase order and recomputes snapshots. Every affected year must
              be open.
            </p>
          )}
          <label className="investment-action-bar">
            <input
              type="checkbox"
              checked={confirmed}
              onChange={(event) => setConfirmed(event.target.checked)}
            />
            I reviewed the dates, affected cycles and financial impact
          </label>
          <button
            type="button"
            className="secondary-button"
            disabled={checking || busy}
            onClick={() => void preview()}
          >
            Refresh impact
          </button>
        </>
      )}
      <button
        className="primary-button"
        disabled={busy || checking || !memberId || (historical && !!impact && !confirmed)}
      >
        {historical && !impact ? "Preview historical impact" : "Request contribution approval"}
      </button>
    </form>
  );
}
