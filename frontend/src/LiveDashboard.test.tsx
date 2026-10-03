import { afterEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import LiveDashboard from "./LiveDashboard";
import { ToastProvider } from "./toast";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

it("renders live Sheet B figures and filters member rows without changing totals", async () => {
  vi.stubGlobal("matchMedia", () => ({ matches: false,
    addListener: () => {}, removeListener: () => {} }));
  const figures = (principal: string, income: string, equity: string) =>
    ({ principal, income, equity });
  const total = { memberId: null, memberNumber: null, fullName: "Total",
    balanceBf: figures("30000.00", "4796.87", "34796.87"),
    ytd: figures("1500.00", "100.00", "1600.00"),
    total: figures("31500.00", "4896.87", "36396.87"),
    roePercent: "15.55", equityRatioPercent: "100.00" };
  const first = { ...total, memberId: "m1", memberNumber: "CU-000001",
    fullName: "Ama Example", equityRatioPercent: "96.98" };
  const second = { ...total, memberId: "m2", memberNumber: "CU-000002",
    fullName: "Kojo Example", equityRatioPercent: "3.02" };
  const fetchMock = vi.fn(() => Promise.resolve({ ok: true,
    json: async () => ({ asAt: "2001-02-01", fiscalYear: 2001,
      members: [first, second], totals: total }) }));
  vi.stubGlobal("fetch", fetchMock);
  render(<ToastProvider><LiveDashboard asAt="2001-02-01" /></ToastProvider>);
  expect(await screen.findByText("Ama Example")).toBeTruthy();
  expect(screen.getByText("Kojo Example")).toBeTruthy();
  expect(screen.getAllByText("GH₵ 36,396.87").length).toBeGreaterThan(0);
  fireEvent.change(screen.getByLabelText("Search members"),
    { target: { value: "Ama" } });
  expect(screen.queryByText("Kojo Example")).toBeNull();
  expect(screen.getByText("Totals include all members at this date.")).toBeTruthy();
  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
    "/api/v1/dashboard?asAt=2001-02-01",
    expect.objectContaining({ credentials: "same-origin" }),
  ));
});
