import { afterEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { formatMoney } from "./money";
import LiveDashboard from "./LiveDashboard";
import { ToastProvider } from "./toast";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

it("handles an empty ledger without a chart or invalid percentages", async () => {
  const figures = { principal: "0", income: "0", equity: "0" };
  vi.stubGlobal("fetch", vi.fn(() => Promise.resolve({ ok: true,
    json: async () => ({ asAt: "2001-02-01", fiscalYear: 2001, members: [], trend: [],
      totals: { balanceBf: figures, ytd: figures, total: figures, roePercent: "0", equityRatioPercent: "0" } }) })));
  render(<MemoryRouter><ToastProvider><LiveDashboard asAt="2001-02-01" /></ToastProvider></MemoryRouter>);
  expect(await screen.findByText("A fresh start for your union")).toBeTruthy();
  expect(screen.getByText("No equity history yet")).toBeTruthy();
  expect(screen.queryByText(/NaN|Infinity/)).toBeNull();
});

it("offers a retry when the dashboard cannot be loaded", async () => {
  vi.stubGlobal("matchMedia", () => ({ matches: false, addListener: () => {}, removeListener: () => {} }));
  const fetchMock = vi.fn(() => Promise.resolve({ ok: false, json: async () => ({ message: "Please retry." }) }));
  vi.stubGlobal("fetch", fetchMock);
  render(<MemoryRouter><ToastProvider><LiveDashboard asAt="2001-02-01" /></ToastProvider></MemoryRouter>);
  expect(await screen.findByRole("heading", { name: "Overview unavailable" })).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Try again" }));
  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
});

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
  render(<MemoryRouter><ToastProvider><LiveDashboard asAt="2001-02-01" /></ToastProvider></MemoryRouter>);
  expect((await screen.findAllByText("Ama Example"))[0]).toBeTruthy();
  expect(screen.getAllByText("Kojo Example")[0]).toBeTruthy();
  expect(screen.getAllByText(formatMoney("36396.87")).length).toBeGreaterThan(0);
  fireEvent.change(screen.getByLabelText("Search members"),
    { target: { value: "Ama" } });
  expect(screen.queryByText("Kojo Example")).toBeNull();
  expect(screen.getByText("Totals include all members at this date.")).toBeTruthy();
  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
    "/api/v1/dashboard?asAt=2001-02-01",
    expect.objectContaining({ credentials: "same-origin" }),
  ));
});
