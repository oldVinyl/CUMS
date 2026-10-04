import Pagination from "./Pagination";
import { Fragment, useEffect, useState } from "react";
import { Download, ShieldCheck } from "lucide-react";
import type { Profile } from "./auth-api";
import { usePromiseToast } from "./toast-context";

type FinancialChange = { operation: string; transactionId: string; before: Record<string, string | null> | null; after: Record<string, string | null> | null };
type Event = { id: string; actorId: string | null; actorEmail: string | null; action: string; entityType: string; entityId: string | null; occurredAt: string; requestId: string | null; requestIp?: string | null; financialChange?: FinancialChange | null };
type Filters = { from: string; to: string; action: string; entityType: string; actorId: string; entityId: string };
function parameters(filters: Filters) {
  const query = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => { if (value.trim()) query.set(key, value.trim()); });
  return query.toString();
}
async function check(response: Response) {
  if (!response.ok) {
    const error = await response.json().catch(() => null) as { message?: string } | null;
    throw new Error(error?.message ?? "The audit request could not be completed.");
  }
  return response;
}
function ChangeValues({ change }: { change: FinancialChange }) {
  const keys = [...new Set([...Object.keys(change.before ?? {}), ...Object.keys(change.after ?? {})])].filter(key => change.before?.[key] !== change.after?.[key]);
  return <details><summary>Financial changes ({keys.length})</summary><p>{change.operation === "INSERT" ? "Record created" : change.operation === "DELETE" ? "Record removed" : "Record updated"}. Transaction {change.transactionId}.</p><dl>{keys.map(key => <Fragment key={key}><dt>{key.replaceAll("_", " ")}</dt><dd><span>Before: {change.before?.[key] ?? "None"}</span><br /><span>After: {change.after?.[key] ?? "None"}</span></dd></Fragment>)}</dl></details>;
}
export default function AuditPage({ profile }: { profile: Profile }) {
  const today = new Date().toISOString().slice(0, 10);
  const initial: Filters = { from: today.slice(0, 8) + "01", to: today, action: "", entityType: "", actorId: "", entityId: "" };
  const [draft, setDraft] = useState(initial);
  const [filters, setFilters] = useState(initial);
  const [page, setPage] = useState(0);
  const [events, setEvents] = useState<Event[]>([]);
  const [hasNext, setHasNext] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  const [busy, setBusy] = useState(false);
  const promiseToast = usePromiseToast();
  const allowed = profile.permissions.includes("AUDIT_READ");
  const query = parameters(filters);
  useEffect(() => {
    if (!allowed) return;
    const controller = new AbortController();
    setLoading(true); setError("");
    fetch(`/api/v1/audit?${query}&page=${page}`, { credentials: "same-origin", signal: controller.signal })
      .then(check).then(response => response.json() as Promise<{ events: Event[]; hasNext: boolean }>)
      .then(result => { if (!controller.signal.aborted) { setEvents(result.events); setHasNext(result.hasNext); } })
      .catch(failure => { if (!controller.signal.aborted) { setEvents([]); setHasNext(false); setError(failure.message); } })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [allowed, page, query, retry]);
  const download = async () => {
    setBusy(true);
    try {
      await promiseToast((async () => {
        const response = await check(await fetch(`/api/v1/audit/export.csv?${query}`, { credentials: "same-origin" }));
        const url = URL.createObjectURL(await response.blob());
        const link = document.createElement("a");
        link.href = url; link.download = `audit-${filters.from}-${filters.to}.csv`;
        document.body.appendChild(link); link.click(); link.remove();
        window.setTimeout(() => URL.revokeObjectURL(url), 1000);
        setRetry(value => value + 1);
      })(), { loading: "Preparing audit export…", success: "Audit CSV downloaded", error: "Could not export audit records" });
    } catch { /* Promise toast contains the server response. */ }
    finally { setBusy(false); }
  };
  if (!allowed) return <section className="surface investment-panel"><ShieldCheck size={28} /><h1>Audit access required</h1><p>Your account cannot view union audit records.</p></section>;
  const field = (key: keyof Filters, value: string) => setDraft(current => ({ ...current, [key]: value }));
  return <>
    <header className="page-heading"><div><span className="eyebrow">Harbour Credit Union</span><h1>Audit log</h1><p>Trace recorded actions, actors and affected records. Posted events cannot be edited.</p></div></header>
    <section className="surface investment-panel"><h2>Find events</h2><p>Date filters use Africa/Accra. Event timestamps show your local time. Exports contain up to 5,000 matching events across all pages.</p>
      <form className="investment-form" onSubmit={event => { event.preventDefault(); setFilters({ ...draft }); setPage(0); setRetry(value => value + 1); }}>
        <label>From<input type="date" required min="2000-01-01" max={draft.to || today} value={draft.from} onChange={event => field("from", event.target.value)} /></label>
        <label>Through<input type="date" required min={draft.from || "2000-01-01"} max={today} value={draft.to} onChange={event => field("to", event.target.value)} /></label>
        <label>Action<input placeholder="e.g. PAYMENT_POSTED" maxLength={100} pattern="[A-Z][A-Z0-9_]*" value={draft.action} onChange={event => field("action", event.target.value.toUpperCase())} /></label>
        <label>Entity type<input placeholder="e.g. MEMBER" maxLength={100} pattern="[A-Z][A-Z0-9_]*" value={draft.entityType} onChange={event => field("entityType", event.target.value.toUpperCase())} /></label>
        <label>Actor ID<input placeholder="Optional account UUID" value={draft.actorId} onChange={event => field("actorId", event.target.value)} /></label>
        <label>Entity ID<input placeholder="Optional record UUID" value={draft.entityId} onChange={event => field("entityId", event.target.value)} /></label>
        <button className="primary-button" disabled={loading}>Apply filters</button>
        <button type="button" className="secondary-button" disabled={busy || loading || !!error} onClick={() => void download()}><Download size={16} /> Export applied filters</button>
      </form>
    </section>
    <section className="surface investment-panel"><h2>Recorded events</h2><p>Review event metadata and recorded financial changes. Personal details, free-text notes and private payloads are excluded.</p>
      {loading ? <p role="status">Loading audit records…</p> : error ? <div role="alert"><p>{error}</p><button className="secondary-button" onClick={() => setRetry(value => value + 1)}>Retry</button></div>
        : events.length === 0 ? <p>No events match these filters.</p> : <div className="audit-events">{events.map(event => <article className="payment-record" key={event.id}>
          <h3>{event.action.replaceAll("_", " ")}</h3><time dateTime={event.occurredAt}>{new Date(event.occurredAt).toLocaleString()}</time>
          <dl><dt>Actor</dt><dd>{event.actorEmail ?? "System"}{event.actorId && <small>{event.actorId}</small>}</dd>
            <dt>Entity</dt><dd>{event.entityType}<small>{event.entityId ?? "No specific record"}</small></dd>
            <dt>Event ID</dt><dd>{event.id}</dd>{event.requestId && <><dt>Request ID</dt><dd>{event.requestId}</dd></>}
            {event.requestIp && <><dt>Request IP</dt><dd>{event.requestIp}</dd></>}</dl>
          {event.financialChange && <ChangeValues change={event.financialChange} />}
        </article>)}</div>}
      <Pagination page={page} hasNext={hasNext} loading={loading} onPageChange={setPage} count={events.length} pageSize={50} label="Audit pages" />
    </section>
  </>;
}
