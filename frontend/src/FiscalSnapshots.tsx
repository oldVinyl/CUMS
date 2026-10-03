import { useEffect, useState } from "react";
import { ledgerRequest } from "./ledger-api";
import { formatMoney } from "./money";
import { useToast } from "./toast-context";
import Pagination from "./Pagination";

type Snapshot = { memberNumber: string; fullName: string; principal: string; income: string;
  equity: string; revision: number; ledgerEquity: string; reconciled: boolean };
export default function FiscalSnapshots({ years, refresh }: { years: { year: number; status: string }[]; refresh: number }) {
  const [year, setYear] = useState(""); const [page, setPage] = useState(0);
  const [rows, setRows] = useState<Snapshot[]>([]); const [hasNext, setHasNext] = useState(false);
  const [loading, setLoading] = useState(false); const [failed, setFailed] = useState(false); const [retry, setRetry] = useState(0);
  const toast = useToast();
  useEffect(() => {
    if (!year) return;
    const controller = new AbortController(); setLoading(true); setFailed(false);
    ledgerRequest<{ entries: Snapshot[]; hasNext: boolean }>(`/fiscal-years/${year}/snapshots?page=${page}`, "GET", undefined, controller.signal)
      .then(data => { if (!controller.signal.aborted) { setRows(data.entries); setHasNext(data.hasNext); } })
      .catch(error => { if (!controller.signal.aborted) { setFailed(true); toast("Could not load balance forwards", { tone: "error", detail: error instanceof Error ? error.message : "Please retry." }); } })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [year, page, refresh, retry, toast]);
  return <section className="surface investment-panel"><div className="card-title-row"><div><h2>Fiscal balance forwards</h2>
    <p>Compare each year-end snapshot with the immutable ledger. Corrections retain earlier snapshot versions.</p></div>
    <label>Fiscal year<select value={year} onChange={event => { setYear(event.target.value); setPage(0); }}><option value="">Choose year</option>{years.map(item => <option key={item.year} value={item.year}>{item.year} · {item.status === "CLOSED" ? "Closed" : "Open"}</option>)}</select></label></div>
    {!year ? <p>Choose a year to inspect its recorded balance forwards.</p> : loading ? <p role="status">Loading balance forwards…</p> : failed ? <button className="secondary-button" onClick={() => setRetry(value => value + 1)}>Try again</button> : !rows.length ? <div className="dashboard-empty"><h3>No snapshot recorded</h3><p>Balance forwards are created when a completed fiscal year is approved for closing.</p></div> : <>
      {rows.map(row => <article className="ledger-entry" key={row.memberNumber}><div className="card-title-row"><div><h3>{row.fullName}</h3><small>{row.memberNumber} · Revision {row.revision}</small></div><strong>{formatMoney(row.equity)}</strong></div>
        <p>Principal {formatMoney(row.principal)} · Income {formatMoney(row.income)}</p>
        <p className={row.reconciled ? "" : "negative"}>{row.reconciled ? "Reconciled with ledger" : `Review required: ledger equity ${formatMoney(row.ledgerEquity)}`}</p>
      </article>)}
      <Pagination page={page} hasNext={hasNext} loading={loading} onPageChange={setPage} count={rows.length} pageSize={50} label="Balance forward pages" />
    </>}
  </section>;
}
