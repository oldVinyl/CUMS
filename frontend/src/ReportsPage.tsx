import { useEffect, useState } from "react";
import { FileChartColumnIncreasing, Search } from "lucide-react";
import { NavLink } from "react-router-dom";
import type { Profile } from "./auth-api";
import { useToast } from "./toast-context";
import ReportDownloads from "./ReportDownloads";
import MemberStatement from "./MemberStatement";

type Member = { id: string; memberNumber: string; fullName: string };
export default function ReportsPage({ profile, asAt }: { profile: Profile; asAt: string }) {
  const canExport = profile.permissions.includes("REPORT_EXPORT");
  const canStatement = canExport && profile.permissions.includes("LEDGER_READ") && profile.permissions.includes("MEMBER_READ");
  const [search, setSearch] = useState("");
  const [members, setMembers] = useState<Member[]>([]);
  const [selected, setSelected] = useState<Member | null>(null);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  const [retry, setRetry] = useState(0);
  const showToast = useToast();
  useEffect(() => {
    if (!canStatement) return;
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      setLoading(true); setFailed(false);
      fetch(`/api/v1/members?search=${encodeURIComponent(search)}&size=25`, { credentials: "same-origin", signal: controller.signal })
        .then(async response => { if (!response.ok) throw new Error("Please retry loading the member directory."); return response.json() as Promise<{ items: Member[]; hasNext: boolean }>; })
        .then(data => { if (!controller.signal.aborted) setMembers(data.items); })
        .catch(error => { if (!controller.signal.aborted) { setFailed(true); setMembers([]); showToast("Could not load members", { tone: "error", detail: error instanceof Error ? error.message : "Please retry." }); } })
        .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    }, 250);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [canStatement, search, retry, showToast]);
  return <>
    <header className="page-heading"><div><span className="eyebrow">Harbour Credit Union</span><h1>Reports</h1><p>Reliable figures as at {asAt}. Downloads include every member, regardless of overview search filters.</p></div></header>
    {!canExport ? <section className="surface investment-panel"><FileChartColumnIncreasing size={28} /><h2>Report access required</h2><p>Your account does not have permission to export union reports.</p></section> : <>
      {profile.permissions.includes("DASHBOARD_READ_ALL") && <section className="surface investment-panel"><h2>Member equity · Sheet B</h2><p>Balance forward, year to date and total balances, with ROE and equity share.</p>
        <ReportDownloads path={`/api/v1/reports/dashboard?asAt=${asAt}`} filename={`sheet-b-${asAt}`} /></section>}
      {profile.permissions.includes("INVESTMENT_READ") && <section className="surface investment-panel"><h2>Investment allocations · Sheet A</h2><p>Open a cycle to export its member participation, expected allocations and reconciled totals.</p><NavLink className="secondary-button" to="/investments">View investment cycles</NavLink></section>}
      {profile.permissions.includes("MEMBER_READ") && <section className="surface investment-panel"><h2>Member directory</h2><p>Names, member numbers, join dates and current status. Private contact and identity fields are excluded.</p><ReportDownloads path={`/api/v1/reports/members?asAt=${asAt}`} filename={`members-${asAt}`} /></section>}
      {profile.permissions.includes("LEDGER_READ") && <section className="surface investment-panel"><h2>Posted ledger</h2><p>Export posted entries for a selected date range, including correction references.</p><NavLink className="secondary-button" to="/ledger">Open ledger and downloads</NavLink></section>}
      {canStatement && <section className="surface investment-panel"><h2>Member statements</h2><p>Find a member by name or number. Showing up to 25 results; refine your search to find a specific member.</p>
        <label className="search-box"><Search size={18} /><input aria-label="Find member for statement" value={search} onChange={event => { setSearch(event.target.value); setSelected(null); }} placeholder="Name or member number" /></label>
        {loading ? <p role="status">Loading members…</p> : failed ? <button className="secondary-button" onClick={() => setRetry(value => value + 1)}>Try again</button>
          : members.length === 0 ? <p>No matching members.</p> : <label>Member<select value={selected?.id ?? ""} onChange={event => setSelected(members.find(member => member.id === event.target.value) ?? null)}>
            <option value="">Choose a member</option>{members.map(member => <option key={member.id} value={member.id}>{member.fullName} · {member.memberNumber}</option>)}</select></label>}
        {selected && <><h3>{selected.fullName} · {selected.memberNumber}</h3><MemberStatement key={selected.id} memberId={selected.id} asAt={asAt} /></>}
      </section>}
    </>}
  </>;
}
