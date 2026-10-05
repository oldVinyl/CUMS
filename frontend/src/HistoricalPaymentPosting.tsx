import { financialFetch } from "./financial-fetch";
import { useEffect, useRef, useState } from "react";
import { csrfCookie } from "./auth-api";
import { ContributionImpact, type Impact } from "./ContributionForm";
import { usePromiseToast } from "./toast-context";

async function request<T>(
  paymentId: string,
  action: string,
  body?: object,
  signal?: AbortSignal,
): Promise<T> {
  const response = await financialFetch(`/api/v1/payments/${paymentId}/${action}`, {
    method: body ? "POST" : "GET",
    credentials: "same-origin",
    signal,
    headers: body
      ? { "Content-Type": "application/json", "X-CSRF-Token": csrfCookie() }
      : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!response.ok) {
    const error = (await response.json().catch(() => null)) as { message?: string } | null;
    throw new Error(error?.message ?? "Could not review historical payment posting.");
  }
  return response.json() as Promise<T>;
}

export function PaymentPostingImpact({
  paymentId,
  onReady,
}: {
  paymentId: string;
  onReady: (ready: boolean) => void;
}) {
  const [review, setReview] = useState<{
    input: { mode: string; effectiveDate: string; nextCycleDate: string; reason: string } | null;
    impact: Impact | null;
  } | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    const controller = new AbortController();
    onReady(false);
    setReview(null);
    setError("");
    void request<NonNullable<typeof review>>(
      paymentId,
      "posting-impact",
      undefined,
      controller.signal,
    )
      .then((value) => {
        if (!controller.signal.aborted) {
          setReview(value);
          onReady(true);
        }
      })
      .catch((failure: Error) => {
        if (!controller.signal.aborted) setError(failure.message);
      });
    return () => controller.abort();
  }, [paymentId, onReady]);
  if (error)
    return (
      <p role="alert">
        {error} Cancel this verification and request a fresh review if its financial source changed.
      </p>
    );
  if (!review) return <p role="status">Loading posting impact…</p>;
  return review.input && review.impact ? (
    <>
      <p>
        Original payment date: {review.input.effectiveDate}.{" "}
        {review.input.mode === "REPOST"
          ? "Recompute affected investment allocations."
          : `Apply from ${review.input.nextCycleDate}; preserve existing investment allocations.`}
      </p>
      <p className="payment-instructions">Reason: {review.input.reason}</p>
      <ContributionImpact impact={review.impact} />
    </>
  ) : (
    <p>Current-day posting. No historical investment allocations will be changed.</p>
  );
}

export default function HistoricalPaymentPosting({
  paymentId,
  paidDate,
  amount,
  busy,
  onSubmit,
}: {
  paymentId: string;
  paidDate: string;
  amount: string;
  busy: boolean;
  onSubmit: (work: Promise<unknown>, success: string) => Promise<void>;
}) {
  const today = new Date().toISOString().slice(0, 10);
  const [impact, setImpact] = useState<Impact | null>(null),
    [checking, setChecking] = useState(false);
  const [mode, setMode] = useState("FUTURE_ONLY"),
    [next, setNext] = useState(today),
    [reason, setReason] = useState(""),
    [confirmed, setConfirmed] = useState(false);
  const generation = useRef(0);
  const toast = usePromiseToast();
  useEffect(() => {
    generation.current++;
    setImpact(null);
    setConfirmed(false);
  }, [paymentId, paidDate, amount, mode, next, reason]);
  const body = { amount, mode, nextCycleDate: next, reason };
  const preview = async () => {
    const current = ++generation.current;
    setChecking(true);
    setConfirmed(false);
    try {
      const value = await toast(request<Impact>(paymentId, "historical-preview", body), {
        loading: "Calculating payment impact…",
        success: "Payment impact ready",
        error: "Could not calculate payment impact",
      });
      if (generation.current === current) setImpact(value);
    } catch {
      /* Toast retains server feedback. */
    } finally {
      setChecking(false);
    }
  };
  return (
    <section className="investment-edit">
      <h4>Use the original payment date</h4>
      <p>Paid {paidDate}. This credits the same receipt once, with independent approval.</p>
      <label>
        Handling of existing cycles
        <select value={mode} onChange={(e) => setMode(e.target.value)}>
          <option value="FUTURE_ONLY">Apply from the next cycle</option>
          <option value="REPOST">Recompute affected cycles</option>
        </select>
      </label>
      {mode === "FUTURE_ONLY" && (
        <label>
          Next cycle start date
          <input
            type="date"
            min={paidDate}
            max={today}
            value={next}
            onChange={(e) => setNext(e.target.value)}
          />
        </label>
      )}
      <label>
        Reason for historical posting
        <textarea maxLength={1000} value={reason} onChange={(e) => setReason(e.target.value)} />
      </label>
      <button
        className="secondary-button"
        disabled={busy || checking || !amount || !reason.trim()}
        onClick={() => void preview()}
      >
        Preview historical impact
      </button>
      {impact && (
        <>
          <ContributionImpact impact={impact} />
          <p>Earliest future-only posting: {impact.earliestNextCycleDate}.</p>
          <label className="investment-action-bar">
            <input
              type="checkbox"
              checked={confirmed}
              onChange={(e) => setConfirmed(e.target.checked)}
            />
            I reviewed the receipt and every affected allocation.
          </label>
          <button
            className="primary-button"
            disabled={
              busy ||
              checking ||
              !confirmed ||
              (mode === "REPOST" && !impact.repostAllowed) ||
              (mode === "FUTURE_ONLY" && next < impact.earliestNextCycleDate)
            }
            onClick={() =>
              void onSubmit(
                request(paymentId, "historical-verify", {
                  ...body,
                  sourceFingerprint: impact.sourceFingerprint,
                }),
                "Historical payment sent for independent approval",
              )
            }
          >
            Request historical posting
          </button>
        </>
      )}
    </section>
  );
}
