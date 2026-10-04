import "@testing-library/jest-dom/vitest";
import { afterEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import NotificationAdministration from "./NotificationAdministration";
vi.mock("./toast-context", () => ({
  usePromiseToast:
    () =>
    <T,>(promise: Promise<T>) =>
      promise,
}));
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});
const item = {
  id: "fixture-notification",
  accountId: "fixture-account",
  kind: "APPROVAL",
  outcome: "APPROVED",
  status: "FAILED",
  attempts: 10,
  createdAt: "2026-01-01T12:00:00Z",
  sentAt: null,
  nextAttemptAt: "2026-01-01T12:10:00Z",
  canRetry: true,
};
it("requires explicit retry confirmation and refreshes queued status", async () => {
  vi.stubGlobal("matchMedia", () => ({ matches: false, addListener() {}, removeListener() {} }));
  const fetchMock = vi.fn(async (_path: string, options?: RequestInit) => ({
    ok: true,
    status: options?.method === "POST" ? 204 : 200,
    json: async () => ({ items: [item], hasNext: false }),
  }));
  vi.stubGlobal("fetch", fetchMock);
  render(<NotificationAdministration />);
  fireEvent.click(await screen.findByRole("button", { name: "Review retry" }));
  expect(fetchMock.mock.calls.some((call) => call[1]?.method === "POST")).toBe(false);
  fireEvent.click(screen.getByRole("button", { name: "Confirm retry" }));
  await waitFor(() =>
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/notifications/fixture-notification/retry",
      expect.objectContaining({ method: "POST" }),
    ),
  );
});
it("shows an empty state and sends the selected status filter", async () => {
  const fetchMock = vi.fn(async () => ({
    ok: true,
    status: 200,
    json: async () => ({ items: [], hasNext: false }),
  }));
  vi.stubGlobal("fetch", fetchMock);
  render(<NotificationAdministration />);
  await screen.findByText("No notifications match this status.");
  fireEvent.change(screen.getByLabelText("Status"), { target: { value: "FAILED" } });
  await waitFor(() =>
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/notifications?status=FAILED&page=0",
      expect.anything(),
    ),
  );
});
it("does not expose a retry action for expired invitations", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => ({
      ok: true,
      status: 200,
      json: async () => ({
        items: [{ ...item, kind: "INVITATION", canRetry: false }],
        hasNext: false,
      }),
    })),
  );
  render(<NotificationAdministration />);
  await screen.findByText(
    "The account or link is no longer eligible. Review the account and request a new invitation or recovery link.",
  );
  expect(screen.queryByRole("button", { name: "Review retry" })).toBeNull();
});
