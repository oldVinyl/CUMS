import { afterEach, beforeEach, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render as renderComponent,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import type { ReactElement } from "react";
import LiveInvestments from "./LiveInvestments";
import { ToastProvider } from "./toast";

const render = (ui: ReactElement) => renderComponent(<MemoryRouter>{ui}</MemoryRouter>);

beforeEach(() => vi.stubGlobal("matchMedia", () => ({ matches: false,
  addListener: () => {}, removeListener: () => {} })));
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

it("renders the Sheet A fixture totals in the allocation table", async () => {
  const contributions = [5000, 7000, 12000, ...Array(9).fill(2000)] as number[];
  const cycle = { id: "sheet-a", tenor: "91_DAY", institution: "Fixture Bank",
    referenceNumber: "SHEET-A", purchaseDate: "2026-10-02", maturityDate: "2027-01-01",
    cost: "42271.26", maturityValue: "45435.00", actualProceeds: null, status: "POSTED" };
  vi.stubGlobal("fetch", vi.fn((path: string) => Promise.resolve({
    ok: true, status: 200,
    json: async () => path.endsWith("/sheet-a") ? {
      investment: cycle, pool: "42000.00", costDifference: "271.26",
      expectedIncome: "3163.74", expectedClosing: "45163.74",
      maturityAllocations: [],
      allocations: contributions.map((participation, index) => ({
        memberId: `fixture-${index + 1}`, memberNumber: `CU-${String(index + 1).padStart(6, "0")}`,
        fullName: `Fixture ${index + 1}`, participation: String(participation), ratioPercent: "0.00",
        costShare: "0.00", maturityValueShare: "0.00", incomeShare: "0.00", closingBalance: "0.00",
      })),
    } : { entries: [cycle], hasNext: false },
  })));
  render(<ToastProvider><LiveInvestments profile={{ userId: "auditor-1",
    email: "auditor@example.test", preferredSignInMethod: "TOTP",
    permissions: ["INVESTMENT_READ"] }} /></ToastProvider>);
  fireEvent.click(await screen.findByRole("button", { name: "View allocation" }));
  const footer = await screen.findByText("Total");
  const totals = within(footer.closest("tr") as HTMLElement);
  expect(totals.getByText("GH₵ 42,000.00")).toBeTruthy();
  expect(totals.getByText("GH₵ 42,271.26")).toBeTruthy();
  expect(totals.getByText("GH₵ 45,435.00")).toBeTruthy();
  expect(totals.getByText("GH₵ 3,163.74")).toBeTruthy();
  expect(totals.getByText("GH₵ 45,163.74")).toBeTruthy();
});

it("shows a live allocation preview with a visible cost difference", async () => {
  const cycle = {
    id: "cycle-1",
    tenor: "91_DAY",
    institution: "Harbour Bank",
    referenceNumber: "TB-1",
    purchaseDate: "2026-10-02",
    maturityDate: "2027-01-01",
    cost: "201.00",
    maturityValue: "221.00",
    status: "DRAFT",
  };
  const fetchMock = vi.fn((path: string) =>
    Promise.resolve({
      ok: true,
      status: 200,
      json: async () =>
        path.endsWith("/cycle-1")
          ? {
              investment: cycle,
              pool: "200.00",
              costDifference: "1.00",
              expectedIncome: "20.00",
              expectedClosing: "220.00",
              maturityAllocations: [],
              allocations: [
                {
                  memberId: "member-1",
                  memberNumber: "CU-000001",
                  fullName: "Ama Example",
                  participation: "200.00",
                  ratioPercent: "100.00",
                  costShare: "201.00",
                  maturityValueShare: "221.00",
                  incomeShare: "20.00",
                  closingBalance: "220.00",
                },
              ],
            }
          : { entries: [cycle], hasNext: false },
    }),
  );
  vi.stubGlobal("fetch", fetchMock);
  render(
    <ToastProvider>
      <LiveInvestments
        profile={{
          userId: "treasurer-1",
          email: "treasurer@example.test",
          preferredSignInMethod: "TOTP",
          permissions: ["INVESTMENT_READ", "INVESTMENT_WRITE"],
        }}
      />
    </ToastProvider>,
  );
  expect(await screen.findByText("Harbour Bank")).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "View allocation" }));
  expect(await screen.findByText("Ama Example")).toBeTruthy();
  expect(screen.getByText("Cost less pool")).toBeTruthy();
  await waitFor(() =>
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/investments/cycle-1",
      expect.objectContaining({ credentials: "same-origin" }),
    ),
  );
});

it("creates a principal-only draft from a matured investment", async () => {
  const cycle = { id: "source", tenor: "91_DAY", institution: "Fixture Bank",
    referenceNumber: "SOURCE", purchaseDate: "2026-06-01", maturityDate: "2026-10-01",
    cost: "100.00", maturityValue: "120.00", actualProceeds: "120.00", status: "MATURED" };
  const detail = { investment: cycle, pool: "100.00", costDifference: "0.00",
    expectedIncome: "20.00", expectedClosing: "120.00",
    allocations: [{ memberId: "member-1", memberNumber: "CU-000001", fullName: "Ama Example",
      participation: "100.00", ratioPercent: "100.00", costShare: "100.00",
      maturityValueShare: "120.00", incomeShare: "20.00", closingBalance: "120.00" }],
    maturityAllocations: [{ memberId: "member-1", actualMaturityShare: "120.00",
      actualIncomeShare: "20.00" }] };
  const fetchMock = vi.fn((path: string) => Promise.resolve({
    ok: true, status: path.endsWith("/rollover-draft") ? 201 : 200,
    json: async () => path.endsWith("/rollover-draft") ? { id: "next" }
      : path.endsWith("/settings/rollover") ? { defaultRolloverPolicy: "ROLL_PRINCIPAL_ONLY" }
      : path.endsWith("/source") || path.endsWith("/next") ? detail : { entries: [cycle], hasNext: false },
  }));
  vi.stubGlobal("fetch", fetchMock);
  render(<ToastProvider><LiveInvestments profile={{ userId: "treasurer-1",
    email: "treasurer@example.test", preferredSignInMethod: "TOTP",
    permissions: ["INVESTMENT_READ", "INVESTMENT_WRITE"] }} /></ToastProvider>);
  fireEvent.click(await screen.findByRole("button", { name: "View allocation" }));
  fireEvent.click(await screen.findByRole("button", { name: "Create rollover draft" }));
  await waitFor(() => expect((screen.getByLabelText("Rollover policy") as HTMLSelectElement).value)
    .toBe("ROLL_PRINCIPAL_ONLY"));
  fireEvent.change(screen.getByLabelText("Maturity date"),
    { target: { value: "2027-01-01" } });
  fireEvent.change(screen.getByLabelText("Cost (GH₵)"),
    { target: { value: "100.00" } });
  fireEvent.change(screen.getByLabelText("Expected maturity value (GH₵)"),
    { target: { value: "110.00" } });
  fireEvent.click(screen.getByRole("button", { name: "Create rollover preview" }));
  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
    "/api/v1/investments/source/rollover-draft",
    expect.objectContaining({ method: "POST", body: expect.stringContaining('"policy":"ROLL_PRINCIPAL_ONLY"') }),
  ));
});
