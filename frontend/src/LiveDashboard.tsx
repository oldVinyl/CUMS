import { useEffect, useMemo, useState } from "react";
import { ArrowDownLeft, ArrowUpRight, Search, Users, WalletCards, ChevronDown, RefreshCw } from "lucide-react";
import { NavLink } from "react-router-dom";
import { formatMoney } from "./money";
import { useToast } from "./toast-context";
import ReportDownloads from "./ReportDownloads";

type Figures = { principal: string; income: string; equity: string };
type Row = { memberId: string | null; memberNumber: string | null; fullName: string;
  balanceBf: Figures; ytd: Figures; total: Figures; roePercent: string; equityRatioPercent: string };
type Dashboard = { asAt: string; fiscalYear: number; members: Row[]; totals: Row;
  trend?: { date: string; equity: string }[]; developmentData?: boolean; balanceForwardSnapshot?: { fiscalYear: number; state: string; snapshotCount: number; missingMembers: number; mismatchedMembers: number } };

export default function LiveDashboard({ asAt, canExport = false, onAsAtChange }: { asAt: string; canExport?: boolean; onAsAtChange?: (date: string) => void }) {
  const showToast = useToast();
  const today = new Date().toLocaleDateString("en-CA", { timeZone: "Africa/Accra" });
  const currentYear = Number(today.slice(0, 4));
  const [data, setData] = useState<Dashboard | null>(null);
  const [search, setSearch] = useState("");
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [retry, setRetry] = useState(0);
  const [view, setView] = useState<"total" | "balanceBf" | "ytd">("total");
  const [sort, setSort] = useState("number");
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setFailed(false);
    fetch(`/api/v1/dashboard?asAt=${encodeURIComponent(asAt)}`, {
      credentials: "same-origin", signal: controller.signal,
    }).then(async response => {
      if (!response.ok) {
        const error = await response.json().catch(() => null) as { message?: string } | null;
        throw new Error(error?.message ?? "Dashboard could not be loaded.");
      }
      return response.json() as Promise<Dashboard>;
    }).then(result => { if (!controller.signal.aborted) setData(result); }).catch((error: unknown) => {
      if (controller.signal.aborted) return;
      setData(null); setFailed(true);
      showToast("Could not load dashboard", { tone: "error",
        detail: error instanceof Error ? error.message : "Please retry." });
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [asAt, showToast, retry]);
  const rows = useMemo(() => (data?.members.filter(row =>
    `${row.memberNumber ?? ""} ${row.fullName}`.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase())) ?? [])
    .sort((a, b) => sort === "equity" ? Number(b.total.equity) - Number(a.total.equity)
      : sort === "name" ? a.fullName.localeCompare(b.fullName)
      : (a.memberNumber ?? "").localeCompare(b.memberNumber ?? "")), [data, search, sort]);
  const cards = data ? [
    { label: "Total member equity", value: data.totals.total.equity, detail: `${formatMoney(data.totals.ytd.equity)} movement this year`, icon: WalletCards, color: "navy" },
    { label: "Total principal", value: data.totals.total.principal, detail: `Across ${data.members.length} members`, icon: ArrowDownLeft, color: "teal" },
    { label: "Income earned", value: data.totals.total.income, detail: `${Number(data.totals.roePercent).toFixed(2)}% return on principal`, icon: ArrowUpRight, color: "gold" },
  ] : [];
  return <div className="live-dashboard">
    <header className="page-heading"><div><div className="eyebrow">HARBOUR CREDIT UNION</div><h1>Overview</h1>
      <p>Your union at a glance. Balances as at {new Date(`${asAt}T12:00:00`).toLocaleDateString("en-GH", { day: "numeric", month: "long", year: "numeric" })}.</p></div>
      <div className="investment-action-bar">{onAsAtChange && <label className="dashboard-sort">Fiscal year<select value={Number(asAt.slice(0, 4))} onChange={event => onAsAtChange(Number(event.target.value) === currentYear ? today : `${event.target.value}-12-31`)}>{Array.from({ length: currentYear - 1999 }, (_, index) => currentYear - index).map(year => <option key={year} value={year}>{year}</option>)}</select></label>}
      <NavLink to="/members" className="primary-button"><Users size={18} /> View members</NavLink></div></header>
    {loading && <section className="surface dashboard-empty" role="status">Loading your financial overview…</section>}
    {!loading && failed && <section className="surface dashboard-empty"><h2>Overview unavailable</h2><p>Your figures could not be loaded. Try again in a moment.</p>
      <button className="secondary-button" onClick={() => setRetry(value => value + 1)}><RefreshCw size={16} /> Try again</button></section>}
    {!loading && data && <>
      {data.developmentData && <div className="dev-data-notice"><span className="preview-dot" /><span><strong>Development sample data</strong> Fictitious demo members and ledger entries are included.</span></div>}
      <div className="section-heading"><div><h2>Financial overview</h2><p>Fiscal year {data.fiscalYear}</p></div><span className="quiet-tag">{data.members.length} members</span></div>
      {data.balanceForwardSnapshot && <section className="surface investment-panel"><h2>Balance-forward verification</h2>
        <p className={data.balanceForwardSnapshot.state === "REVIEW_REQUIRED" ? "negative" : undefined}>{data.balanceForwardSnapshot.state === "RECONCILED" ? `${data.balanceForwardSnapshot.fiscalYear} closed snapshots reconcile with the ledger.` : data.balanceForwardSnapshot.state === "REVIEW_REQUIRED" ? `${data.balanceForwardSnapshot.fiscalYear} snapshot review required: ${data.balanceForwardSnapshot.missingMembers} missing members and ${data.balanceForwardSnapshot.mismatchedMembers} mismatched balances.` : data.balanceForwardSnapshot.state === "CORRECTION_OPEN" ? `${data.balanceForwardSnapshot.fiscalYear} is open for correction. Close it again after the reviewed changes.` : `No closed balance-forward snapshot for ${data.balanceForwardSnapshot.fiscalYear}.`}</p>
        <p>Overview figures are calculated from immutable ledger entries through the selected date.</p>
      </section>}
      <div className="stat-grid">{cards.map(({ label, value, detail, icon: Icon, color }) => <div className={`stat-card ${color}`} key={label}>
        <div className="stat-head"><span>{label}</span><span className="stat-icon"><Icon size={19} /></span></div>
        <strong className="stat-value">{formatMoney(value)}</strong><span className="stat-detail">{detail}</span></div>)}</div>
      <div className="overview-grid dashboard-insights">
        <section className="surface activity-card"><div className="card-title-row"><div><h2>Equity over time</h2><p>Posted balances through the selected date</p></div><span className="quiet-tag">{data.fiscalYear}</span></div>
          <EquityChart points={data.trend ?? []} />
        </section>
        <section className="surface dashboard-year-card"><h2>This year's movement</h2><p>From balance forward to today's position</p>
          <dl><div><dt>Balance forward</dt><dd>{formatMoney(data.totals.balanceBf.equity)}</dd></div>
            <div><dt>Principal added</dt><dd>{formatMoney(data.totals.ytd.principal)}</dd></div>
            <div><dt>Income earned</dt><dd>{formatMoney(data.totals.ytd.income)}</dd></div>
            <div className="year-closing"><dt>Closing equity</dt><dd>{formatMoney(data.totals.total.equity)}</dd></div></dl>
          <p className="dashboard-note">ROE compares total income with total principal. Equity ratio shows each member's share of the union.</p>
        </section>
      </div>
      <section className="surface dashboard-sheet">
        <div className="card-title-row"><div><h2>Member balances</h2><p>A clear view of principal, income, and equity.</p></div>
          <label className="search-box"><Search size={18} /><input aria-label="Search members" value={search} onChange={event => setSearch(event.target.value)} placeholder="Name or number" /></label></div>
        {canExport && <ReportDownloads path={`/api/v1/reports/dashboard?asAt=${encodeURIComponent(asAt)}`} filename={`sheet-b-${asAt}`} />}
        <div className="dashboard-tools"><div className="dashboard-view" role="group" aria-label="Balance period">{([["total", "Total balances"], ["balanceBf", "Balance forward"], ["ytd", "Year to date"]] as const).map(([key, label]) =>
          <button key={key} aria-pressed={view === key} onClick={() => setView(key)}>{label}</button>)}</div>
          <label className="dashboard-sort">Sort by<select value={sort} onChange={event => setSort(event.target.value)}><option value="number">Member number</option><option value="name">Name</option><option value="equity">Highest equity</option></select></label></div>
        {rows.length > 0 ? <>
          <div className="dashboard-desktop"><table><thead><tr><th>Member</th><th className="numeric">Principal</th><th className="numeric">Income</th><th className="numeric">Equity</th><th className="numeric">ROE</th><th className="numeric">Share</th></tr></thead>
            <tbody>{rows.map(row => <tr key={row.memberId}><th scope="row"><strong>{row.fullName}</strong><small>{row.memberNumber}</small></th><SummaryCells row={row} view={view} /></tr>)}</tbody>
            <tfoot><tr><th scope="row">All members</th><SummaryCells row={data.totals} view={view} /></tr></tfoot></table></div>
          <div className="dashboard-mobile">{rows.map(row => <details className="dashboard-member-card" key={row.memberId}><summary><span><strong>{row.fullName}</strong><small>{row.memberNumber}</small></span><span><strong>{formatMoney(row[view].equity)}</strong><ChevronDown size={17} /></span></summary>
            <dl><div><dt>Principal</dt><dd>{formatMoney(row[view].principal)}</dd></div><div><dt>Income</dt><dd>{formatMoney(row[view].income)}</dd></div><div><dt>ROE</dt><dd>{row.roePercent}%</dd></div><div><dt>Equity ratio</dt><dd>{row.equityRatioPercent}%</dd></div></dl></details>)}</div>
          <details className="dashboard-full-details"><summary>View full balance breakdown <ChevronDown size={16} /></summary><div className="dashboard-breakdown-grid">{rows.map(row => <article key={row.memberId}><h3>{row.fullName}</h3>{([["balanceBf", "Balance forward"], ["ytd", "Year to date"], ["total", "Total"]] as const).map(([key, label]) => <div key={key}><h4>{label}</h4><p>Principal <strong>{formatMoney(row[key].principal)}</strong></p><p>Income <strong>{formatMoney(row[key].income)}</strong></p><p>Equity <strong>{formatMoney(row[key].equity)}</strong></p></div>)}</article>)}</div></details>
        </> : <div className="dashboard-empty"><Users size={30} /><h3>{search ? "No matching members" : "A fresh start for your union"}</h3><p>{search ? "Try another name or member number." : "Add a member and post their first contribution to see balances here."}</p>{search && <button className="text-button" onClick={() => setSearch("")}>Clear search</button>}</div>}
        {search && <p className="dashboard-filter-note">Totals include all members at this date.</p>}
        <p className="dashboard-note">ROE and equity share use total balances across all periods.</p>
      </section>
    </>}
  </div>;
}
function SummaryCells({ row, view }: { row: Row; view: "total" | "balanceBf" | "ytd" }) {
  return <>{[row[view].principal, row[view].income, row[view].equity].map((value, index) => <td className={`numeric${Number(value) < 0 ? " negative" : ""}`} key={index}>{formatMoney(value)}</td>)}<td className="numeric">{row.roePercent}%</td><td className="numeric">{row.equityRatioPercent}%</td></>;
}
function EquityChart({ points }: { points: { date: string; equity: string }[] }) {
  if (!points.length || points.every(point => Number(point.equity) === 0)) return <div className="dashboard-empty"><WalletCards size={30} /><h3>No equity history yet</h3><p>Your posted contributions and income will build this chart.</p></div>;
  const values = points.map(point => Number(point.equity));
  const minimum = Math.min(0, ...values); const maximum = Math.max(1, ...values); const range = maximum - minimum;
  const coordinates = values.map((value, index) => `${20 + index * 560 / Math.max(1, values.length - 1)},${190 - (value - minimum) / range * 160}`);
  return <div className="live-equity-chart"><div className="trend-range"><span>{formatMoney(String(maximum))}</span><span>{formatMoney(String(minimum))}</span></div>
    <svg viewBox="0 0 600 220" role="img" aria-label={`Equity moved from ${formatMoney(String(values[0]))} to ${formatMoney(String(values[values.length - 1]))}`}><defs><linearGradient id="live-equity-fill" x1="0" x2="0" y1="0" y2="1"><stop stopColor="#407e8c" stopOpacity=".28" /><stop offset="1" stopColor="#407e8c" stopOpacity=".02" /></linearGradient></defs>
      {[30, 110, 190].map(y => <line key={y} x1="20" x2="580" y1={y} y2={y} className="trend-grid" />)}
      <polygon points={`20,190 ${coordinates.join(" ")} ${20 + (values.length - 1) * 560 / Math.max(1, values.length - 1)},190`} fill="url(#live-equity-fill)" />
      <polyline points={coordinates.join(" ")} fill="none" stroke="#407e8c" strokeWidth="3" strokeLinejoin="round" />
      {coordinates.map((coordinate, index) => { const [cx, cy] = coordinate.split(","); return <circle key={points[index].date} cx={cx} cy={cy} r="4" fill="#407e8c"><title>{points[index].date}: {formatMoney(points[index].equity)}</title></circle>; })}</svg>
    <div className="trend-months">{points.filter((_, index) => index === 0 || index === points.length - 1 || index % 3 === 0).map(point => <span key={point.date}>{new Date(`${point.date}T12:00:00`).toLocaleDateString("en-GH", { month: "short" })}</span>)}</div>
  </div>;
}
