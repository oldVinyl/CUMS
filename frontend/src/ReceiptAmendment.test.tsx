import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import toast from "react-hot-toast";
import ReceiptAmendment from "./ReceiptAmendment";
import { ToastProvider } from "./toast";
const payment = {
  id: "payment-fixture",
  memberId: "member-fixture",
  proofId: "old-proof",
  reference: "CU-FIXTURE",
};
beforeEach(() => {
  vi.stubGlobal(
    "matchMedia",
    vi.fn(() => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() })),
  );
});
afterEach(() => {
  toast.remove();
  cleanup();
  vi.unstubAllGlobals();
});
it("retains an uploaded replacement on a failed save and retries without another upload", async () => {
  const onSaved = vi.fn(),
    onClose = vi.fn();
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(new Response("[]"))
    .mockResolvedValueOnce(new Response(JSON.stringify({ id: "new-proof" })))
    .mockResolvedValueOnce(
      new Response(JSON.stringify({ message: "Receipt update temporarily unavailable" }), {
        status: 409,
      }),
    )
    .mockResolvedValueOnce(new Response(null, { status: 204 }));
  vi.stubGlobal("fetch", fetch);
  render(
    <ToastProvider>
      <ReceiptAmendment payment={payment} onSaved={onSaved} onClose={onClose} />
    </ToastProvider>,
  );
  await screen.findByText("No earlier replacements.");
  fireEvent.change(screen.getByLabelText(/Replacement receipt/), {
    target: { files: [new File(["synthetic"], "receipt.png", { type: "image/png" })] },
  });
  fireEvent.submit(
    screen.getByRole("button", { name: "Replace and return for verification" }).closest("form")!,
  );
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(3));
  await waitFor(() =>
    expect(
      (
        screen.getByRole("button", {
          name: "Replace and return for verification",
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(false),
  );
  expect(onClose).not.toHaveBeenCalled();
  fireEvent.submit(
    screen.getByRole("button", { name: "Replace and return for verification" }).closest("form")!,
  );
  await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1));
  expect(onClose).toHaveBeenCalledTimes(1);
  expect(fetch.mock.calls.filter(([url]) => url === "/api/v1/payments/proofs")).toHaveLength(1);
  expect(JSON.parse(fetch.mock.calls[3][1].body)).toEqual({
    previousProofId: "old-proof",
    newProofId: "new-proof",
  });
});
it("shows protected earlier-receipt links and does not save without a file", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(
      async () =>
        new Response(
          JSON.stringify([
            {
              id: "revision",
              previousProofId: "earlier-proof",
              newProofId: "old-proof",
              amendedAt: "2026-01-01T12:00:00Z",
            },
          ]),
        ),
    ),
  );
  render(
    <ToastProvider>
      <ReceiptAmendment payment={payment} onSaved={vi.fn()} onClose={vi.fn()} />
    </ToastProvider>,
  );
  expect(
    (await screen.findByRole("link", { name: "Download earlier receipt 1" })).getAttribute("href"),
  ).toBe("/api/v1/payments/proofs/earlier-proof");
  expect(
    (
      screen.getByRole("button", {
        name: "Replace and return for verification",
      }) as HTMLButtonElement
    ).disabled,
  ).toBe(true);
});

it("read-only history supports checkers and posted payments without an amendment form", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response("[]")),
  );
  render(
    <ToastProvider>
      <ReceiptAmendment payment={payment} editable={false} onSaved={vi.fn()} onClose={vi.fn()} />
    </ToastProvider>,
  );
  await screen.findByText("No earlier replacements.");
  expect(screen.getByRole("heading", { name: "Payment receipt history" })).toBeTruthy();
  expect(screen.queryByLabelText(/Replacement receipt/)).toBeNull();
  expect(screen.getByRole("link", { name: "Download current receipt" })).toBeTruthy();
});
