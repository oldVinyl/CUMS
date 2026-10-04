import { useEffect, useRef, useState } from "react";
import type { Profile } from "./auth-api";
import { csrfCookie } from "./auth-api";
import ResponsiveOverlay from "./ResponsiveOverlay";
import { usePromiseToast } from "./toast-context";
type Account = { id: string; email: string; status: string };
type Pending = {
  id: string;
  makerId: string;
  userId: string;
  reason: string;
  requiredApprovals: number;
  approvals: number;
};
type Preview = { userId: string; securityFingerprint: string };
async function api<T>(path: string, signal?: AbortSignal, body?: object): Promise<T> {
  const response = await fetch(`/api/v1/account-recovery${path}`, {
    credentials: "same-origin",
    signal,
    method: body ? "POST" : "GET",
    headers: body
      ? { "Content-Type": "application/json", "X-CSRF-Token": csrfCookie() }
      : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!response.ok) {
    const error = await response.json().catch(() => null);
    throw new Error(error?.message ?? "Account recovery request failed.");
  }
  return response.status === 204 ? (undefined as T) : (response.json() as Promise<T>);
}
export default function AccountRecoveryAdministration({
  profile,
  accounts,
}: {
  profile: Profile;
  accounts: Account[];
}) {
  const maker = profile.permissions.includes("USER_ADMIN"),
    checker =
      profile.permissions.includes("APPROVE") && profile.permissions.includes("ROLE_ASSIGN");
  const [target, setTarget] = useState(""),
    [reason, setReason] = useState(""),
    [verified, setVerified] = useState(false),
    [preview, setPreview] = useState<Preview | null>(null),
    [pending, setPending] = useState<Pending[]>([]),
    [loading, setLoading] = useState(true),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [version, setVersion] = useState(0),
    [review, setReview] = useState<Pending | null>(null),
    [confirmed, setConfirmed] = useState(false),
    [comment, setComment] = useState("");
  const generation = useRef(0);
  const toast = usePromiseToast();
  useEffect(() => {
    generation.current++;
    setPreview(null);
    setVerified(false);
  }, [target]);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError("");
    api<Pending[]>("", controller.signal)
      .then((value) => {
        if (!controller.signal.aborted) {
          if (!Array.isArray(value)) throw new Error("Recovery queue unavailable.");
          setPending(value);
        }
      })
      .catch((failure) => {
        if (!controller.signal.aborted) setError(failure.message);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [version]);
  async function action(work: Promise<unknown>, success: string) {
    setBusy(true);
    try {
      await toast(work, {
        loading: "Saving recovery request…",
        success,
        error: "Could not complete account recovery",
      });
      setVersion((value) => value + 1);
      setReview(null);
      setPreview(null);
      setVerified(false);
    } catch {
      /* Promise feedback includes server detail. */
    } finally {
      setBusy(false);
    }
  }
  async function inspect() {
    const current = ++generation.current;
    setBusy(true);
    try {
      const value = await toast(api<Preview>("/preview", undefined, { userId: target }), {
        loading: "Checking account security…",
        success: "Account ready for recovery review",
        error: "Could not prepare recovery",
      });
      if (generation.current === current) setPreview(value);
    } catch {
      /* Toast provides detail. */
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="surface investment-panel">
      <h2>Lost account access</h2>
      <p>
        When every authenticator, passkey and recovery code is lost, verify the account holder
        offline and request independent recovery. Approval revokes all existing sign-in methods and
        sends a 30-minute password link to the registered email. The holder then sets up a protected
        method again.
      </p>
      {maker && (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (!preview) {
              void inspect();
              return;
            }
            void action(
              api("", undefined, {
                userId: target,
                reason,
                securityFingerprint: preview.securityFingerprint,
              }),
              "Recovery approval requested",
            );
          }}
        >
          <div className="investment-form-grid">
            <label>
              Recovery account
              <select required value={target} onChange={(event) => setTarget(event.target.value)}>
                <option value="">Choose from the accounts above</option>
                {accounts
                  .filter((account) => account.status === "ACTIVE" && account.id !== profile.userId)
                  .map((account) => (
                    <option key={account.id} value={account.id}>
                      {account.email}
                    </option>
                  ))}
              </select>
              <small>
                Use account search above to find the account. You cannot reset your own factors
                here.
              </small>
            </label>
            <label>
              Verification reason
              <textarea
                required
                maxLength={1000}
                value={reason}
                onChange={(event) => setReason(event.target.value)}
              />
              <small>
                Describe the verification process without entering ID numbers or private documents.
              </small>
            </label>
          </div>
          <label className="investment-action-bar">
            <input
              required
              type="checkbox"
              checked={verified}
              onChange={(event) => setVerified(event.target.checked)}
            />
            I verified the account holder and their registered email offline
          </label>
          {preview && (
            <p role="status">
              Account security snapshot recorded. Changes before approval will require a fresh
              request.
            </p>
          )}
          <button className="primary-button" disabled={busy || !target || !verified}>
            {preview ? "Request recovery approval" : "Preview recovery"}
          </button>
        </form>
      )}
      <h3>Pending recovery requests</h3>
      {loading ? (
        <p role="status">Loading recovery requests…</p>
      ) : error ? (
        <div role="alert">
          <p>{error}</p>
          <button className="secondary-button" onClick={() => setVersion((value) => value + 1)}>
            Try again
          </button>
        </div>
      ) : !pending.length ? (
        <p>No pending recovery requests.</p>
      ) : (
        pending.map((item) => (
          <article className="payment-record" key={item.id}>
            <h4>
              {accounts.find((account) => account.id === item.userId)?.email ?? "Account recovery"}
            </h4>
            <p className="payment-instructions">Account reference: {item.userId}</p>
            <p>{item.reason}</p>
            <p>
              {item.approvals} of {item.requiredApprovals} approvals
            </p>
            {item.makerId === profile.userId ? (
              <button
                className="secondary-button"
                disabled={busy}
                onClick={() =>
                  void action(
                    api(`/${item.id}/cancel`, undefined, {}),
                    "Recovery request cancelled",
                  )
                }
              >
                Cancel recovery request
              </button>
            ) : checker && item.userId !== profile.userId ? (
              <button
                className="secondary-button"
                onClick={() => {
                  setReview(item);
                  setConfirmed(false);
                  setComment("");
                }}
              >
                Review recovery
              </button>
            ) : null}
          </article>
        ))
      )}
      <ResponsiveOverlay
        open={!!review}
        onClose={() => {
          if (!busy) setReview(null);
        }}
        title="Review account recovery"
      >
        <p>
          Confirm the account reference and verification reason. Final approval immediately revokes
          passwords, sessions, authenticators, recovery codes, passkeys and Google links. Only the
          registered email receives a new single-use link.
        </p>
        <p className="payment-instructions">Account reference: {review?.userId}</p>
        <p>{review?.reason}</p>
        <label className="investment-action-bar">
          <input
            type="checkbox"
            checked={confirmed}
            onChange={(event) => setConfirmed(event.target.checked)}
          />
          I independently verified the holder and recovery reason
        </label>
        <button
          className="primary-button"
          disabled={busy || !confirmed}
          onClick={() => {
            if (review)
              void action(
                api(`/${review.id}/decide`, undefined, { decision: "APPROVE", comment }),
                "Recovery decision recorded",
              );
          }}
        >
          Confirm recovery approval
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
          onClick={() => {
            if (review)
              void action(
                api(`/${review.id}/decide`, undefined, { decision: "REJECT", comment }),
                "Recovery request rejected",
              );
          }}
        >
          Reject recovery
        </button>
      </ResponsiveOverlay>
    </section>
  );
}
