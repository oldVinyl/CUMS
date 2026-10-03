import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import OwnDataExport from "./OwnDataExport";
import { ToastProvider } from "./toast";
beforeEach(() => vi.stubGlobal("matchMedia", () => ({ matches: false, addListener() {}, removeListener() {} })));
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
it("downloads only the own-data endpoint with a stable JSON filename", async () => {
  const fetch = vi.fn(async () => ({ ok: true, blob: async () => new Blob(['{"schemaVersion":1}']) })); vi.stubGlobal("fetch", fetch);
  Object.defineProperty(URL, "createObjectURL", { configurable: true, value: vi.fn(() => "blob:own-export") });
  Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: vi.fn() });
  const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) { expect(this.download).toBe("membership-data.json"); });
  render(<ToastProvider><OwnDataExport /></ToastProvider>);
  fireEvent.click(screen.getByRole("button", { name: "Download my data" }));
  await waitFor(() => expect(click).toHaveBeenCalledOnce());
  expect(fetch).toHaveBeenCalledWith("/api/v1/portal/export.json", { credentials: "same-origin" });
});
it("gives server feedback and never creates a download when permission is denied", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => ({ ok: false, json: async () => ({ message: "Membership is unavailable." }) })));
  const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
  render(<ToastProvider><OwnDataExport /></ToastProvider>);
  fireEvent.click(screen.getByRole("button", { name: "Download my data" }));
  expect(await screen.findByText("Membership is unavailable.")).toBeTruthy(); expect(click).not.toHaveBeenCalled();
});
