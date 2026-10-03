import { useCallback, useEffect, useState } from "react";
import { Landmark, Plus } from "lucide-react";
import type { Profile } from "./auth-api";
import { csrfCookie } from "./auth-api";
import { formatMoney } from "./money";
import { usePromiseToast, useToast } from "./toast-context";

const money = (amount: string) => formatMoney(amount);

type Summary = {
  id: string;
  tenor: string;
  institution: string | null;
  referenceNumber: string | null;
  purchaseDate: string;
  maturityDate: string;
  cost: string;
  maturityValue: string;
  actualProceeds: string | null;
  status: string;
};
type Allocation = {
  memberId: string;
  memberNumber: string;
  fullName: string;
  participation: string;
  ratioPercent: string;
  costShare: string;
  maturityValueShare: string;
  incomeShare: string;
  closingBalance: string;
};
type Detail = {
  investment: Summary;
  allocations: Allocation[];
  maturityAllocations: {
    memberId: string;
    actualMaturityShare: string;
    actualIncomeShare: string;
  }[];
  pool: string;
  costDifference: string;
  expectedIncome: string;
  expectedClosing: string;
};
type Pending = {
  id: string;
  investmentId: string;
  makerId: string;
  actionType: string;
  institution: string | null;
  purchaseDate: string;
  cost: string;
  maturityValue: string;
  actualProceeds: string | null;
};
type MaturityPreview = {
  actualProceeds: string;
  totalIncome: string;
  allocations: {
    memberId: string;
    actualMaturityShare: string;
    actualIncomeShare: string;
  }[];
};

const businessToday = () =>
  new Date().toLocaleDateString("en-CA", { timeZone: "Africa/Accra" });

