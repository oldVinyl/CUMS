import { useEffect, useMemo, useState } from "react";
import { formatMoney } from "./money";
import { useToast } from "./toast-context";

type Figures = { principal: string; income: string; equity: string };
type Row = {
  memberId: string | null;
  memberNumber: string | null;
  fullName: string;
  balanceBf: Figures;
  ytd: Figures;
  total: Figures;
  roePercent: string;
  equityRatioPercent: string;
};
type Dashboard = { asAt: string; fiscalYear: number; members: Row[]; totals: Row };

export default function LiveDashboard({ asAt }: { asAt: string }) {
  const showToast = useToast();
  const [data, setData] = useState<Dashboard | null>(null);
  const [search, setSearch] = useState("");
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    fetch(`/api/v1/dashboard?asAt=${encodeURIComponent(asAt)}`, {
      credentials: "same-origin",
      signal: controller.signal,
    }).then(async (response) => {
      if (!response.ok) {
        const error = await response.json().catch(() => null) as { message?: string } | null;
        throw new Error(error?.message ?? "Dashboard could not be loaded.");
      }
      return response.json() as Promise<Dashboard>;
    }).then(setData).catch((error: unknown) => {
      if (controller.signal.aborted) return;
      setData(null);
      showToast("Could not load dashboard", {
        tone: "error",
        detail: error instanceof Error ? error.message : "Please retry.",
      });
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [asAt, showToast]);

  const rows = useMemo(() => data?.members.filter((row) =>
    `${row.memberNumber ?? ""} ${row.fullName}`.toLocaleLowerCase()
      .includes(search.trim().toLocaleLowerCase())) ?? [], [data, search]);

  return <div className="live-dashboard">
    <header className="page-heading">
      <div><span className="eyebrow">Ledger dashboard</span><h1>Overview</h1>
        <p>Member balances as at {asAt}. Fiscal year {data?.fiscalYear ?? asAt.slice(0, 4)}.</p></div>
    </header>
    {loading && <section className="surface dashboard-empty">Loading ledger figures…</section>}
    {!loading && data && <>
      <div className="dashboard-summary">
        <div className="surface"><span>Balance forward</span><strong>{formatMoney(data.totals.balanceBf.equity)}</strong></div>
        <div className="surface"><span>Year to date</span><strong>{formatMoney(data.totals.ytd.equity)}</strong></div>
        <div className="surface"><span>Total equity</span><strong>{formatMoney(data.totals.total.equity)}</strong></div>
        <div className="surface"><span>Overall ROE</span><strong>{data.totals.roePercent}%</strong></div>
      </div>
      <section className="surface dashboard-sheet">
        <div className="card-title-row"><div><h2>Member balances</h2><p>Balance forward, year to date, and total from posted ledger entries.</p></div>
          <label className="dashboard-search">Search members<input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Name or number" /></label></div>
        <div className="table-scroll"><table><thead><tr>
          <th rowSpan={2}>Member</th><th colSpan={3}>Balance forward</th><th colSpan={3}>Year to date</th>
          <th colSpan={3}>Total</th><th rowSpan={2}>ROE</th><th rowSpan={2}>Equity ratio</th>
        </tr><tr>{["Principal", "Income", "Equity", "Principal", "Income", "Total", "Principal", "Income", "Equity"].map((label, index) => <th className="numeric" key={`${label}-${index}`}>{label}</th>)}</tr></thead>
          <tbody>{rows.map((row) => <tr key={row.memberId}><th scope="row"><strong>{row.fullName}</strong><small>{row.memberNumber}</small></th>
            <FigureCells row={row} /></tr>)}</tbody>
          <tfoot><tr><th scope="row">Total</th><FigureCells row={data.totals} /></tr></tfoot>
        </table></div>
        {rows.length === 0 && <p className="dashboard-empty">{search ? "No matching members." : "No members at this date."}</p>}
        {search && <p className="dashboard-filter-note">Totals include all members at this date.</p>}
      </section>
    </>}
  </div>;
}

function FigureCells({ row }: { row: Row }) {
  return <>
    {([row.balanceBf, row.ytd, row.total] as Figures[]).flatMap((item) =>
      [item.principal, item.income, item.equity]).map((value, index) =>
      <td className="numeric" key={index}>{formatMoney(value)}</td>)}
    <td className="numeric">{row.roePercent}%</td>
    <td className="numeric">{row.equityRatioPercent}%</td>
  </>;
}
