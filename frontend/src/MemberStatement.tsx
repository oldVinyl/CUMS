import { useState } from "react";
import ReportDownloads from "./ReportDownloads";

export default function MemberStatement({ memberId, asAt }: { memberId: string; asAt: string }) {
  const [from, setFrom] = useState(`${asAt.slice(0, 4)}-01-01`);
  const valid = from >= "2000-01-01" && from <= asAt;
  return <div className="investment-edit">
    <h3>Member statement</h3><p>Every posted entry, with opening and running principal, income and equity balances. Through {asAt}.</p>
    <label>From date<input type="date" min="2000-01-01" max={asAt} value={from} onChange={event => setFrom(event.target.value)} /></label>
    {valid ? <ReportDownloads path={`/api/v1/reports/members/${memberId}/statement?from=${from}&to=${asAt}`} filename={`statement-${memberId}-${from}-${asAt}`} />
      : <p role="alert">Choose a start date on or before the as-at date.</p>}
  </div>;
}
