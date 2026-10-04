import "@testing-library/jest-dom/vitest";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import ContributionForm, { HistoricalRequestImpact, type Impact } from "./ContributionForm";
import type { Profile } from "./auth-api";
import { ledgerRequest } from "./ledger-api";
vi.mock("./ledger-api", () => ({ ledgerRequest: vi.fn() }));
vi.mock("./toast-context", () => ({
  usePromiseToast:
    () =>
    <T,>(promise: Promise<T>) =>
      promise,
}));
vi.mock("./MemberPicker", () => ({
  default: ({ onSelect }: { onSelect: (member: { id: string }) => void }) => (
    <button type="button" onClick={() => onSelect({ id: "anonymous-member" })}>
      Choose fixture member
    </button>
  ),
}));
const profile: Profile = {
  userId: "maker",
  email: "fixture@invalid.example",
  preferredSignInMethod: "TOTP",
  permissions: ["LEDGER_POST", "LEDGER_BACKDATE"],
};
const impact: Impact = {
  sourceFingerprint: "fixture-checksum",
  earliestNextCycleDate: "2026-01-01",
  repostAllowed: true,
  restriction: null,
  affectedCycles: [],
};
beforeEach(() => vi.resetAllMocks());
afterEach(cleanup);
function prepare(
  onSubmit = vi.fn(async (promise: Promise<unknown>) => {
    await promise;
  }),
) {
  render(<ContributionForm profile={profile} busy={false} onSubmit={onSubmit} />);
  fireEvent.click(screen.getByText("Choose fixture member"));
  fireEvent.change(screen.getByLabelText("Amount (GH₵)"), { target: { value: "25.50" } });
  fireEvent.click(screen.getByLabelText("Use a historical date"));
  fireEvent.change(screen.getByLabelText("Correction reason"), {
    target: { value: "Correct omitted contribution" },
  });
  return onSubmit;
}
it("requires reviewed impact before requesting approval and sends the preview checksum", async () => {
  vi.mocked(ledgerRequest).mockResolvedValue(impact);
  const submit = prepare();
  fireEvent.click(screen.getByText("Preview historical impact"));
  await screen.findByText("Investment impact");
  expect(screen.getByRole("button", { name: "Request contribution approval" })).toBeDisabled();
  fireEvent.click(
    screen.getByLabelText("I reviewed the dates, affected cycles and financial impact"),
  );
  fireEvent.click(screen.getByRole("button", { name: "Request contribution approval" }));
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
  expect(ledgerRequest).toHaveBeenLastCalledWith(
    "/contributions/historical",
    "POST",
    expect.objectContaining({
      sourceFingerprint: "fixture-checksum",
      mode: "FUTURE_ONLY",
      amount: "25.50",
    }),
  );
});
it("invalidates the preview and confirmation when a financial input changes", async () => {
  vi.mocked(ledgerRequest).mockResolvedValue(impact);
  prepare();
  fireEvent.click(screen.getByText("Preview historical impact"));
  await screen.findByText("Investment impact");
  fireEvent.click(
    screen.getByLabelText("I reviewed the dates, affected cycles and financial impact"),
  );
  fireEvent.change(screen.getByLabelText("Amount (GH₵)"), { target: { value: "30" } });
  expect(screen.queryByText("Investment impact")).toBeNull();
  expect(screen.getByText("Preview historical impact")).toBeTruthy();
});
it("discards a late preview response after the inputs change", async () => {
  let resolve!: (value: Impact) => void;
  vi.mocked(ledgerRequest).mockReturnValue(
    new Promise<Impact>((done) => {
      resolve = done;
    }),
  );
  prepare();
  fireEvent.click(screen.getByText("Preview historical impact"));
  fireEvent.change(screen.getByLabelText("Amount (GH₵)"), { target: { value: "30" } });
  await act(async () => resolve(impact));
  expect(screen.queryByText("Investment impact")).toBeNull();
});
it("keeps checker approval unavailable when the source review fails", async () => {
  vi.mocked(ledgerRequest).mockRejectedValue(
    new Error("Financial source changed. Run a fresh preview."),
  );
  const ready = vi.fn();
  render(<HistoricalRequestImpact requestId="fixture-request" onReady={ready} />);
  expect(await screen.findByRole("alert")).toHaveTextContent("Financial source changed");
  expect(ready).toHaveBeenCalledWith(false);
  expect(ready).not.toHaveBeenCalledWith(true);
});
