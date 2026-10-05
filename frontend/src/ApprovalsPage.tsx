import { canSeeRoute } from "./navigation";
import { OrganizationName } from "./OrganizationBranding";
import { NavLink } from "react-router-dom";
import type { Profile } from "./auth-api";
import LedgerApprovals from "./LedgerApprovals";

export default function ApprovalsPage({ profile }: { profile: Profile }) {
  return <><header className="page-heading"><div><span className="eyebrow"><OrganizationName /></span><h1>Approvals</h1><p>Review the exact impact before a money movement is posted.</p></div></header>
    <LedgerApprovals profile={profile} />
    {(profile.permissions.includes("APPROVE") || profile.permissions.includes("MEMBER_IMPORT")) && <section className="surface investment-panel"><h2>Import approvals</h2><p>Review source rows, calculated totals and reasons before creating or compensating an entire batch.</p><NavLink className="secondary-button" to="/imports">Review import batches</NavLink></section>}
    {profile.permissions.includes("APPROVE") && canSeeRoute("/accounts",profile) && <section className="surface investment-panel"><h2>Role-change approvals</h2><p>Review access changes and mandatory reasons before applying new permissions.</p><NavLink className="secondary-button" to="/accounts">Review role requests</NavLink></section>}
    {profile.permissions.includes("APPROVE") && <section className="surface investment-panel"><h2>Payments and reconciliation</h2><p>Review receipts and verified amounts before posting, and compare actual cash and holdings before finalizing reconciliation.</p><div className="investment-action-bar">{canSeeRoute("/payments",profile)&&<NavLink className="secondary-button" to="/payments">Review payment requests</NavLink>}{canSeeRoute("/reconciliation",profile)&&<NavLink className="secondary-button" to="/reconciliation">Review reconciliation requests</NavLink>}</div></section>}
    {profile.permissions.includes("INVESTMENT_READ") && <section className="surface investment-panel"><h2>Investment approvals</h2><p>Review posting allocations, actual maturity, rollover, settlement and reversal alongside the full cycle.</p><NavLink className="secondary-button" to="/investments">Review investment requests</NavLink></section>}
  </>;
}
