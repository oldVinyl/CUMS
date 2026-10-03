import { useCallback, useEffect, useState } from "react";
import type { Profile } from "./auth-api";
import { formatMoney } from "./money";
import { usePromiseToast, useToast } from "./toast-context";
import { ledgerRequest } from "./ledger-api";

type Pending = { id: string; makerId: string; actionType: string; payload: { amount?: string; type?: string; reason?: string; year?: number; entryId?: string };
  fullName: string | null; memberNumber: string | null; originalAmount: string | null; requiredApprovals: number; approvals: number };
export default function LedgerApprovals({ profile, onChanged }: { profile: Profile; onChanged?: () => void }) {
  const [items, setItems] = useState<Pending[]>([]);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [review, setReview] = useState<{ item: Pending; decision: "APPROVE" | "REJECT" } | null>(null);
  const [comment, setComment] = useState("");
  const toast = useToast(); const promiseToast = usePromiseToast();
  const canRead = profile.permissions.includes("LEDGER_READ");
  const canApprove = profile.permissions.includes("APPROVE");
  const load = useCallback(async () => {
    if (!canRead) { setLoading(false); return; }
    setLoading(true); setFailed(false);
    try { setItems(await ledgerRequest<Pending[]>("/approvals")); }
    catch (error) { setFailed(true); toast("Could not load approvals", { tone: "error", detail: error instanceof Error ? error.message : "Please retry." }); }
    finally { setLoading(false); }
  }, [canRead, toast]);
  useEffect(() => { void load(); }, [load]);
  const act = async (item: Pending, decision?: "APPROVE" | "REJECT") => {
    setBusy(true);
    try {
      await promiseToast(ledgerRequest(`/approvals/${item.id}/${decision ? "decide" : "cancel"}`, "POST", decision ? { decision, comment } : undefined),
        { loading: "Saving approval decision…", success: decision === "APPROVE" ? "Approval completed" : decision === "REJECT" ? "Request rejected" : "Request cancelled", error: "Could not complete request" });
      setReview(null); setComment(""); await load(); onChanged?.();
    } catch { /* The toast includes server feedback. */ }
    finally { setBusy(false); }
  };
  if (!canRead) return <section className="surface investment-panel"><h2>Ledger approval access required</h2><p>Your account cannot view ledger requests.</p></section>;
  return <section className="surface investment-panel"><div className="card-title-row"><div><h2>Ledger approvals</h2><p>Contributions, reversals and year-end close. A different person must approve. Showing up to 100 pending requests.</p></div>
    <button className="text-button" disabled={busy || loading} onClick={() => void load()}>Refresh</button></div>
    {loading ? <p role="status">Loading approvals…</p> : failed ? <p role="alert">Requests could not be loaded. Use Refresh to retry.</p> : !items.length ? <p>No ledger requests are awaiting approval.</p> : items.map(item => <article className="ledger-entry" key={item.id}>
      <h3>{item.actionType === "LEDGER_POST" ? `${item.payload.type === "SEED" ? "Seed" : "Additional"} contribution` : item.actionType === "LEDGER_REVERSE" ? "Contribution reversal" : `Close fiscal year ${item.payload.year}`}</h3>
      {item.fullName && <p><strong>{item.fullName}</strong> · {item.memberNumber}</p>}
      {item.actionType === "LEDGER_POST" && <p>Principal: <strong>{formatMoney(item.payload.amount ?? "0")}</strong>. Effective on the day of approval.</p>}
      {item.actionType === "LEDGER_REVERSE" && <><p>Principal compensation: <strong>−{formatMoney(item.originalAmount ?? "0")}</strong></p><p>Reason: {item.payload.reason}</p><small>Original entry: {item.payload.entryId}</small></>}
      {item.actionType === "FISCAL_YEAR_CLOSE" && <p>Creates member balance-forward snapshots and blocks posting to this fiscal year.</p>}
      <p>{item.approvals} of {item.requiredApprovals} approvals</p>
      <div className="investment-action-bar">{item.makerId === profile.userId ? <button className="secondary-button" disabled={busy} onClick={() => void act(item)}>Cancel my request</button> : canApprove && <>
        <button className="primary-button" disabled={busy} onClick={() => { setReview({ item, decision: "APPROVE" }); setComment(""); }}>Review approval</button>
        <button className="secondary-button" disabled={busy} onClick={() => { setReview({ item, decision: "REJECT" }); setComment(""); }}>Reject</button></>}</div>
      {review?.item.id === item.id && <form className="investment-edit" onSubmit={event => { event.preventDefault(); void act(item, review.decision); }}>
        <h3>{review.decision === "APPROVE" ? "Confirm approval" : "Reject this request"}</h3>
        <p>{review.decision === "APPROVE" ? "Confirm that you have checked the member, amount and financial impact above. This decision is recorded." : "Explain why this request should not proceed."}</p>
        <label>{review.decision === "REJECT" ? "Reason (required)" : "Review note (optional)"}<textarea rows={2} maxLength={1000} required={review.decision === "REJECT"} value={comment} onChange={event => setComment(event.target.value)} /></label>
        <div className="investment-action-bar"><button className="primary-button" disabled={busy}>{review.decision === "APPROVE" ? "Confirm and approve" : "Confirm rejection"}</button>
          <button type="button" className="text-button" onClick={() => setReview(null)}>Cancel</button></div>
      </form>}
    </article>)}
  </section>;
}