async function request<T>(
  path: string,
  method = "GET",
  body?: object,
): Promise<T> {
  const response = await fetch(`/api/v1/investments${path}`, {
    method,
    credentials: "same-origin",
    headers:
      method === "GET"
        ? undefined
        : { "Content-Type": "application/json", "X-CSRF-Token": csrfCookie() },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!response.ok) {
    const error = (await response.json().catch(() => null)) as {
      message?: string;
    } | null;
    throw new Error(
      error?.message ?? "The investment request could not be completed.",
    );
  }
  return response.status === 204
    ? (undefined as T)
    : (response.json() as Promise<T>);
}

export default function LiveInvestments({ profile }: { profile: Profile }) {
  const showToast = useToast();
  const showPromiseToast = usePromiseToast();
  const canRead = profile.permissions.includes("INVESTMENT_READ");
  const canWrite = profile.permissions.includes("INVESTMENT_WRITE");
  const canApprove = profile.permissions.includes("APPROVE") && canRead;
  const [items, setItems] = useState<Summary[]>([]);
  const [pending, setPending] = useState<Pending[]>([]);
  const [detail, setDetail] = useState<Detail | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [rolloverSource, setRolloverSource] = useState<string | null>(null);
  const [rolloverPolicy, setRolloverPolicy] = useState("ROLL_PRINCIPAL_AND_INCOME");
  const [settleConfirm, setSettleConfirm] = useState(false);
  const [discardConfirm, setDiscardConfirm] = useState(false);
  const [discardReason, setDiscardReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [decisionComment, setDecisionComment] = useState("");
  const [editing, setEditing] = useState<Allocation | null>(null);
  const [editAmount, setEditAmount] = useState("");
  const [editReason, setEditReason] = useState("");
  const [tenor, setTenor] = useState("91_DAY");
  const [customDays, setCustomDays] = useState("");
  const [institution, setInstitution] = useState("");
  const [referenceNumber, setReferenceNumber] = useState("");
  const [maturityDate, setMaturityDate] = useState("");
  const [cost, setCost] = useState("");
  const [maturityValue, setMaturityValue] = useState("");
  const [actualProceeds, setActualProceeds] = useState("");
  const [maturityPreview, setMaturityPreview] =
    useState<MaturityPreview | null>(null);

  const load = useCallback(async () => {
    if (!canRead) return;
    try {
      const [list, approvals] = await Promise.all([
        request<Summary[]>(""),
        canApprove
          ? request<Pending[]>("/approvals/pending")
          : Promise.resolve([]),
      ]);
      setItems(list);
      setPending(approvals);
    } catch (error) {
      showToast("Could not load investments", {
        detail: error instanceof Error ? error.message : "Please retry.",
        tone: "error",
      });
    } finally {
      setLoaded(true);
    }
  }, [canRead, canApprove, showToast]);
  useEffect(() => {
    void load();
  }, [load]);

  const open = async (id: string) => {
    try {
      const result = await request<Detail>(`/${id}`);
      setEditing(null);
      setDiscardConfirm(false);
      setDiscardReason("");
      setActualProceeds(String(result.investment.maturityValue));
      setMaturityPreview(null);
      setDetail(result);
    } catch (error) {
      showToast("Could not open investment", {
        detail: error instanceof Error ? error.message : "Please retry.",
        tone: "error",
      });
    }
  };

  const create = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setBusy(true);
    try {
      const draft = {
        tenor,
        customDays: tenor === "CUSTOM" ? Number(customDays) : null,
        institution,
        referenceNumber,
        purchaseDate: businessToday(),
        maturityDate,
        cost,
        maturityValue,
      };
      const result = await showPromiseToast(
        rolloverSource
          ? request<{ id: string }>(`/${rolloverSource}/rollover-draft`, "POST",
              { policy: rolloverPolicy, draft })
          : request<{ id: string }>("", "POST", draft),
        {
          loading: "Creating investment draft…",
          success: "Draft created",
          error: "Could not create draft",
        },
      );
      setFormOpen(false);
      setRolloverSource(null);
      await load();
      await open(result.id);
    } catch {
      /* Promise toast shows the server error. */
    } finally {
      setBusy(false);
    }
  };

  const submit = async () => {
    if (!detail) return;
    setBusy(true);
    try {
      await showPromiseToast(
        request(`/${detail.investment.id}/submit`, "POST"),
        {
          loading: "Sending for approval…",
          success: "Approval requested",
          error: "Could not request approval",
        },
      );
      await load();
    } catch {
      /* The toast includes the error. */
    } finally {
      setBusy(false);
    }
  };

  const saveParticipation = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!detail || !editing) return;
    setBusy(true);
    try {
      await showPromiseToast(
        request(
          `/${detail.investment.id}/allocations/${editing.memberId}`,
          "POST",
          { amount: editAmount, reason: editReason },
        ),
        {
          loading: "Updating participation…",
          success: "Participation updated",
          error: "Could not update participation",
        },
      );
      setEditing(null);
      setEditReason("");
      await open(detail.investment.id);
    } catch {
      /* The toast includes the error. */
    } finally {
      setBusy(false);
    }
  };

  const submitMaturity = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!detail) return;
    setBusy(true);
    try {
      await showPromiseToast(
        request(`/${detail.investment.id}/mature`, "POST", { actualProceeds }),
        {
          loading: "Sending maturity for approval…",
          success: "Maturity approval requested",
          error: "Could not request maturity approval",
        },
      );
      await load();
    } catch {
      /* The toast includes the error. */
    } finally {
      setBusy(false);
    }
  };

  const submitSettlement = async () => {
    if (!detail) return;
    setBusy(true);
    try {
      await showPromiseToast(request(`/${detail.investment.id}/settle`, "POST"), {
        loading: "Sending settlement for approval…",
        success: "Settlement approval requested",
        error: "Could not request settlement approval",
      });
      setSettleConfirm(false);
      await load();
    } catch {
      /* The toast includes the error. */
    } finally {
      setBusy(false);
    }
  };

  const cancelApproval = async (item: Pending) => {
    setBusy(true);
    try {
      await showPromiseToast(request(`/approvals/${item.id}/cancel`, "POST"), {
        loading: "Cancelling approval request…",
        success: "Approval request cancelled",
        error: "Could not cancel approval request",
      });
      await load();
    } catch {
      /* The toast includes the error. */
    } finally { setBusy(false); }
  };

  const discardDraft = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!detail) return;
    setBusy(true);
    try {
      await showPromiseToast(
        request(`/${detail.investment.id}/discard`, "POST", { reason: discardReason }),
        { loading: "Discarding draft…", success: "Draft discarded", error: "Could not discard draft" },
      );
      setDiscardConfirm(false);
      setDiscardReason("");
      await load();
      await open(detail.investment.id);
    } catch {
      /* The toast includes the error. */
    } finally {
      setBusy(false);
    }
  };

  const previewActual = async (id: string, amount: string) => {
    try {
      setMaturityPreview(
        await request<MaturityPreview>(
          `/${id}/maturity-preview?actualProceeds=${encodeURIComponent(amount)}`,
        ),
      );
    } catch (error) {
      showToast("Could not preview actual maturity", {
        detail: error instanceof Error ? error.message : "Please retry.",
        tone: "error",
      });
    }
  };

  const reviewPending = async (item: Pending) => {
    await open(item.investmentId);
    if (
      item.actionType === "INVESTMENT_MATURE" &&
      item.actualProceeds != null
    ) {
      setActualProceeds(String(item.actualProceeds));
      await previewActual(item.investmentId, String(item.actualProceeds));
    }
  };

  const decide = async (item: Pending, decision: "APPROVE" | "REJECT") => {
    if (decision === "REJECT" && !decisionComment.trim()) {
      showToast("Add a reason for rejection", { tone: "error" });
      return;
    }
    setBusy(true);
    try {
      await showPromiseToast(
        request(`/approvals/${item.id}/decide`, "POST", {
          decision,
          comment: decisionComment.trim() || null,
        }),
        {
          loading:
            decision === "APPROVE"
              ? "Approving investment…"
              : "Rejecting request…",
          success:
            decision === "APPROVE"
              ? item.actionType === "INVESTMENT_MATURE"
                ? "Maturity posted"
                : item.actionType === "INVESTMENT_SETTLE"
                  ? "Investment settled"
                : "Investment posted"
              : "Request rejected",
          error: "Could not record decision",
        },
      );
      setDecisionComment("");
      await load();
      if (detail?.investment.id === item.investmentId)
        await open(item.investmentId);
    } catch {
      /* The toast includes the error. */
    } finally {
      setBusy(false);
    }
  };

  if (!canRead)
    return (
      <div className="surface empty-page">
        <Landmark size={34} />
        <h2>Investments are restricted</h2>
        <p>Your role does not include investment access.</p>
      </div>
    );

  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">HARBOUR CREDIT UNION</div>
          <h1>Investments</h1>
          <p>Plan, review, and approve treasury bill allocations.</p>
        </div>
        {canWrite && (
          <button
            className="primary-button"
            onClick={() => { setRolloverSource(null); setFormOpen(!formOpen); }}
          >
            <Plus size={18} /> New draft
          </button>
        )}
      </div>
      {formOpen && (
        <section className="surface member-form">
          <h2>{rolloverSource ? "New rollover draft" : "New treasury bill draft"}</h2>
          <p>
            {rolloverSource
              ? "Participation starts from the matured cycle’s eligible member balances. Review each share before approval."
              : "Participation uses today’s uninvested member balances. You can review every share before asking for approval."}
          </p>
          <form onSubmit={create}>
            <div className="member-form-grid">
              {rolloverSource && <label>Rollover policy
                <select value={rolloverPolicy} onChange={event => setRolloverPolicy(event.target.value)}>
                  <option value="ROLL_PRINCIPAL_AND_INCOME">Principal and income</option>
                  <option value="ROLL_PRINCIPAL_ONLY">Principal only</option>
                </select>
              </label>}
              <label>
                Tenor
                <select
                  value={tenor}
                  onChange={(event) => setTenor(event.target.value)}
                >
                  <option value="91_DAY">91 days</option>
                  <option value="182_DAY">182 days</option>
                  <option value="364_DAY">364 days</option>
                  <option value="CUSTOM">Custom</option>
                </select>
              </label>
              {tenor === "CUSTOM" && (
                <label>
                  Custom days
                  <input
                    type="number"
                    min="1"
                    value={customDays}
                    onChange={(event) => setCustomDays(event.target.value)}
                    required
                  />
                </label>
              )}
              <label>
                Institution or broker
                <input
                  value={institution}
                  maxLength={200}
                  onChange={(event) => setInstitution(event.target.value)}
                />
              </label>
              <label>
                Reference
                <input
                  value={referenceNumber}
                  maxLength={100}
                  onChange={(event) => setReferenceNumber(event.target.value)}
                />
              </label>
              <label>
                Maturity date
                <input
                  type="date"
                  required
                  value={maturityDate}
                  min={new Date(Date.now() + 86400000)
                    .toISOString()
                    .slice(0, 10)}
                  onChange={(event) => setMaturityDate(event.target.value)}
                />
              </label>
              <label>
                Cost (GH₵)
                <input
                  type="number"
                  min="0.01"
                  step="0.01"
                  required
                  value={cost}
                  onChange={(event) => setCost(event.target.value)}
                />
              </label>
              <label>
                Expected maturity value (GH₵)
                <input
                  type="number"
                  min="0"
                  step="0.01"
                  required
                  value={maturityValue}
                  onChange={(event) => setMaturityValue(event.target.value)}
                />
              </label>
            </div>
            <button className="primary-button" disabled={busy}>
              {rolloverSource ? "Create rollover preview" : "Create and preview"}
            </button>
          </form>
        </section>
      )}
      <section className="surface investment-panel">
        <div className="card-title-row">
          <div>
            <h2>Investment cycles</h2>
            <p>
              {loaded ? `${items.length} recorded cycles` : "Loading cycles…"}
            </p>
          </div>
        </div>
        {loaded && items.length === 0 ? (
          <p className="investment-empty">
            No investments yet. Create a draft when member funds are ready.
          </p>
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Institution</th>
                  <th>Purchase</th>
                  <th>Maturity</th>
                  <th>Status</th>
                  <th className="numeric">Cost</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {items.map((item) => (
                  <tr key={item.id}>
                    <td>
                      <strong>{item.institution || "Treasury bill"}</strong>
                      <small>
                        {item.referenceNumber || item.tenor.replace("_", " ")}
                      </small>
                    </td>
                    <td>{item.purchaseDate}</td>
                    <td>{item.maturityDate}</td>
                    <td>
                      <span className="quiet-tag">{item.status}</span>
                    </td>
                    <td className="numeric">{money(item.cost)}</td>
                    <td>
                      <button
                        className="text-button"
                        onClick={() => void open(item.id)}
                      >
                        View allocation
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
      {detail && (
        <section className="surface investment-panel">
          <div className="card-title-row">
            <div>
              <h2>Allocation preview</h2>
              <p>
                {detail.investment.institution || "Treasury bill"} ·{" "}
                {detail.investment.status}
              </p>
            </div>
            <button className="text-button" onClick={() => setDetail(null)}>
              Close
            </button>
          </div>
          <div className="investment-totals">
            <div>
              <span>Participation pool</span>
              <strong>{money(detail.pool)}</strong>
            </div>
            <div>
              <span>Purchase cost</span>
              <strong>{money(detail.investment.cost)}</strong>
            </div>
            <div>
              <span>Cost less pool</span>
              <strong>{money(detail.costDifference)}</strong>
            </div>
            <div>
              <span>Expected maturity</span>
              <strong>{money(detail.investment.maturityValue)}</strong>
            </div>
          </div>
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Member</th>
                  <th className="numeric">Participation</th>
                  <th className="numeric">Ratio</th>
                  <th className="numeric">Cost share</th>
                  <th className="numeric">Maturity share</th>
                  <th className="numeric">Income</th>
                  <th className="numeric">Closing</th>
                  {canWrite && detail.investment.status === "DRAFT" && <th />}
                </tr>
              </thead>
              <tbody>
                {detail.allocations.map((row) => (
                  <tr key={row.memberId}>
                    <td>
                      <strong>{row.fullName}</strong>
                      <small>{row.memberNumber}</small>
                    </td>
                    <td className="numeric">{money(row.participation)}</td>
                    <td className="numeric">
                      {Number(row.ratioPercent).toFixed(2)}%
                    </td>
                    <td className="numeric">{money(row.costShare)}</td>
                    <td className="numeric">{money(row.maturityValueShare)}</td>
                    <td className="numeric">{money(row.incomeShare)}</td>
                    <td className="numeric">{money(row.closingBalance)}</td>
                    {canWrite && detail.investment.status === "DRAFT" && (
                      <td>
                        <button
                          className="text-button"
                          onClick={() => {
                            setEditing(row);
                            setEditAmount(String(row.participation));
                            setEditReason("");
                          }}
                        >
                          Edit
                        </button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr>
                  <th>Total</th>
                  <td className="numeric">{money(detail.pool)}</td>
                  <td className="numeric">100.00%</td>
                  <td className="numeric">{money(detail.investment.cost)}</td>
                  <td className="numeric">{money(detail.investment.maturityValue)}</td>
                  <td className="numeric">{money(detail.expectedIncome)}</td>
                  <td className="numeric">{money(detail.expectedClosing)}</td>
                  {canWrite && detail.investment.status === "DRAFT" && <td />}
                </tr>
              </tfoot>
            </table>
          </div>
          {editing && detail.investment.status === "DRAFT" && (
            <form className="investment-edit" onSubmit={saveParticipation}>
              <strong>Edit {editing.fullName}</strong>
              <p>
                Enter zero to exclude this member. A reason is kept with the
                draft.
              </p>
              <div className="investment-edit-fields">
                <label>
                  Participation (GH₵)
                  <input
                    type="number"
                    min="0"
                    step="0.01"
                    required
                    value={editAmount}
                    onChange={(event) => setEditAmount(event.target.value)}
                  />
                </label>
                <label>
                  Reason
                  <input
                    value={editReason}
                    maxLength={1000}
                    required
                    onChange={(event) => setEditReason(event.target.value)}
                  />
                </label>
              </div>
              <button className="primary-button" disabled={busy}>
                Save participation
              </button>
              <button
                type="button"
                className="text-button"
                onClick={() => setEditing(null)}
              >
                Cancel
              </button>
            </form>
          )}
          {canWrite && detail.investment.status === "DRAFT" && (
            <div className="investment-action-bar"><button
              className="primary-button investment-action"
              disabled={
                busy ||
                pending.some(
                  (item) => item.investmentId === detail.investment.id,
                )
              }
              onClick={() => void submit()}
            >
              Request approval
            </button><button className="secondary-button" disabled={busy || pending.some(
              (item) => item.investmentId === detail.investment.id,
            )} onClick={() => setDiscardConfirm(true)}>Discard draft</button></div>
          )}
          {discardConfirm && detail.investment.status === "DRAFT" && (
            <form className="investment-edit" onSubmit={discardDraft}>
              <strong>Discard this draft?</strong>
              <p>This removes its rollover reservation and keeps an audit record.</p>
              <label>Reason<input required maxLength={1000} value={discardReason}
                onChange={(event) => setDiscardReason(event.target.value)} /></label>
              <div className="investment-action-bar">
                <button className="primary-button" disabled={busy}>Discard draft</button>
                <button type="button" className="text-button" onClick={() => setDiscardConfirm(false)}>Cancel</button>
              </div>
            </form>
          )}
          {canWrite &&
            detail.investment.status === "POSTED" &&
            detail.investment.maturityDate <= businessToday() && (
              <form className="investment-edit" onSubmit={submitMaturity}>
                <strong>Record actual maturity</strong>
                <p>
                  The proceeds and member income will post only after a second
                  person approves.
                </p>
                <div className="investment-edit-fields">
                  <label>
                    Actual proceeds (GH₵)
                    <input
                      type="number"
                      min="0"
                      step="0.01"
                      required
                      value={actualProceeds}
                      placeholder={String(detail.investment.maturityValue)}
                      onChange={(event) => {
                        setActualProceeds(event.target.value);
                        setMaturityPreview(null);
                      }}
                    />
                  </label>
                </div>
                <button
                  type="button"
                  className="secondary-button"
                  disabled={!actualProceeds}
                  onClick={() =>
                    void previewActual(detail.investment.id, actualProceeds)
                  }
                >
                  Preview income
                </button>
                <button
                  className="primary-button"
                  disabled={
                    busy ||
                    pending.some(
                      (item) => item.investmentId === detail.investment.id,
                    )
                  }
                  type="submit"
                >
                  Request maturity approval
                </button>
              </form>
            )}
          {maturityPreview && (
            <div className="investment-maturity">
              <h3>Actual proceeds preview</h3>
              <p>
                Proceeds:{" "}
                <strong>{money(maturityPreview.actualProceeds)}</strong> · Total
                income: <strong>{money(maturityPreview.totalIncome)}</strong>
              </p>
              <div className="table-scroll">
                <table>
                  <thead>
                    <tr>
                      <th>Member</th>
                      <th className="numeric">Actual share</th>
                      <th className="numeric">Income</th>
                    </tr>
                  </thead>
                  <tbody>
                    {maturityPreview.allocations.map((row) => (
                      <tr key={row.memberId}>
                        <td>
                          {detail.allocations.find(
                            (item) => item.memberId === row.memberId,
                          )?.fullName ?? row.memberId}
                        </td>
                        <td className="numeric">
                          {money(row.actualMaturityShare)}
                        </td>
                        <td className="numeric">
                          {money(row.actualIncomeShare)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
          {["MATURED", "ROLLED_OVER", "SETTLED"].includes(detail.investment.status) && (
            <div className="investment-maturity">
              <h3>Actual maturity</h3>
              <p>
                Total proceeds:{" "}
                <strong>{money(detail.investment.actualProceeds ?? "0")}</strong>
              </p>
              <div className="table-scroll">
                <table>
                  <thead>
                    <tr>
                      <th>Member</th>
                      <th className="numeric">Actual share</th>
                      <th className="numeric">Posted income</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.maturityAllocations.map((row) => (
                      <tr key={row.memberId}>
                        <td>
                          {detail.allocations.find(
                            (item) => item.memberId === row.memberId,
                          )?.fullName ?? row.memberId}
                        </td>
                        <td className="numeric">
                          {money(row.actualMaturityShare)}
                        </td>
                        <td className="numeric">
                          {money(row.actualIncomeShare)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {canWrite && detail.investment.status === "MATURED" && <div className="investment-action-bar">
                <button className="primary-button" onClick={() => {
                  setRolloverSource(detail.investment.id);
                  setRolloverPolicy("ROLL_PRINCIPAL_AND_INCOME");
                  setFormOpen(true);
                  setSettleConfirm(false);
                }}>Create rollover draft</button>
                <button className="secondary-button" disabled={busy || pending.some(item => item.investmentId === detail.investment.id && item.actionType === "INVESTMENT_SETTLE")}
                  onClick={() => setSettleConfirm(true)}>Settle without rollover</button>
              </div>}
              {settleConfirm && detail.investment.status === "MATURED" && <div className="investment-settle-confirm">
                <p>Settlement leaves all member funds uninvested. A different approver must confirm it.</p>
                <button className="primary-button" disabled={busy} onClick={() => void submitSettlement()}>Request settlement approval</button>
                <button className="text-button" onClick={() => setSettleConfirm(false)}>Cancel</button>
              </div>}
            </div>
          )}
        </section>
      )}
      {canApprove && pending.length > 0 && (
        <section className="surface investment-panel">
          <h2>Awaiting approval</h2>
          <p>
            Review the allocation above before deciding. You cannot approve your
            own request.
          </p>
          {pending.map((item) => (
            <div className="investment-approval" key={item.id}>
              <div>
                <strong>{item.institution || "Treasury bill"}</strong>
                <span>
                  {item.actionType === "INVESTMENT_MATURE"
                    ? `Maturity · ${money(item.actualProceeds ?? "0")} actual proceeds`
                    : item.actionType === "INVESTMENT_SETTLE"
                      ? "Settlement · no rollover"
                    : `Posting · ${money(item.cost)} cost · ${money(item.maturityValue)} expected value`}
                </span>
              </div>
              <button
                className="text-button"
                onClick={() => void reviewPending(item)}
              >
                Review
              </button>
              <button
                className="secondary-button"
                disabled={busy || item.makerId === profile.userId}
                onClick={() => void decide(item, "APPROVE")}
              >
                Approve
              </button>
              <button
                className="secondary-button"
                disabled={busy || item.makerId === profile.userId}
                onClick={() => void decide(item, "REJECT")}
              >
                Reject
              </button>
              {item.makerId === profile.userId && <button
                className="text-button" disabled={busy}
                onClick={() => void cancelApproval(item)}>
                Cancel request
              </button>}
            </div>
          ))}
          <label className="investment-comment">
            Reason for rejection
            <textarea
              value={decisionComment}
              onChange={(event) => setDecisionComment(event.target.value)}
              rows={2}
            />
          </label>
        </section>
      )}
    </>
  );
}
