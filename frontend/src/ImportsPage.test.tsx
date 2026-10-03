import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import ImportsPage from "./ImportsPage";
import { ToastProvider } from "./toast";
import type { Profile } from "./auth-api";
const profile = { userId: "maker", email: "fixture@invalid.example", permissions: ["MEMBER_IMPORT", "MEMBER_WRITE"] } as Profile;
beforeEach(() => { vi.stubGlobal("matchMedia", () => ({ matches: false, addListener: () => {}, removeListener: () => {} })); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
it("does not request batch data for an ordinary member", () => {
  const fetch = vi.fn(); vi.stubGlobal("fetch", fetch);
  render(<ToastProvider><ImportsPage profile={{ ...profile, permissions: ["OWN_DATA_READ"] }} /></ToastProvider>);
  expect(screen.getByRole("heading", { name: "Import access required" })).toBeTruthy(); expect(fetch).not.toHaveBeenCalled();
});
it("shows row validation errors without offering confirmation or writing financial records", async () => {
  const calls: string[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: string) => {
    calls.push(input);
    return new Response(JSON.stringify(input.endsWith("/dry-run") ? { kind: "MEMBERS", fileHash: "fixture-hash", rowCount: 1, total: "0", valid: false, warnings: [], rows: [{ row: 1, memberNumber: "CU-000001", name: "Fixture", amount: null, effectiveDate: "2026-01-01", errors: ["Member number already exists."] }] } : { batches: [], hasNext: false }));
  }));
  render(<ToastProvider><ImportsPage profile={profile} /></ToastProvider>);
  await screen.findByText("No import batches yet.");
  fireEvent.change(screen.getByLabelText("Source file"), { target: { files: [new File(["fixture"], "members.csv", { type: "text/csv" })] } });
  fireEvent.submit(screen.getByRole("button", { name: "Run dry run" }).closest("form")!);
  expect(await screen.findByText("Member number already exists.", {}, { timeout: 5000 })).toBeTruthy();
  expect(screen.queryByRole("button", { name: "Confirm and submit batch" })).toBeNull();
  expect(calls.filter(path => path.endsWith("/dry-run"))).toHaveLength(1);
});
