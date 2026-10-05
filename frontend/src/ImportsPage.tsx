import { OrganizationName } from "./OrganizationBranding";
import { financialFetch } from "./financial-fetch";
import { useEffect, useState } from "react";
import { Download, Upload } from "lucide-react";
import type { Profile } from "./auth-api";
import { csrfCookie } from "./auth-api";
import { formatMoney } from "./money";
import { usePromiseToast } from "./toast-context";
import Pagination from "./Pagination";
type Row = {
  row: number;
  memberNumber: string | null;
  name: string | null;
  amount: string | null;
  effectiveDate: string | null;
  errors: string[];
  details?: Record<string, string>;
};
type Report = {
  kind: string;
  fileHash: string;
  rowCount: number;
  total: string;
  valid: boolean;
  warnings: string[];
  rows: Row[];
};
type Batch = {
  id: string;
  kind: string;
  rowCount: number;
  total: string;
  status: string;
  makerId: string;
  reason: string;
  createdAt: string;
  requestId: string;
  reversalRequestId: string | null;
  approvals: number;
  requiredApprovals: number;
  reversalMakerId: string | null;
  reversalStatus: string | null;
  reversalApprovals: number;
  reversalRequiredApprovals: number;
  reversalReason: string | null;
};
async function api<T>(path: string, body?: object | FormData, signal?: AbortSignal): Promise<T> {
  const response = await financialFetch(`/api/v1/imports${path}`, {
    credentials: "same-origin",
    signal,
    method: body ? "POST" : "GET",
    headers: body
      ? body instanceof FormData
        ? { "X-CSRF-Token": csrfCookie() }
        : { "Content-Type": "application/json", "X-CSRF-Token": csrfCookie() }
      : undefined,
    body: body ? (body instanceof FormData ? body : JSON.stringify(body)) : undefined,
  });
  if (!response.ok) {
    const error = (await response.json().catch(() => null)) as { message?: string } | null;
    throw new Error(error?.message ?? "The import request could not be completed.");
  }
  return response.status === 204 ? (undefined as T) : (response.json() as Promise<T>);
}
function ImportRows({ report }: { report: Report }) {
  const [page, setPage] = useState(0);
  const rows = report.rows.slice(page * 50, (page + 1) * 50);
  return (
    <div className="import-preview">
      <p>
        <strong>{report.rowCount} data rows</strong>
        {report.kind !== "MEMBERS" && (
          <>
            {" "}
            · Exact calculated total <strong>GHS {report.total}</strong>
          </>
        )}
      </p>
      {report.warnings.map((warning) => (
        <p className="import-notice" key={warning}>
          {warning}
        </p>
      ))}
      {rows.map((row) => (
        <article className="payment-record" key={row.row}>
          <div className="payment-record-heading">
            <div>
              <h3>
                Data row {row.row} · {row.memberNumber || "Number generated at approval"}
              </h3>
              {row.name && <p>{row.name}</p>}
              <p>{row.effectiveDate}</p>
            </div>
            {row.amount && <strong>GHS {row.amount}</strong>}
          </div>
          {row.details && (
            <dl className="member-facts">
              {Object.entries(row.details).map(([label, value]) => (
                <div key={label}>
                  <dt>{label}</dt>
                  <dd>{value}</dd>
                </div>
              ))}
            </dl>
          )}
          {row.errors.length > 0 && (
            <ul className="payment-warning">
              {row.errors.map((error) => (
                <li key={error}>{error}</li>
              ))}
            </ul>
          )}
        </article>
      ))}
      <Pagination
        page={page}
        hasNext={(page + 1) * 50 < report.rows.length}
        count={rows.length}
        onPageChange={setPage}
        label="Import preview pages"
      />
      <details className="import-checksum">
        <summary>Source checksum</summary>
        <p>{report.fileHash}</p>
      </details>
    </div>
  );
}
export default function ImportsPage({ profile }: { profile: Profile }) {
  const canImport = profile.permissions.includes("MEMBER_IMPORT");
  const canMembers = canImport && profile.permissions.includes("MEMBER_WRITE");
  const canLedger =
    canImport &&
    profile.permissions.includes("LEDGER_POST") &&
    profile.permissions.includes("LEDGER_BACKDATE");
  const canInvestments =
    canImport &&
    profile.permissions.includes("INVESTMENT_WRITE") &&
    profile.permissions.includes("LEDGER_BACKDATE") &&
    profile.permissions.includes("CYCLE_REPOST");
  const checker = profile.permissions.includes("APPROVE");
  const reader = canImport || checker || profile.permissions.includes("AUDIT_READ");
  const [kind, setKind] = useState(canMembers ? "MEMBERS" : canLedger ? "LEDGER" : "INVESTMENTS");
  const [file, setFile] = useState<File | null>(null);
  const [report, setReport] = useState<Report | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [batches, setBatches] = useState<Batch[]>([]);
  const [page, setPage] = useState(0);
  const [hasNext, setHasNext] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  const [review, setReview] = useState<{ batch: Batch; report: Report } | null>(null);
  const [checked, setChecked] = useState(false);
  const [comment, setComment] = useState("");
  const [reverse, setReverse] = useState<Batch | null>(null);
  const [reverseReason, setReverseReason] = useState("");
  const promiseToast = usePromiseToast();
  useEffect(() => {
    if (!reader) return;
    const controller = new AbortController();
    setLoading(true);
    setError("");
    api<{ batches: Batch[]; hasNext: boolean }>(`?page=${page}`, undefined, controller.signal)
      .then((result) => {
        if (!controller.signal.aborted) {
          setBatches(result.batches);
          setHasNext(result.hasNext);
        }
      })
      .catch((failure) => {
        if (!controller.signal.aborted) {
          setError(failure.message);
          setBatches([]);
          setHasNext(false);
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [reader, page, retry]);
  const action = async (work: Promise<unknown>, success: string) => {
    setBusy(true);
    try {
      await promiseToast(work, {
        loading: "Saving import request…",
        success,
        error: "Could not complete import request",
      });
      setRetry((value) => value + 1);
      setReview(null);
      setReverse(null);
    } catch {
      /* Promise feedback includes the server reason. */
    } finally {
      setBusy(false);
    }
  };
  const upload = async (confirm: boolean) => {
    if (!file || (confirm && !report?.valid)) return;
    const form = new FormData();
    form.set("kind", kind);
    form.set("file", file);
    if (confirm && report) {
      form.set("expectedHash", report.fileHash);
      form.set("reason", reason);
    }
    setBusy(true);
    if (!confirm) setReport(null);
    try {
      if (confirm) {
        await promiseToast(api("", form), {
          loading: "Submitting reviewed batch…",
          success: "Import submitted for independent approval",
          error: "Could not submit import",
        });
        setReport(null);
        setFile(null);
        setReason("");
        setRetry((value) => value + 1);
      } else
        await promiseToast(
          api<Report>("/dry-run", form).then((result) => {
            setReport(result);
            if (!result.valid)
              throw new Error(
                "Fix the row or totals errors shown below.\nNo records were created; run the dry run again after correcting the source.",
              );
            return result;
          }),
          {
            loading: "Checking import rows…",
            success: "Dry run valid — no records were created",
            error: "Import needs attention",
          },
        );
    } catch {
      /* Promise toast contains server feedback. */
    } finally {
      setBusy(false);
    }
  };
  const openReview = async (batch: Batch) => {
    setBusy(true);
    try {
      const result = await promiseToast(api<Report>(`/${batch.id}/review`), {
        loading: "Loading batch impact…",
        success: "Batch ready for review",
        error: "Could not load batch",
      });
      setReview({ batch, report: result });
      setChecked(false);
      setComment("");
    } catch {
      /* Promise toast contains server feedback. */
    } finally {
      setBusy(false);
    }
  };
  if (!reader)
    return (
      <section className="surface investment-panel">
        <h1>Import access required</h1>
        <p>Your account cannot view or submit import batches.</p>
      </section>
    );
  const pendingReverse = review?.batch.reversalStatus === "PENDING";
  const requestId = pendingReverse ? review?.batch.reversalRequestId : review?.batch.requestId;
  const makerId = pendingReverse ? review?.batch.reversalMakerId : review?.batch.makerId;
  const pending = review && (review.batch.status === "PENDING" || pendingReverse);
  return (
    <>
      <header className="page-heading">
        <div>
          <span className="eyebrow"><OrganizationName /></span>
          <h1>Imports</h1>
          <p>Check source rows and totals before submitting a batch for independent approval.</p>
        </div>
      </header>
      {(canMembers || canLedger || canInvestments) && (
        <section className="surface investment-panel">
          <h2>Validate a source file</h2>
          <p>
            One CSV or XLSX worksheet, up to 5 MB and 1,000 data rows. Use ISO dates (YYYY-MM-DD),
            plain decimal amounts and values instead of formulas.
          </p>
          <form
            className="investment-form"
            onSubmit={(event) => {
              event.preventDefault();
              void upload(false);
            }}
          >
            <label>
              Import type
              <select
                value={kind}
                disabled={busy}
                onChange={(event) => {
                  setKind(event.target.value);
                  setReport(null);
                  setFile(null);
                }}
              >
                {canMembers && <option value="MEMBERS">Members</option>}
                {canLedger && <option value="LEDGER">Opening balances and contributions</option>}
                {canInvestments && (
                  <option value="INVESTMENTS">Historical investment and allocations</option>
                )}
              </select>
            </label>
            <a className="secondary-button" href={`/api/v1/imports/template.csv?kind=${kind}`}>
              <Download size={16} /> Download CSV template
            </a>
            <label>
              Source file
              <input
                key={kind + retry}
                required
                type="file"
                accept=".csv,.xlsx"
                disabled={busy}
                onChange={(event) => {
                  setFile(event.target.files?.[0] ?? null);
                  setReport(null);
                }}
              />
            </label>
            <button className="primary-button" disabled={busy || !file}>
              <Upload size={16} /> Run dry run
            </button>
          </form>
          {kind === "INVESTMENTS" && (
            <p className="import-notice">
              One investment per file, oldest first. Enter participating member numbers and repeat
              the cycle totals and dates on every row. Optional supplied shares must match the exact
              preview. Actual proceeds posts earned income: do not duplicate income already included
              in opening balances.
            </p>
          )}
          {kind === "LEDGER" && (
            <p className="import-notice">
              Use OPENING_BALANCE with PRINCIPAL or INCOME; SEED and ADDITION use PRINCIPAL. Add an
              optional final TOTAL row with its amount for reconciliation. Closed periods require
              independent unlock. Import investment cycles separately after their opening balances.
            </p>
          )}
        </section>
      )}
      {report && (
        <section className="surface investment-panel">
          <h2>
            {report.valid ? "Dry run ready for confirmation" : "Resolve the validation errors"}
          </h2>
          <ImportRows key={report.fileHash} report={report} />
          {report.valid && (
            <form
              className="investment-form"
              onSubmit={(event) => {
                event.preventDefault();
                void upload(true);
              }}
            >
              <label>
                Source and reason for importing
                <textarea
                  required
                  maxLength={1000}
                  value={reason}
                  onChange={(event) => setReason(event.target.value)}
                />
              </label>
              <p>
                Confirm that the data rows and calculated total match your source. This queues the
                whole batch; records are created only after the required approvals.
              </p>
              <button className="primary-button" disabled={busy || !reason.trim()}>
                Confirm and submit batch
              </button>
            </form>
          )}
        </section>
      )}
      <section className="surface investment-panel">
        <h2>Import batches</h2>
        {loading ? (
          <p role="status">Loading import batches…</p>
        ) : error ? (
          <div role="alert">
            <p>{error}</p>
            <button className="secondary-button" onClick={() => setRetry((value) => value + 1)}>
              Retry
            </button>
          </div>
        ) : !batches.length ? (
          <p>No import batches yet.</p>
        ) : (
          batches.map((batch) => (
            <article className="payment-record" key={batch.id}>
              <h3>
                {batch.kind === "MEMBERS"
                  ? "Members"
                  : batch.kind === "INVESTMENTS"
                    ? "Historical investment"
                    : "Ledger entries"}{" "}
                · {batch.status.toLowerCase()}
              </h3>
              <p>
                {batch.rowCount} rows
                {batch.kind !== "MEMBERS" && <> · {formatMoney(batch.total)}</>} ·{" "}
                {new Date(batch.createdAt).toLocaleString()}
              </p>
              <p className="payment-instructions">{batch.reason}</p>
              <p>
                {batch.approvals} of {batch.requiredApprovals} posting approvals
                {batch.reversalStatus && <> · Reversal {batch.reversalStatus.toLowerCase()}</>}
              </p>
              <div className="investment-action-bar">
                <button
                  className="secondary-button"
                  disabled={busy}
                  onClick={() => void openReview(batch)}
                >
                  Review batch
                </button>
                {batch.status === "POSTED" &&
                  batch.reversalStatus !== "PENDING" &&
                  canImport &&
                  profile.permissions.includes(
                    batch.kind === "MEMBERS"
                      ? "MEMBER_WRITE"
                      : batch.kind === "INVESTMENTS"
                        ? "CYCLE_REPOST"
                        : "LEDGER_REVERSE",
                  ) && (
                    <button
                      className="secondary-button"
                      disabled={busy}
                      onClick={() => {
                        setReverse(batch);
                        setReverseReason("");
                      }}
                    >
                      Request batch reversal
                    </button>
                  )}
              </div>
              {reverse?.id === batch.id && (
                <form
                  className="investment-form"
                  onSubmit={(event) => {
                    event.preventDefault();
                    void action(
                      api(`/${batch.id}/reverse`, { reason: reverseReason }),
                      "Batch reversal submitted for independent approval",
                    );
                  }}
                >
                  <h3>Confirm a reversal request</h3>
                  <p>
                    Ledger batches receive exact compensating entries dated today. Investment
                    batches release the cycle and compensate earned income, provided later
                    commitments permit it. Imported members are deactivated only if they still have
                    no accounts or financial records.
                  </p>
                  <label>
                    Reversal reason
                    <textarea
                      required
                      maxLength={1000}
                      value={reverseReason}
                      onChange={(event) => setReverseReason(event.target.value)}
                    />
                  </label>
                  <button className="primary-button" disabled={busy}>
                    Submit reversal request
                  </button>
                  <button
                    type="button"
                    className="secondary-button"
                    onClick={() => setReverse(null)}
                  >
                    Close
                  </button>
                </form>
              )}
            </article>
          ))
        )}
        <Pagination
          page={page}
          hasNext={hasNext}
          loading={loading}
          count={batches.length}
          pageSize={25}
          onPageChange={setPage}
          label="Import batch pages"
        />
      </section>
      {review && (
        <section className="surface investment-panel">
          <h2>{pendingReverse ? "Review batch reversal" : "Review submitted batch"}</h2>
          <p className="payment-instructions">{review.batch.reason}</p>
          {pendingReverse && (
            <>
              <h3>Reversal reason</h3>
              <p className="payment-instructions">{review.batch.reversalReason}</p>
              <p>
                {review.batch.reversalApprovals} of {review.batch.reversalRequiredApprovals}{" "}
                reversal approvals.
              </p>
            </>
          )}
          <ImportRows key={review.batch.id} report={review.report} />
          {pending &&
            requestId &&
            (makerId === profile.userId ? (
              <button
                className="secondary-button"
                disabled={busy}
                onClick={() =>
                  void action(
                    api(`/${review.batch.id}/cancel`, { requestId }),
                    "Import request cancelled",
                  )
                }
              >
                Cancel my request
              </button>
            ) : (
              checker && (
                <div className="investment-form">
                  <label className="import-confirm">
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={(event) => setChecked(event.target.checked)}
                    />
                    I reviewed the affected members, dates, amounts and source totals.
                  </label>
                  <p>
                    {pendingReverse
                      ? "Approval compensates every entry in this batch or deactivates unused imported memberships. Original records remain traceable."
                      : "Final approval creates every validated record in one transaction."}
                  </p>
                  <button
                    className="primary-button"
                    disabled={busy || !checked}
                    onClick={() =>
                      void action(
                        api(`/${review.batch.id}/decide`, { requestId, decision: "APPROVE" }),
                        "Import approval recorded",
                      )
                    }
                  >
                    Confirm approval
                  </button>
                  <label>
                    Reason to reject
                    <textarea
                      maxLength={1000}
                      value={comment}
                      onChange={(event) => setComment(event.target.value)}
                    />
                  </label>
                  <button
                    className="secondary-button"
                    disabled={busy || !comment.trim()}
                    onClick={() =>
                      void action(
                        api(`/${review.batch.id}/decide`, {
                          requestId,
                          decision: "REJECT",
                          comment,
                        }),
                        "Import request rejected",
                      )
                    }
                  >
                    Reject request
                  </button>
                </div>
              )
            ))}
          <button className="secondary-button" onClick={() => setReview(null)}>
            Close review
          </button>
        </section>
      )}
    </>
  );
}
