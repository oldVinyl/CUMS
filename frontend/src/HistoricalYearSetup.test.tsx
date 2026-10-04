import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import LiveLedger from "./LiveLedger";
import LedgerApprovals from "./LedgerApprovals";
import { ToastProvider } from "./toast";
import type { Profile } from "./auth-api";
const today = new Date().toISOString().slice(0, 10),
  year = Number(today.slice(0, 4)) - 1;
const profile: Profile = {
  userId: "synthetic-maker",
  email: "maker@invalid.example",
  permissions: ["LEDGER_READ", "PERIOD_UNLOCK"],
  preferredSignInMethod: "TOTP",
};
beforeEach(() =>
  vi.stubGlobal("matchMedia", () => ({
    matches: false,
    addListener: () => {},
    removeListener: () => {},
  })),
);
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});
it("requests a missing historical year from a responsive form with its source reason", async () => {
  const fetchMock = vi.fn(
    async (url: string, init?: RequestInit) =>
      new Response(
        JSON.stringify(
          init?.method === "POST"
            ? { approvalRequestId: "synthetic-request" }
            : url.endsWith("/approvals") || url.endsWith("/fiscal-years")
              ? []
              : { entries: [], hasNext: false },
        ),
        { status: 200 },
      ),
  );
  vi.stubGlobal("fetch", fetchMock);
  render(
    <ToastProvider>
      <LiveLedger profile={profile} asAt={today} />
    </ToastProvider>,
  );
  fireEvent.click(screen.getByRole("button", { name: "Set up historical year" }));
  const dialog = within(screen.getByRole("dialog"));
  expect(
    dialog.getByRole("button", { name: "Request year setup approval" }).hasAttribute("disabled"),
  ).toBe(true);
  fireEvent.change(dialog.getByLabelText("Historical year"), { target: { value: String(year) } });
  fireEvent.change(dialog.getByLabelText("Source and setup reason"), {
    target: { value: "Verified synthetic baseline" },
  });
  fireEvent.click(dialog.getByRole("button", { name: "Request year setup approval" }));
  await screen.findByText("Historical fiscal-year setup requested");
  const request = fetchMock.mock.calls.find(
    ([url, init]) => url.endsWith(`/${year}/create`) && init?.method === "POST",
  );
  expect(JSON.parse(request?.[1]?.body as string)).toEqual({
    reason: "Verified synthetic baseline",
  });
  expect(screen.queryByRole("dialog")).toBeNull();
});
it("shows a checker the period source and confirms setup without implying posted balances", async () => {
  const fetchMock = vi.fn(async (_url: string, init?: RequestInit) =>
    init?.method === "POST"
      ? new Response(null, { status: 204 })
      : new Response(
          JSON.stringify([
            {
              id: "synthetic-request",
              makerId: "other-maker",
              actionType: "PERIOD_CREATE",
              payload: { year, reason: "Verified synthetic baseline" },
              requiredApprovals: 1,
              approvals: 0,
            },
          ]),
          { status: 200 },
        ),
  );
  vi.stubGlobal("fetch", fetchMock);
  render(
    <ToastProvider>
      <LedgerApprovals
        profile={{
          ...profile,
          userId: "synthetic-checker",
          permissions: ["LEDGER_READ", "APPROVE"],
        }}
      />
    </ToastProvider>,
  );
  await screen.findByRole("heading", { name: `Set up historical fiscal year ${year}` });
  expect(screen.getByText(/No balances are posted by this decision/)).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Review approval" }));
  fireEvent.click(screen.getByRole("button", { name: "Confirm and approve" }));
  await screen.findByText("Approval completed");
  const request = fetchMock.mock.calls.find(
    ([url, init]) => url.endsWith("/decide") && init?.method === "POST",
  );
  expect(JSON.parse(request?.[1]?.body as string)).toMatchObject({ decision: "APPROVE" });
});
