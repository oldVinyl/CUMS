import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { useState } from "react";
import { MemoryRouter } from "react-router-dom";
import App from "./App";
import ResponsiveOverlay from "./ResponsiveOverlay";
import MemberPicker from "./MemberPicker";
import EquityChart from "./EquityChart";
import ReportsPage from "./ReportsPage";
import LivePayments from "./LivePayments";
import MemberPortal from "./MemberPortal";
import UserAdministration from "./UserAdministration";
import { ToastProvider } from "./toast";
import type { Profile } from "./auth-api";

beforeEach(() => { vi.stubGlobal("matchMedia", () => ({ matches: false, addListener: () => {}, removeListener: () => {} })); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
const member = { id: "member-1", fullName: "Ama Test", memberNumber: "CU-000001" };
const profile: Profile = { userId: "account-1", email: "test@invalid.example", preferredSignInMethod: "TOTP", permissions: ["OWN_DATA_READ"] };
const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const wrap = (children: React.ReactNode) => render(<MemoryRouter><ToastProvider>{children}</ToastProvider></MemoryRouter>);

it("traps a drawer, closes with Escape and restores the opener", async () => {
 function Example() { const [open, setOpen] = useState(false); return <><button onClick={() => setOpen(true)}>Open details</button><ResponsiveOverlay open={open} title="Member details" onClose={() => setOpen(false)}><input aria-label="Private field" /></ResponsiveOverlay></>; }
 render(<Example />);
 const opener = screen.getByRole("button", { name: "Open details" }); opener.focus(); fireEvent.click(opener);
 expect(screen.getByRole("dialog")).toBeTruthy();
 expect(document.activeElement).toBe(screen.getByRole("heading", { name: "Member details" }));
 fireEvent.keyDown(document.activeElement!, { key: "Escape" });
 await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
 await waitFor(() => expect(document.activeElement).toBe(opener));
});

it("shows search results as typed, supports keyboard selection and clears stale selections", async () => {
 const select = vi.fn(); const fetchMock = vi.fn(async () => response({ items: [member] })); vi.stubGlobal("fetch", fetchMock);
 render(<MemberPicker onSelect={select} />);
 const input = screen.getByRole("combobox", { name: "Find member" });
 fireEvent.focus(input); fireEvent.change(input, { target: { value: "Ama" } });
 expect(await screen.findByRole("option", { name: /Ama Test.*CU-000001/ })).toBeTruthy();
 expect(fetchMock).toHaveBeenCalledWith("/api/v1/members?search=Ama&size=10", expect.objectContaining({ signal: expect.any(AbortSignal) }));
 fireEvent.keyDown(input, { key: "Enter" }); expect(select).toHaveBeenLastCalledWith(member);
 fireEvent.change(input, { target: { value: "Someone else" } }); expect(select).toHaveBeenLastCalledWith(null);
});

it("shows recoverable search failures instead of a misleading empty dropdown", async () => {
 vi.stubGlobal("fetch", vi.fn(async () => response({}, 500)));
 render(<MemberPicker onSelect={() => {}} />); fireEvent.focus(screen.getByRole("combobox"));
 expect(await screen.findByText("Members could not be loaded. Try searching again.")).toBeTruthy();
});

it("labels actual balance dates and plots losses below the zero baseline", () => {
 render(<EquityChart points={[{ date: "2026-02-28", equity: "150.25" }, { date: "2026-01-31", equity: "-20.50" }]} />);
 const chart = screen.getByRole("img"); expect(chart.getAttribute("aria-label")).toContain("31 Jan 2026");
 expect(chart.getAttribute("aria-label")).toContain("150.25");
 expect(chart.querySelector(".equity-zero")).toBeTruthy();
 expect(chart.querySelector("polyline")!.getAttribute("points")).not.toContain("NaN");
 expect(screen.getByText("View exact monthly balances")).toBeTruthy();
});

it("reports show member results without opening a separate select", async () => {
 vi.stubGlobal("fetch", vi.fn(async () => response({ items: [member] })));
 wrap(<ReportsPage asAt="2026-10-03" profile={{ ...profile, permissions: ["REPORT_EXPORT", "MEMBER_READ", "LEDGER_READ"] }} />);
 const input = screen.getByRole("combobox", { name: "Find member for statement" }); fireEvent.change(input, { target: { value: "Ama" } });
 fireEvent.click(await screen.findByRole("option", { name: /Ama Test.*CU-000001/ }));
 expect(screen.getByRole("heading", { name: "Member statement" })).toBeTruthy();
});

it("members see published bank details and upload their own receipt from a drawer", async () => {
 const fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
  if (input.includes("/methods")) return response([{ id: "bank-1", name: "Union bank", instructions: "Account 000123. Use your transfer reference.", active: true }]);
  if (input.includes("/own-member")) return response({ memberId: member.id });
  if (input.includes("/online/config")) return response({ enabled: false });
  if (input.includes("/proofs")) return response({ id: "proof-1" });
  if (init?.method === "POST") return response({});
  return response({ payments: [], hasNext: false });
 }); vi.stubGlobal("fetch", fetchMock);
 wrap(<LivePayments profile={profile} />);
 expect(await screen.findByText("Account 000123. Use your transfer reference.")).toBeTruthy();
 fireEvent.click(await screen.findByRole("button", { name: "Submit receipt" }));
 const drawer = within(screen.getByRole("dialog")); expect(drawer.queryByRole("combobox", { name: "Find member" })).toBeNull();
 fireEvent.change(drawer.getByLabelText("Payment method"), { target: { value: "bank-1" } });
 fireEvent.change(drawer.getByLabelText("Amount paid (GHS)"), { target: { value: "125.50" } });
 fireEvent.change(drawer.getByLabelText(/Receipt \(JPEG/), { target: { files: [new File(["receipt"], "receipt.pdf", { type: "application/pdf" })] } });
 fireEvent.submit(drawer.getByRole("button", { name: "Submit for verification" }).closest("form")!);
 await screen.findByText("Payment submitted for verification");
 const upload = fetchMock.mock.calls.find(([url]) => url.endsWith("/proofs"));
 expect((upload?.[1]?.body as FormData).get("memberId")).toBe(member.id);
 const post = fetchMock.mock.calls.find(([url, init]) => url === "/api/v1/payments" && init?.method === "POST");
 expect(JSON.parse(post?.[1]?.body as string)).toMatchObject({ memberId: member.id, proofId: "proof-1", amount: "125.50" });
});

it("places contacts and the statement before transactions, with only own export after them", async () => {
 const figures = { principal: "100", income: "10", equity: "110" };
 vi.stubGlobal("fetch", vi.fn(async (url: string) => response(url.includes("/profile") ? { version: 1, email: profile.email } : url.includes("/ledger") ? { entries: [], hasNext: false } : { ...member, memberId: member.id, asAt: "2026-10-03", balanceBf: figures, ytd: figures, total: figures, roePercent: "10", equityRatioPercent: "1", trend: [], allocations: [] })));
 wrap(<MemberPortal asAt="2026-10-03" />); await screen.findByText("Your contact details");
 const titles = screen.getAllByRole("heading").map(item => item.textContent);
 expect(titles.indexOf("Your contact details")).toBeLessThan(titles.indexOf("Your transactions"));
 expect(titles.indexOf("Member statement")).toBeLessThan(titles.indexOf("Your transactions"));
 expect(titles.slice(titles.indexOf("Your transactions") + 1)).toEqual(["Your data"]);
});

it("invites a new recipient by email without requiring an existing member", async () => {
 const fetchMock = vi.fn(async (url: string, init?: RequestInit) => response(init?.method === "POST" ? { url: "https://invalid.example/accept-invitation#token=fixture", expiresAt: "2026-10-06T12:00:00Z" } : url.includes("/pending") ? [] : { users: [], hasNext: false })); vi.stubGlobal("fetch", fetchMock);
 wrap(<UserAdministration profile={{ ...profile, permissions: ["USER_ADMIN", "MEMBER_WRITE"] }} />);
 fireEvent.change(screen.getByLabelText("Recipient full name"), { target: { value: "New Member" } });
 fireEvent.change(screen.getByLabelText("Recipient email"), { target: { value: "new@invalid.example" } });
 fireEvent.click(screen.getByRole("button", { name: "Send invitation email" }));
 await screen.findByRole("heading", { name: "Invitation email queued" });
 const request = fetchMock.mock.calls.find(([url]) => url.endsWith("/invitations/email"));
 expect(JSON.parse(request?.[1]?.body as string)).toEqual({ fullName: "New Member", email: "new@invalid.example" });
 expect(fetchMock.mock.calls.some(([url]) => url.includes("/members"))).toBe(false);
});


it("lands on Overview after sign-in and opens an account menu without logging out", async () => {
 const fetchMock = vi.fn(async (url: string) => {
  if (url.endsWith("/auth/login")) return response({ stage: "AUTHENTICATED", challenge: null });
  if (url.endsWith("/auth/me")) return response({ ...profile, preferredSignInMethod: "TOTP", permissions: ["DASHBOARD_READ_ALL", "MEMBER_READ", "OWN_DATA_READ"] });
  return response({ message: "Fixture unavailable" }, 404);
 }); vi.stubGlobal("fetch", fetchMock);
 render(<MemoryRouter initialEntries={["/sign-in"]}><ToastProvider><App /></ToastProvider></MemoryRouter>);
 fireEvent.change(screen.getByLabelText("Email address"), { target: { value: profile.email } });
 fireEvent.change(screen.getByLabelText("Password"), { target: { value: "fixture-password" } });
 fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
 await screen.findByRole("heading", { name: "Overview" });
 fireEvent.click(screen.getByRole("button", { name: "Open account menu" }));
 const account = within(screen.getByRole("navigation", { name: "Your account" }));
 expect(account.getByRole("link", { name: "My membership" })).toBeTruthy();
 expect(account.getByRole("link", { name: "Settings" })).toBeTruthy();
 expect(account.getByRole("button", { name: "Sign out" })).toBeTruthy();
 expect(fetchMock.mock.calls.some(([url]) => url.includes("logout"))).toBe(false);
 fireEvent.keyDown(document, { key: "Escape" });
 expect(screen.queryByRole("navigation", { name: "Your account" })).toBeNull();
});

it("gives signed-out pages the full workspace width without a navigation sidebar", async () => {
 vi.stubGlobal("fetch", vi.fn(async () => response({}, 401)));
 wrap(<App />);
 expect(screen.queryByRole("navigation", { name: "Main navigation" })).toBeNull();
 expect(screen.queryByRole("navigation", { name: "Mobile navigation" })).toBeNull();
 expect(screen.getByRole("link", { name: "Sign in" })).toBeTruthy();
});
