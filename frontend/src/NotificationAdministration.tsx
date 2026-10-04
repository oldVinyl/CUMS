import { useEffect, useState } from "react";
import { csrfCookie } from "./auth-api";
import Pagination from "./Pagination";
import ResponsiveOverlay from "./ResponsiveOverlay";
import { usePromiseToast } from "./toast-context";
type Item = {
  id: string;
  accountId: string;
  kind: string;
  outcome: string;
  status: string;
  attempts: number;
  createdAt: string;
  sentAt: string | null;
  nextAttemptAt: string;
  canRetry: boolean;
};
async function request<T>(path: string, signal?: AbortSignal, retry = false): Promise<T> {
  const response = await fetch(`/api/v1/notifications${path}`, {
    credentials: "same-origin",
    signal,
    method: retry ? "POST" : "GET",
    headers: retry ? { "X-CSRF-Token": csrfCookie() } : undefined,
  });
  if (!response.ok) {
    const error = await response.json().catch(() => null);
    throw new Error(error?.message ?? "Notification request failed.");
  }
  return response.status === 204 ? (undefined as T) : (response.json() as Promise<T>);
}
export default function NotificationAdministration() {
  const [items, setItems] = useState<Item[]>([]),
    [status, setStatus] = useState("ALL"),
    [page, setPage] = useState(0),
    [hasNext, setHasNext] = useState(false),
    [version, setVersion] = useState(0),
    [loading, setLoading] = useState(true),
    [error, setError] = useState(""),
    [selected, setSelected] = useState<Item | null>(null),
    [busy, setBusy] = useState(false);
  const toast = usePromiseToast();
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError("");
    request<{ items: Item[]; hasNext: boolean }>(
      `?status=${status}&page=${page}`,
      controller.signal,
    )
      .then((value) => {
        if (!controller.signal.aborted) {
          if (!Array.isArray(value.items)) throw new Error("Notification list unavailable.");
          setItems(value.items);
          setHasNext(value.hasNext);
        }
      })
      .catch((failure) => {
        if (!controller.signal.aborted) setError(failure.message);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [status, page, version]);
  async function retry() {
    if (!selected || busy) return;
    setBusy(true);
    try {
      await toast(request(`/${selected.id}/retry`, undefined, true), {
        loading: "Queuing notification retry…",
        success: "Notification retry queued",
        error: "Could not retry notification",
      });
      setSelected(null);
      setVersion((value) => value + 1);
    } catch {
      /* Detailed feedback is in the promise toast. */
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="surface investment-panel">
      <div className="card-title-row">
        <div>
          <h2>Email delivery</h2>
          <p>
            Review delivery status and retry failed messages. Queued messages are sent when SMTP is
            enabled.
          </p>
        </div>
        <label>
          Status
          <select
            value={status}
            onChange={(event) => {
              setStatus(event.target.value);
              setPage(0);
            }}
          >
            <option value="ALL">All messages</option>
            <option value="PENDING">Queued</option>
            <option value="FAILED">Failed</option>
            <option value="SENT">Sent</option>
          </select>
        </label>
      </div>
      {loading ? (
        <p role="status">Loading notifications…</p>
      ) : error ? (
        <div role="alert">
          <p>{error}</p>
          <button className="secondary-button" onClick={() => setVersion((value) => value + 1)}>
            Try again
          </button>
        </div>
      ) : !items.length ? (
        <p>No notifications match this status.</p>
      ) : (
        items.map((item) => (
          <article className="payment-record" key={item.id}>
            <div className="card-title-row">
              <h3>
                {item.kind.replaceAll("_", " ")} · {item.outcome.replaceAll("_", " ")}
              </h3>
              <span className="status-badge">
                {item.status === "PENDING" ? "QUEUED" : item.status}
              </span>
            </div>
            <p>
              Attempts: {item.attempts} · Created: {new Date(item.createdAt).toLocaleString()}
            </p>
            <p className="payment-instructions">Account reference: {item.accountId}</p>
            {item.sentAt ? (
              <p>Sent: {new Date(item.sentAt).toLocaleString()}</p>
            ) : item.status === "PENDING" ? (
              <p>Next attempt: {new Date(item.nextAttemptAt).toLocaleString()}</p>
            ) : null}
            {item.canRetry ? (
              <button className="secondary-button" onClick={() => setSelected(item)}>
                Review retry
              </button>
            ) : item.status === "FAILED" ? (
              <p>
                The account or invitation is no longer eligible. Review the account or send a new
                invitation.
              </p>
            ) : null}
          </article>
        ))
      )}
      <Pagination
        page={page}
        hasNext={hasNext}
        loading={loading}
        onPageChange={setPage}
        count={error ? 0 : items.length}
        pageSize={25}
        label="Notification pages"
      />
      <ResponsiveOverlay
        open={selected !== null}
        onClose={() => {
          if (!busy) setSelected(null);
        }}
        title="Retry failed email"
      >
        <p>
          Queue this failed message for another delivery attempt. SMTP must be enabled. Sent
          messages cannot be retried here.
        </p>
        <div className="investment-action-bar">
          <button className="primary-button" disabled={busy} onClick={() => void retry()}>
            Confirm retry
          </button>
          <button className="secondary-button" disabled={busy} onClick={() => setSelected(null)}>
            Cancel
          </button>
        </div>
      </ResponsiveOverlay>
    </section>
  );
}
