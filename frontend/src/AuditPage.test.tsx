import { afterEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import AuditPage from "./AuditPage";
import { ToastProvider } from "./toast";
import type { Profile } from "./auth-api";
const profile = { userId: "audit-user", email: "auditor@invalid.example", permissions: ["AUDIT_READ"] } as Profile;
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
it("does not fetch union events for an ordinary member", () => {
  const fetch = vi.fn(); vi.stubGlobal("fetch", fetch);
  render(<ToastProvider><AuditPage profile={{ ...profile, permissions: ["OWN_DATA_READ"] }} /></ToastProvider>);
  expect(screen.getByRole("heading", { name: "Audit access required" })).toBeTruthy();
  expect(fetch).not.toHaveBeenCalled();
});
it("handles an empty log and retries a failed request without showing stale events", async () => {
  const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ message: "Session expired. Please sign in." }), { status: 401 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ events: [], hasNext: false })));
  vi.stubGlobal("fetch", fetch);
  render(<ToastProvider><AuditPage profile={profile} /></ToastProvider>);
  expect((await screen.findByRole("alert")).textContent).toContain("Session expired");
  expect((screen.getByRole("button", { name: "Export applied filters" }) as HTMLButtonElement).disabled).toBe(true);
  fireEvent.click(screen.getByRole("button", { name: "Retry" }));
  expect(await screen.findByText("No events match these filters.")).toBeTruthy();
});
it("applies filters explicitly and displays literal event text safely", async () => {
  const fetch = vi.fn<(input: RequestInfo | URL) => Promise<Response>>(async () => new Response(JSON.stringify({ events: [{ id: "event-id", actorId: null, actorEmail: null,
    action: "<script>bad()</script>", entityType: "MEMBER", entityId: null, requestId: null, occurredAt: "2026-01-01T12:00:00Z" }], hasNext: false })));
  vi.stubGlobal("fetch", fetch);
  const { container } = render(<ToastProvider><AuditPage profile={profile} /></ToastProvider>);
  await screen.findByText("<script>bad()</script>");
  expect(container.querySelector("script")).toBeNull();
  fireEvent.change(screen.getByLabelText("Action"), { target: { value: "payment_posted" } });
  expect(fetch).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
  expect(fetch.mock.calls[1][0]).toContain("action=PAYMENT_POSTED");
});
