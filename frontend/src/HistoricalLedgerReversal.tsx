import { useState } from "react";
import ResponsiveOverlay from "./ResponsiveOverlay";
import { ContributionImpact, type Impact } from "./ContributionForm";
import { ledgerRequest } from "./ledger-api";
import { formatMoney } from "./money";
import { usePromiseToast } from "./toast-context";

export default function HistoricalLedgerReversal({
  entry,
  onClose,
  onSaved,
}: {
  entry: { id: string; amount: string; effectiveDate: string; memberNumber: string };
  onClose: () => void;
  onSaved: () => void;
}) {
  const [reason, setReason] = useState(""),
    [impact, setImpact] = useState<Impact | null>(null),
    [checked, setChecked] = useState(false),
    [busy, setBusy] = useState(false);
  const toast = usePromiseToast();
  const preview = async () => {
    setBusy(true);
    setImpact(null);
    setChecked(false);
    try {
      const value = await toast(
        ledgerRequest<Impact>(`/${entry.id}/historical-reversal/preview`, "POST"),
        {
          loading: "Calculating original-date compensation…",
          success: "Reversal impact ready",
          error: "Could not calculate historical reversal",
        },
      );
      setImpact(value);
    } catch {
      /*toast*/
    } finally {
      setBusy(false);
    }
  };
  const submit = async () => {
    if (!impact) return;
    setBusy(true);
    try {
      await toast(
        ledgerRequest("/historical-reversals", "POST", {
          entryId: entry.id,
          reason,
          sourceFingerprint: impact.sourceFingerprint,
        }),
        {
          loading: "Requesting original-date compensation…",
          success: "Historical reversal sent for independent approval",
          error: "Could not request historical reversal",
        },
      );
      onSaved();
      onClose();
    } catch {
      /*toast*/
    } finally {
      setBusy(false);
    }
  };
  return (
    <ResponsiveOverlay open title="Original-date ledger correction" onClose={onClose}>
      <section className="investment-panel">
        <h3>
          Member {entry.memberNumber} · {formatMoney(entry.amount)}
        </h3>
        <p>
          Compensate this exact entry on {entry.effectiveDate}. The original remains visible. A
          separate approval recomputes affected current and archived cycles and fiscal snapshots.
          Enter any corrected replacement separately through the reviewed contribution/import
          workflow.
        </p>
        <p>
          Original fiscal periods and every affected maturity/compensation period must be open or
          independently unlocked.
        </p>
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
          disabled={busy || !reason.trim()}
          onClick={() => void preview()}
        >
          Preview original-date reversal
        </button>
        {impact && (
          <>
            <ContributionImpact impact={impact} />
            <label className="investment-action-bar">
              <input
                type="checkbox"
                checked={checked}
                onChange={(e) => setChecked(e.target.checked)}
              />
              I reviewed the original entry and every affected allocation.
            </label>
            <button
              className="primary-button"
              disabled={busy || !checked || !reason.trim()}
              onClick={() => void submit()}
            >
              Request original-date compensation
            </button>
          </>
        )}
      </section>
    </ResponsiveOverlay>
  );
}
