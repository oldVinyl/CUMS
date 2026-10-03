import { useEffect, useState } from "react";
import { Plus, BookOpen } from "lucide-react";
import type { Profile } from "./auth-api";
import { usePromiseToast, useToast } from "./toast-context";
import { ledgerRequest } from "./ledger-api";
import { formatMoney } from "./money";
import LedgerApprovals from "./LedgerApprovals";
import ReportDownloads from "./ReportDownloads";

type Entry = { id: string; memberId: string; memberNumber: string; fullName: string; type: string; component: string; amount: string;
  effectiveDate: string; reference: string; narration: string | null; reversalOf: string | null; reversed: boolean; canReverse: boolean };
type Member = { id: string; memberNumber: string; fullName: string };
type Year = { year: number; status: string };
export default function LiveLedger({ profile, asAt }: { profile: Profile; asAt: string }) {
  const canRead = profile.permissions.includes("LEDGER_READ");
  const canPost = profile.permissions.includes("LEDGER_POST");
  const canClose = profile.permissions.includes("FISCAL_YEAR_CLOSE");
  const [from, setFrom] = useState(`${asAt.slice(0, 4)}-01-01`);
  const [entries, setEntries] = useState<Entry[]>([]);
  const [page, setPage] = useState(0); const [hasNext, setHasNext] = useState(false);
  const [retry, setRetry] = useState(0); const [loading, setLoading] = useState(true); const [failed, setFailed] = useState(false);
  const [formOpen, setFormOpen] = useState(false); const [busy, setBusy] = useState(false);
  const [search, setSearch] = useState(""); const [members, setMembers] = useState<Member[]>([]); const [memberId, setMemberId] = useState("");
  const [memberFailed, setMemberFailed] = useState(false);
  const [amount, setAmount] = useState(""); const [type, setType] = useState("ADDITION");
  const [reverse, setReverse] = useState<Entry | null>(null); const [reason, setReason] = useState("");
  const [years, setYears] = useState<Year[]>([]); const [closeYear, setCloseYear] = useState(""); const [closeConfirm, setCloseConfirm] = useState(false);
  const toast = useToast(); const promiseToast = usePromiseToast();
  const validRange = from >= "2000-01-01" && from <= asAt;
  const changed = () => setRetry(value => value + 1);
  useEffect(() => {
    if (!canRead || !validRange) { setLoading(false); return; }
    const controller = new AbortController(); setLoading(true); setFailed(false);
    ledgerRequest<{ entries: Entry[]; hasNext: boolean }>(`?from=${from}&to=${asAt}&page=${page}`, "GET", undefined, controller.signal)
      .then(data => { if (!controller.signal.aborted) { setEntries(data.entries); setHasNext(data.hasNext); } })
      .catch(error => { if (!controller.signal.aborted) { setFailed(true); toast("Could not load ledger", { tone: "error", detail: error instanceof Error ? error.message : "Please retry." }); } })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [canRead, validRange, from, asAt, page, retry, toast]);
  useEffect(() => {
    if (!canRead) return;
    const controller = new AbortController();
    ledgerRequest<Year[]>("/fiscal-years", "GET", undefined, controller.signal).then(setYears).catch(() => { if (!controller.signal.aborted) toast("Could not load fiscal years", { tone: "error" }); });
    return () => controller.abort();
  }, [canRead, retry, toast]);
  useEffect(() => {
    if (!formOpen || !canPost) return;
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      setMemberFailed(false);
      fetch(`/api/v1/members?search=${encodeURIComponent(search)}&size=25`, { credentials: "same-origin", signal: controller.signal })
        .then(async response => { if (!response.ok) throw new Error(); return response.json() as Promise<{ items: Member[] }>; })
        .then(data => { if (!controller.signal.aborted) setMembers(data.items); }).catch(() => { if (!controller.signal.aborted) { setMemberFailed(true); setMembers([]); } });
    }, 250);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [formOpen, canPost, search]);
  const mutation = async (promise: Promise<unknown>, success: string) => {
    setBusy(true);
    try { await promiseToast(promise, { loading: "Sending for approval…", success, error: "Could not submit request" });
      setFormOpen(false); setAmount(""); setMemberId(""); setReverse(null); setCloseConfirm(false); setCloseYear(""); changed(); }
    catch { /* Promise toast includes the server error. */ }
    finally { setBusy(false); }
  };
  if (!canRead) return <section className="surface investment-panel"><h1>Ledger access required</h1><p>Your account cannot view union ledger entries.</p></section>;
  return <>
    <header className="page-heading"><div><span className="eyebrow">Harbour Credit Union</span><h1>Ledger</h1><p>Approved movements, with a permanent record of every correction.</p></div>
      {canPost && <button className="primary-button" onClick={() => setFormOpen(value => !value)}><Plus size={18} style={{ transform: formOpen ? "rotate(45deg)" : undefined, transition: "transform 180ms" }} />{formOpen ? "Close form" : "Add contribution"}</button>}</header>
    {formOpen && <section className="surface investment-panel"><h2>Manual contribution</h2><p>Seed and addition entries increase principal only after a different person approves, on the approval date.</p>
      <form onSubmit={event => { event.preventDefault(); void mutation(ledgerRequest("/contributions", "POST", { memberId, type, amount }), "Contribution approval requested"); }}>
        <div className="investment-form-grid"><label>Find member<input value={search} onChange={event => { setSearch(event.target.value); setMemberId(""); }} placeholder="Name or member number" /></label>
          <label>Member<select required value={memberId} onChange={event => setMemberId(event.target.value)}><option value="">Choose member</option>{members.map(member => <option key={member.id} value={member.id}>{member.fullName} · {member.memberNumber}</option>)}</select></label>
          <label>Contribution type<select value={type} onChange={event => setType(event.target.value)}><option value="SEED">Seed capital</option><option value="ADDITION">Additional contribution</option></select></label>
          <label>Amount (GH₵)<input required type="number" min="0.01" step="0.01" value={amount} onChange={event => setAmount(event.target.value)} /></label></div>
        {memberFailed && <p role="alert">Members could not be loaded. Close and reopen this form to retry.</p>}
        <button className="primary-button" disabled={busy || !memberId}>Request contribution approval</button>
      </form></section>}
    <section className="surface investment-panel"><div className="card-title-row"><div><h2>Posted entries</h2><p>Through {asAt}. 50 entries per page.</p></div><label>From date<input type="date" min="2000-01-01" max={asAt} value={from} onChange={event => { setFrom(event.target.value); setPage(0); }} /></label></div>
      {validRange && profile.permissions.includes("REPORT_EXPORT") && <ReportDownloads path={`/api/v1/reports/ledger?from=${from}&to=${asAt}`} filename={`ledger-${from}-${asAt}`} />}
      {!validRange ? <p role="alert">Choose a valid start date on or before {asAt}.</p> : loading ? <p role="status">Loading ledger…</p> : failed ? <button className="secondary-button" onClick={changed}>Try again</button> : !entries.length ? <div className="dashboard-empty"><BookOpen size={28} /><h3>No entries in this period</h3><p>Approved contributions and investment income will appear here.</p></div> : <>
        {entries.map(entry => <article className="ledger-entry" key={entry.id}><div className="card-title-row"><div><h3>{entry.fullName}</h3><small>{entry.memberNumber} · {entry.effectiveDate}</small></div><strong className={Number(entry.amount) < 0 ? "negative" : ""}>{formatMoney(entry.amount)}</strong></div>
          <p>{entry.type.replaceAll("_", " ")} · {entry.component}{entry.reversed ? " · Reversed" : ""}</p><small>Reference: {entry.reference}</small>
          {entry.narration && <p>{entry.narration}</p>}{entry.reversalOf && <small>Compensates entry {entry.reversalOf}</small>}
          {entry.canReverse && <button className="text-button" disabled={busy} onClick={() => { setReverse(entry); setReason(""); }}>Request reversal</button>}
        </article>)}
        <div className="investment-action-bar"><button className="secondary-button" disabled={page === 0 || loading} onClick={() => setPage(value => value - 1)}>Previous</button><span>Page {page + 1}</span><button className="secondary-button" disabled={!hasNext || loading} onClick={() => setPage(value => value + 1)}>Next</button></div>
      </>}
      {reverse && <form className="investment-edit" onSubmit={event => { event.preventDefault(); void mutation(ledgerRequest(`/${reverse.id}/reverse`, "POST", { reason }), "Reversal approval requested"); }}>
        <h3>Reverse {formatMoney(reverse.amount)} for {reverse.fullName}?</h3><p>The original entry remains visible. Approval adds its exact negative amount; committed investment funds cannot be reversed.</p>
        <label>Reason<textarea required maxLength={1000} rows={3} value={reason} onChange={event => setReason(event.target.value)} /></label><div className="investment-action-bar"><button className="primary-button" disabled={busy}>Request reversal approval</button><button type="button" className="text-button" onClick={() => setReverse(null)}>Cancel</button></div>
      </form>}
    </section>
    {canClose && <section className="surface investment-panel"><h2>Year-end close</h2><p>A closed year prevents further posting and preserves each member's balance forward.</p><label>Completed open fiscal year<select value={closeYear} onChange={event => { setCloseYear(event.target.value); setCloseConfirm(false); }}><option value="">Choose year</option>{years.filter(year => year.status === "OPEN" && year.year < new Date().getUTCFullYear()).map(year => <option key={year.year}>{year.year}</option>)}</select></label>
      {closeYear && (closeConfirm ? <div className="investment-edit"><p>Request close for {closeYear}? A different approver must review it. Historical posting to this year will be blocked.</p><button className="primary-button" disabled={busy} onClick={() => void mutation(ledgerRequest(`/fiscal-years/${closeYear}/close`, "POST"), "Year-end close approval requested")}>Confirm close request</button><button className="text-button" onClick={() => setCloseConfirm(false)}>Cancel</button></div> : <button className="secondary-button" onClick={() => setCloseConfirm(true)}>Review year-end close</button>)}
    </section>}
    <LedgerApprovals key={retry} profile={profile} onChanged={changed} />
  </>;
}
