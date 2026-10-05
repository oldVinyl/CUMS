import { financialFetch } from "./financial-fetch";
import { useEffect, useState } from "react";
import Decimal from "decimal.js";
import type { Profile } from "./auth-api";
import { csrfCookie } from "./auth-api";
import { formatMoney } from "./money";
import { usePromiseToast } from "./toast-context";
import ResponsiveOverlay from "./ResponsiveOverlay";
import Pagination from "./Pagination";
type Payment = {
  id: string;
  reference: string;
  gross: string;
  fee: string;
  expectedNet: string;
  confirmedDate: string;
  settledAmount: string | null;
  settledDate: string | null;
  sourceFingerprint: string;
  pending: boolean;
};
type Match = {
  id: string;
  paymentId: string;
  reference: string;
  amount: string;
  settledDate: string;
  notes: string;
  makerId: string;
  requestId: string;
  status: string;
  approvals: number;
  requiredApprovals: number;
};
type Page = { payments: Payment[]; matches: Match[]; hasNext: boolean };
async function api<T>(path: string, signal?: AbortSignal, body?: object): Promise<T> {
  const response = await financialFetch(`/api/v1/reconciliations/settlements${path}`, {
    credentials: "same-origin",
    signal,
    method: body ? "POST" : "GET",
    headers: body
      ? { "Content-Type": "application/json", "X-CSRF-Token": csrfCookie() }
      : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!response.ok) {
    const error = (await response.json().catch(() => null)) as { message?: string } | null;
    throw new Error(error?.message ?? "Settlement request could not be completed.");
  }
  return response.status === 204 ? (undefined as T) : (response.json() as Promise<T>);
}
export default function SettlementPanel({
  profile,
  onChange,
}: {
  profile: Profile;
  onChange: () => void;
}) {
  const maker = profile.permissions.includes("RECONCILE"),
    checker = profile.permissions.includes("APPROVE");
  const [data, setData] = useState<Page | null>(null),
    [page, setPage] = useState(0),
    [retry, setRetry] = useState(0),
    [loading, setLoading] = useState(true),
    [error, setError] = useState("");
  const [selected, setSelected] = useState<Payment | null>(null),
    [review, setReview] = useState<Match | null>(null),
    [amount, setAmount] = useState(""),
    [date, setDate] = useState(""),
    [notes, setNotes] = useState(""),
    [reason, setReason] = useState(""),
    [busy, setBusy] = useState(false);
  const promiseToast = usePromiseToast();
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError("");
    api<Page>(`?page=${page}`, controller.signal)
      .then((result) => {
        if (!controller.signal.aborted) setData(result);
      })
      .catch((cause) => {
        if (!controller.signal.aborted)
          setError(
            cause instanceof Error ? cause.message : "Settlement payments could not be loaded.",
          );
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [page, retry]);
  const action = async (work: Promise<unknown>, success: string) => {
    setBusy(true);
    try {
      await promiseToast(work, {
        loading: "Recording settlement review…",
        success,
        error: "Settlement review could not be recorded",
      });
      setSelected(null);
      setReview(null);
      setRetry((v) => v + 1);
      onChange();
    } catch {
      /* Detailed toast retains the form for retry. */
    } finally {
      setBusy(false);
    }
  };
  return (
    <section className="surface investment-panel">
      <h2>Gateway settlement matching</h2>
      <p>
        Match confirmed payments with the bank's settlement evidence. Expected net is the gross
        payment less the provider fee. Matching records cash receipt evidence and does not post
        another contribution.
      </p>
      {loading ? (
        <p role="status">Loading settlement payments…</p>
      ) : error ? (
        <div role="alert">
          <p>{error}</p>
          <button className="secondary-button" onClick={() => setRetry((v) => v + 1)}>
            Retry settlement payments
          </button>
        </div>
      ) : (
        data && (
          <>
            {!data.payments.length ? (
              <p>No gateway-confirmed payments to match yet.</p>
            ) : (
              data.payments.map((payment) => (
                <article className="payment-record" key={payment.id}>
                  <h3>{payment.reference}</h3>
                  <div className="portal-balance-grid">
                    <div>
                      <span>Gross payment</span>
                      <strong>{formatMoney(payment.gross)}</strong>
                      <p>Provider fee {formatMoney(payment.fee)}</p>
                    </div>
                    <div>
                      <span>Expected bank receipt</span>
                      <strong>{formatMoney(payment.expectedNet)}</strong>
                      <p>Confirmed {payment.confirmedDate}</p>
                    </div>
                    <div>
                      <span>
                        {payment.settledAmount === null
                          ? "Awaiting bank settlement match"
                          : "Recorded bank receipt"}
                      </span>
                      <strong>
                        {payment.settledAmount === null
                          ? "Not matched"
                          : formatMoney(payment.settledAmount)}
                      </strong>
                      {payment.settledDate && (
                        <p>
                          {payment.settledDate} · Difference{" "}
                          {formatMoney(
                            new Decimal(payment.settledAmount!)
                              .minus(payment.expectedNet)
                              .toFixed(2),
                          )}
                        </p>
                      )}
                    </div>
                  </div>
                  {payment.pending ? (
                    <p>Settlement match awaiting independent review.</p>
                  ) : (
                    maker && (
                      <button
                        className="secondary-button"
                        disabled={busy}
                        onClick={() => {
                          setSelected(payment);
                          setAmount(payment.settledAmount ?? payment.expectedNet);
                          setDate(payment.settledDate ?? payment.confirmedDate);
                          setNotes("");
                        }}
                      >
                        {payment.settledAmount === null
                          ? "Match bank settlement"
                          : "Correct settlement match"}
                      </button>
                    )
                  )}
                </article>
              ))
            )}
            <h3>Settlement review history</h3>
            {!data.matches.length ? (
              <p>No settlement reviews yet.</p>
            ) : (
              data.matches.map((match) => (
                <article className="payment-record" key={match.id}>
                  <h4>
                    {match.reference} · {match.status.toLowerCase()}
                  </h4>
                  <p>
                    {formatMoney(match.amount)} received {match.settledDate}
                  </p>
                  <p className="payment-instructions">{match.notes}</p>
                  {match.status === "PENDING" && (
                    <>
                      <p>
                        {match.approvals} of {match.requiredApprovals} approvals.
                      </p>
                      {match.makerId === profile.userId ? (
                        <button
                          className="secondary-button"
                          disabled={busy}
                          onClick={() =>
                            void action(
                              api(`/${match.id}/cancel`, undefined, {}),
                              "Settlement review cancelled",
                            )
                          }
                        >
                          Cancel settlement review
                        </button>
                      ) : (
                        checker && (
                          <button
                            className="secondary-button"
                            onClick={() => {
                              setReview(match);
                              setReason("");
                            }}
                          >
                            Review settlement match
                          </button>
                        )
                      )}
                    </>
                  )}
                </article>
              ))
            )}
            <Pagination
              page={page}
              hasNext={data.hasNext}
              loading={loading}
              onPageChange={setPage}
                  pageSize={50}
              label="Settlement pages"
            />
          </>
        )
      )}
      <ResponsiveOverlay
        open={!!selected}
        onClose={() => setSelected(null)}
        title="Match bank settlement"
        description="Record the bank evidence for independent review. Existing settlement history remains available."
      >
        {selected && (
          <form
            className="investment-form"
            onSubmit={(event) => {
              event.preventDefault();
              void action(
                api(`/payments/${selected.id}`, undefined, {
                  amount,
                  settledDate: date,
                  notes,
                  sourceFingerprint: selected.sourceFingerprint,
                }),
                "Settlement match sent for independent approval",
              );
            }}
          >
            <p>
              {selected.reference} · Expected net {formatMoney(selected.expectedNet)}
            </p>
            <label>
              Bank settlement amount (GHS)
              <input
                required
                inputMode="decimal"
                value={amount}
                onChange={(event) => setAmount(event.target.value)}
              />
            </label>
            <label>
              Settlement date
              <input
                required
                type="date"
                max={new Date().toISOString().slice(0, 10)}
                min={selected.confirmedDate}
                value={date}
                onChange={(event) => setDate(event.target.value)}
              />
            </label>
            {/^\d+(\.\d{1,2})?$/.test(amount) && (
              <p>
                Difference from expected net:{" "}
                {formatMoney(new Decimal(amount).minus(selected.expectedNet).toFixed(2))}
              </p>
            )}
            <label>
              Bank evidence and explanation
              <textarea
                required
                rows={4}
                maxLength={2000}
                value={notes}
                onChange={(event) => setNotes(event.target.value)}
                placeholder="Statement or payout evidence and explanation of any difference. Avoid personal account details."
              />
            </label>
            <button className="primary-button" disabled={busy}>
              Submit settlement match
            </button>
          </form>
        )}
      </ResponsiveOverlay>
      <ResponsiveOverlay
        open={!!review}
        onClose={() => setReview(null)}
        title="Review settlement match"
        description="Check the bank evidence before approving. A changed payment requires a new review."
      >
        {review && (
          <>
            <p>
              {review.reference} · {formatMoney(review.amount)} · {review.settledDate}
            </p>
            <p className="payment-instructions">{review.notes}</p>
            <button
              className="primary-button"
              disabled={busy}
              onClick={() =>
                void action(
                  api(`/${review.id}/decide`, undefined, { decision: "APPROVE" }),
                  "Settlement approval recorded",
                )
              }
            >
              Confirm settlement match
            </button>
            <label>
              Reason to reject
              <textarea
                maxLength={1000}
                value={reason}
                onChange={(event) => setReason(event.target.value)}
              />
            </label>
            <button
              className="secondary-button"
              disabled={busy || !reason.trim()}
              onClick={() =>
                void action(
                  api(`/${review.id}/decide`, undefined, { decision: "REJECT", comment: reason }),
                  "Settlement match rejected",
                )
              }
            >
              Reject settlement match
            </button>
          </>
        )}
      </ResponsiveOverlay>
    </section>
  );
}
