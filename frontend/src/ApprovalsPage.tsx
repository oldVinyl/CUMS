import { NavLink } from "react-router-dom";
import type { Profile } from "./auth-api";
import LedgerApprovals from "./LedgerApprovals";

export default function ApprovalsPage({ profile }: { profile: Profile }) {
  return <><header className="page-heading"><div><span className="eyebrow">Harbour Credit Union</span><h1>Approvals</h1><p>Review the exact impact before a money movement is posted.</p></div></header>
    <LedgerApprovals profile={profile} />
    {profile.permissions.includes("INVESTMENT_READ") && <section className="surface investment-panel"><h2>Investment approvals</h2><p>Review posting allocations, actual maturity, rollover, settlement and reversal alongside the full cycle.</p><NavLink className="secondary-button" to="/investments">Review investment requests</NavLink></section>}
  </>;
}
