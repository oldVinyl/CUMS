import { useEffect, useState } from "react";
import ResponsiveOverlay from "./ResponsiveOverlay";
import { prepareReceipt } from "./receipt-conversion";
import { csrfCookie } from "./auth-api";
import { usePromiseToast } from "./toast-context";

type Payment = { id: string; memberId: string; proofId: string; reference: string };
type Revision = { id: string; previousProofId: string; newProofId: string; amendedAt: string };
async function responseValue(response: Response) {
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new Error(body?.message ?? "Receipt could not be updated. Please retry.");
  }
  return response.status === 204 ? undefined : response.json();
}
export default function ReceiptAmendment({
  payment,
  onClose,
  onSaved,
  editable = true,
}: {
  payment: Payment;
  onClose: () => void;
  onSaved: () => void;
  editable?: boolean;
}) {
  const [file, setFile] = useState<File | null>(null),
    [proofId, setProofId] = useState<string>();
  const [busy, setBusy] = useState(false),
    [history, setHistory] = useState<Revision[] | null>(null),
    [historyError, setHistoryError] = useState("");
  const promiseToast = usePromiseToast();
  useEffect(() => {
    const controller = new AbortController();
    fetch(`/api/v1/payments/${payment.id}/receipt-revisions`, {
      credentials: "same-origin",
      signal: controller.signal,
    })
      .then(responseValue)
      .then((value) => {
        if (!controller.signal.aborted) setHistory(value);
      })
      .catch((error) => {
        if (!controller.signal.aborted) setHistoryError(error.message);
      });
    return () => controller.abort();
  }, [payment.id]);
  const save = async () => {
    if (!file || busy) return;
    setBusy(true);
    try {
      await promiseToast(
        (async () => {
          let replacement = proofId;
          if (!replacement) {
            const converted = await prepareReceipt(file),
              body = new FormData();
            body.append("memberId", payment.memberId);
            body.append("file", converted);
            const uploaded = await responseValue(
              await fetch("/api/v1/payments/proofs", {
                method: "POST",
                credentials: "same-origin",
                headers: { "X-CSRF-Token": csrfCookie() },
                body,
              }),
            );
            replacement = uploaded.id;
            setProofId(replacement);
          }
          await responseValue(
            await fetch(`/api/v1/payments/${payment.id}/receipt`, {
              method: "POST",
              credentials: "same-origin",
              headers: { "Content-Type": "application/json", "X-CSRF-Token": csrfCookie() },
              body: JSON.stringify({ previousProofId: payment.proofId, newProofId: replacement }),
            }),
          );
          onSaved();
          onClose();
        })(),
        {
          loading: "Preparing replacement receipt…",
          success: "Receipt replaced and returned for verification",
          error: "Could not replace receipt",
        },
      );
    } catch {
      /* Promise feedback contains the safe server response. */
    } finally {
      setBusy(false);
    }
  };
  return (
    <ResponsiveOverlay
      open
      onClose={() => {
        if (!busy) onClose();
      }}
      title={editable ? "Replace payment receipt" : "Payment receipt history"}
    >
      <p>
        Payment {payment.reference}. The earlier receipt is retained. Amounts and payment details
        stay as submitted; staff must verify the replacement before approval.
      </p>
      <a className="secondary-button" href={`/api/v1/payments/proofs/${payment.proofId}`} download>
        Download current receipt
      </a>
      {editable && (
        <form
          className="investment-form"
          onSubmit={(event) => {
            event.preventDefault();
            void save();
          }}
        >
          <label>
            Replacement receipt (JPEG, PNG, PDF or HEIC; up to 10 MB)
            <input
              type="file"
              required
              disabled={busy}
              accept="image/jpeg,image/png,application/pdf,image/heic,image/heif,.heic,.heif"
              onChange={(event) => {
                setFile(event.target.files?.[0] ?? null);
                setProofId(undefined);
              }}
            />
          </label>
          <p>
            HEIC photos are converted on your device. A receipt cannot be changed after verification
            begins.
          </p>
          <button className="primary-button" disabled={busy || !file}>
            Replace and return for verification
          </button>
        </form>
      )}
      <h2>Earlier receipts</h2>
      {historyError ? (
        <p role="alert">{historyError}</p>
      ) : history === null ? (
        <p role="status">Loading receipt history…</p>
      ) : !history.length ? (
        <p>No earlier replacements.</p>
      ) : (
        <ul>
          {history.map((revision, index) => (
            <li key={revision.id}>
              <a href={`/api/v1/payments/proofs/${revision.previousProofId}`} download>
                Download earlier receipt {index + 1}
              </a>{" "}
              · replaced {new Date(revision.amendedAt).toLocaleString()}
            </li>
          ))}
        </ul>
      )}
    </ResponsiveOverlay>
  );
}
