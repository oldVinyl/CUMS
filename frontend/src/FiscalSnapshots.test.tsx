import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import FiscalSnapshots from "./FiscalSnapshots";
import { ToastProvider } from "./toast";
beforeEach(() => { vi.stubGlobal("matchMedia", () => ({ matches: false, addListener: () => {}, removeListener: () => {} })); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
it("does not load private snapshots until a year is selected and handles an empty year", async () => {
  const fetch = vi.fn(async () => new Response(JSON.stringify({ entries: [], hasNext: false })));
  vi.stubGlobal("fetch", fetch);
  render(<ToastProvider><FiscalSnapshots years={[{ year: 2024, status: "OPEN" }]} refresh={0} /></ToastProvider>);
  expect(fetch).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText("Fiscal year"), { target: { value: "2024" } });
  expect(await screen.findByText("No snapshot recorded")).toBeTruthy();
  expect(fetch.mock.calls[0]).toBeTruthy();
});
it("shows snapshot revision and a visible discrepancy requiring review", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ entries: [{ memberNumber: "CU-000001", fullName: "Fixture Member", principal: "100.00", income: "5.25", equity: "105.25", revision: 3, ledgerEquity: "115.25", reconciled: false }], hasNext: false }))));
  render(<ToastProvider><FiscalSnapshots years={[{ year: 2024, status: "CLOSED" }]} refresh={0} /></ToastProvider>);
  fireEvent.change(screen.getByLabelText("Fiscal year"), { target: { value: "2024" } });
  expect(await screen.findByText(/Revision 3/)).toBeTruthy();
  expect(screen.getByText(/Review required: ledger equity/)).toBeTruthy();
  expect(screen.getByRole("navigation", { name: "Balance forward pages" })).toBeTruthy();
});
