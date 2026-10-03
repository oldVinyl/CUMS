import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import LedgerApprovals from "./LedgerApprovals";
import { ToastProvider } from "./toast";

beforeEach(() => vi.stubGlobal("matchMedia", () => ({ matches: false, addListener() {}, removeListener() {} })));
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
const item = { id: "request-1", makerId: "maker-1", actionType: "LEDGER_POST", payload: { amount: "125.50", type: "SEED" }, fullName: "Ama Example", memberNumber: "CU-000001", originalAmount: null, requiredApprovals: 1, approvals: 0 };
const profile = { userId: "maker-1", email: "test@invalid.example", preferredSignInMethod: "TOTP" as const, permissions: ["LEDGER_READ", "APPROVE"] };
it("lets the maker cancel but does not offer self approval", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [item] }));
  render(<ToastProvider><LedgerApprovals profile={profile} /></ToastProvider>);
  await screen.findByText("Ama Example");
  expect(screen.getByRole("button", { name: "Cancel my request" })).toBeTruthy();
  expect(screen.queryByRole("button", { name: "Review approval" })).toBeNull();
});
it("requires a review confirmation before submitting approval", async () => {
  const fetchMock = vi.fn((path: string, options?: RequestInit) => Promise.resolve({ ok: true, status: options?.method === "POST" ? 204 : 200, json: async () => path.endsWith("/approvals") ? [item] : undefined }));
  vi.stubGlobal("fetch", fetchMock);
  render(<ToastProvider><LedgerApprovals profile={{ ...profile, userId: "checker-2" }} /></ToastProvider>);
  fireEvent.click(await screen.findByRole("button", { name: "Review approval" }));
  expect(fetchMock.mock.calls.some(call => call[1]?.method === "POST")).toBe(false);
  fireEvent.click(screen.getByRole("button", { name: "Confirm and approve" }));
  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith("/api/v1/ledger/approvals/request-1/decide", expect.objectContaining({ method: "POST", body: JSON.stringify({ decision: "APPROVE", comment: "" }) })));
});
