import HistoricalIncomeCorrection from "./HistoricalIncomeCorrection";
import type { Profile } from "./auth-api";
import { useState } from "react";
import ResponsiveOverlay from "./ResponsiveOverlay";
import { formatMoney } from "./money";
import { usePromiseToast } from "./toast-context";

type Evidence = {
  investment: {
    purchaseDate: string;
    maturityDate: string;
    status: string;
    cost: string;
    maturityValue: string;
    actualProceeds: string | null;
  };
  pool: string;
  expectedIncome: string;
  expectedClosing: string;
  allocations: {
    memberId: string;
    memberNumber: string;
    fullName: string;
    participation: string;
    ratioPercent: string;
    costShare: string;
    maturityValueShare: string;
    incomeShare: string;
    closingBalance: string;
  }[];
  maturityAllocations: {
    memberId: string;
    actualMaturityShare: string;
    actualIncomeShare: string;
  }[];
};
export default function ArchivedInvestmentEvidence({
  investmentId,
  revision,
  profile,
}: {
  investmentId: string;
  revision: number;
  profile?: Profile;
}) {
  const [evidence, setEvidence] = useState<Evidence | null>(null),
    [busy, setBusy] = useState(false);
  const [corrections, setCorrections] = useState<
    {
      id: string;
      recordedAt: string;
      endedOn: string;
      actualProceeds: string | null;
      allocations: Evidence["allocations"];
      maturityAllocations: Evidence["maturityAllocations"];
    }[]
  >([]);
  const toast = usePromiseToast();
  const open = async () => {
    setBusy(true);
    try {
      const value = await toast(
        (async () => {
          const r = await fetch(`/api/v1/investments/${investmentId}/revisions/${revision}`, {
            credentials: "same-origin",
          });
          if (!r.ok)
            throw new Error("Archived investment evidence is unavailable or access is restricted.");
          const evidence = (await r.json()) as Evidence;
          const corrected = await fetch(
            `/api/v1/investments/${investmentId}/revisions/${revision}/corrections`,
            { credentials: "same-origin" },
          );
          if (!corrected.ok) throw new Error("Archived correction history is unavailable.");
          return { evidence, corrections: await corrected.json() };
        })(),
        {
          loading: "Loading archived allocations…",
          success: "Archived allocations ready",
          error: "Could not load archived allocations",
        },
      );
      setCorrections(value.corrections);
      setEvidence(value.evidence);
    } catch {
      /*toast*/
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <button className="secondary-button" disabled={busy} onClick={() => void open()}>
        Inspect revision {revision} allocations
      </button>
      {evidence && (
        <ResponsiveOverlay
          open
          title={`Investment revision ${revision}`}
          onClose={() => setEvidence(null)}
        >
          <section className="investment-panel">
            <h3>Archived cycle evidence</h3>
            <p>
              Original approved figures and dates. This view does not post money or change the
              current cycle. Member display details follow the current authorized directory.
            </p>
            <p>
              {evidence.investment.purchaseDate} to {evidence.investment.maturityDate} ·{" "}
              {evidence.investment.status.replaceAll("_", " ")}
            </p>
            <dl className="member-facts">
              <div>
                <dt>Participation pool</dt>
                <dd>{formatMoney(evidence.pool)}</dd>
              </div>
              <div>
                <dt>Cost</dt>
                <dd>{formatMoney(evidence.investment.cost)}</dd>
              </div>
              <div>
                <dt>Future value</dt>
                <dd>{formatMoney(evidence.investment.maturityValue)}</dd>
              </div>
              <div>
                <dt>Expected income</dt>
                <dd>{formatMoney(evidence.expectedIncome)}</dd>
              </div>
              {evidence.investment.actualProceeds !== null && (
                <div>
                  <dt>Received proceeds</dt>
                  <dd>{formatMoney(evidence.investment.actualProceeds)}</dd>
                </div>
              )}
            </dl>
            {evidence.allocations.map((a) => {
              const received = evidence.maturityAllocations.find((m) => m.memberId === a.memberId);
              return (
                <article className="payment-record" key={a.memberId}>
                  <h4>
                    {a.memberNumber} · {a.fullName}
                  </h4>
                  <p>
                    Participation {formatMoney(a.participation)} · {a.ratioPercent}%
                  </p>
                  <p>
                    Cost {formatMoney(a.costShare)} · Future value{" "}
                    {formatMoney(a.maturityValueShare)}
                  </p>
                  <p>
                    Expected income {formatMoney(a.incomeShare)} · Closing balance{" "}
                    {formatMoney(a.closingBalance)}
                  </p>
                  {received && (
                    <p>
                      Received share {formatMoney(received.actualMaturityShare)} · Earned income{" "}
                      {formatMoney(received.actualIncomeShare)}
                    </p>
                  )}
                </article>
              );
            })}
            <h3>Approved economic corrections</h3>
            {!corrections.length ? (
              <p>No economic corrections to this archived revision.</p>
            ) : (
              corrections.map((c, index) => (
                <details className="investment-edit" key={c.id} open={index === 0}>
                  <summary>
                    {index === 0 ? "Latest corrected figures" : "Earlier correction"} ·{" "}
                    {c.recordedAt}
                  </summary>
                  <p>
                    Later compensation date: {c.endedOn}
                    {c.actualProceeds !== null && (
                      <> · Received proceeds {formatMoney(c.actualProceeds)}</>
                    )}
                  </p>
                  {c.allocations.map((a) => {
                    const received = c.maturityAllocations.find((m) => m.memberId === a.memberId);
                    return (
                      <article className="payment-record" key={a.memberId}>
                        <h4>
                          {a.memberNumber} · {a.fullName}
                        </h4>
                        <p>
                          Participation {formatMoney(a.participation)} · {a.ratioPercent}%
                        </p>
                        <p>
                          Cost {formatMoney(a.costShare)} · Future value{" "}
                          {formatMoney(a.maturityValueShare)}
                        </p>
                        {received && <p>Earned income {formatMoney(received.actualIncomeShare)}</p>}
                      </article>
                    );
                  })}
                </details>
              ))
            )}
            {profile && (
              <HistoricalIncomeCorrection
                compact
                archivedRevision={revision}
                profile={profile}
                investment={{
                  id: investmentId,
                  status: evidence.investment.status,
                  actualProceeds:
                    corrections[0]?.actualProceeds ?? evidence.investment.actualProceeds,
                }}
                onChanged={() => void open()}
              />
            )}
          </section>
        </ResponsiveOverlay>
      )}
    </>
  );
}
