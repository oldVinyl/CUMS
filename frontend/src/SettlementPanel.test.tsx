import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type { Profile } from "./auth-api";
import SettlementPanel from "./SettlementPanel";
import { ToastProvider } from "./toast";
const profile = { userId: "maker", permissions: ["RECONCILE"] } as Profile;
const payment = {
  id: "payment",
  reference: "SYNTHETIC-001",
  gross: "100.0000",
  fee: "2.5000",
  expectedNet: "97.5000",
  confirmedDate: "2026-10-01",
  settledAmount: null,
  settledDate: null,
  sourceFingerprint: "synthetic-hash",
  pending: false,
};
beforeEach(() => {
  vi.stubGlobal("matchMedia", () => ({
    matches: false,
    addListener: () => {},
    removeListener: () => {},
  }));
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});
it("shows settlement net and submits reviewed bank evidence in the overlay", async () => {
  const fetchMock = vi.fn(async (_path: unknown, options?: RequestInit) =>
    options?.method === "POST"
      ? new Response(JSON.stringify({ id: "match" }))
      : new Response(JSON.stringify({ payments: [payment], matches: [], hasNext: false })),
  );
  vi.stubGlobal("fetch", fetchMock);
  const changed = vi.fn();
  render(
    <ToastProvider>
      <SettlementPanel profile={profile} onChange={changed} />
    </ToastProvider>,
  );
  expect(await screen.findByText(/97\.50/)).toBeTruthy();
  expect(screen.getByText("Awaiting bank settlement match")).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Match bank settlement" }));
  fireEvent.change(screen.getByLabelText("Bank settlement amount (GHS)"), {
    target: { value: "96.50" },
  });
  fireEvent.change(screen.getByLabelText("Bank evidence and explanation"), {
    target: { value: "Synthetic bank evidence with discrepancy" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Submit settlement match" }));
  await waitFor(() =>
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/reconciliations/settlements/payments/payment",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({
          amount: "96.50",
          settledDate: "2026-10-01",
          notes: "Synthetic bank evidence with discrepancy",
          sourceFingerprint: "synthetic-hash",
        }),
      }),
    ),
  );
  await waitFor(() => expect(changed).toHaveBeenCalled());
});
it("empty results and load errors offer useful feedback", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response(JSON.stringify({ payments: [], matches: [], hasNext: false }))),
  );
  const view = render(
    <ToastProvider>
      <SettlementPanel profile={profile} onChange={() => {}} />
    </ToastProvider>,
  );
  expect(await screen.findByText("No gateway-confirmed payments to match yet.")).toBeTruthy();
  view.unmount();
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response(JSON.stringify({ message: "Please retry." }), { status: 503 })),
  );
  render(
    <ToastProvider>
      <SettlementPanel profile={profile} onChange={() => {}} />
    </ToastProvider>,
  );
  expect(await screen.findByRole("button", { name: "Retry settlement payments" })).toBeTruthy();
});
it("checker reviews retained evidence but cannot create a match without permission", async () => {
  const match = {
    id: "match",
    paymentId: "payment",
    reference: "SYNTHETIC-001",
    amount: "97.50",
    settledDate: "2026-10-01",
    notes: "Synthetic bank evidence",
    makerId: "maker",
    requestId: "request",
    status: "PENDING",
    approvals: 0,
    requiredApprovals: 1,
  };
  vi.stubGlobal(
    "fetch",
    vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            payments: [{ ...payment, pending: true }],
            matches: [match],
            hasNext: false,
          }),
        ),
    ),
  );
  render(
    <ToastProvider>
      <SettlementPanel
        profile={{ userId: "checker", permissions: ["APPROVE"] } as Profile}
        onChange={() => {}}
      />
    </ToastProvider>,
  );
  fireEvent.click(await screen.findByRole("button", { name: "Review settlement match" }));
  expect(screen.getByRole("button", { name: "Confirm settlement match" })).toBeTruthy();
  expect(
    (screen.getByRole("button", { name: "Reject settlement match" }) as HTMLButtonElement).disabled,
  ).toBe(true);
  expect(screen.queryByRole("button", { name: "Match bank settlement" })).toBeNull();
});
