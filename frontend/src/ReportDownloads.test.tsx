import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import ReportDownloads from "./ReportDownloads";
import { ToastProvider } from "./toast";

beforeEach(() => vi.stubGlobal("matchMedia", () => ({ matches: false, addListener() {}, removeListener() {} })));
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
it("downloads the selected format and uses a safe filename", async () => {
  const fetchMock = vi.fn().mockResolvedValue({ ok: true, blob: async () => new Blob(["xlsx"]) });
  vi.stubGlobal("fetch", fetchMock);
  const create = vi.fn().mockReturnValue("blob:report");
  Object.defineProperty(URL, "createObjectURL", { configurable: true, value: create });
  Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: vi.fn() });
  const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
    expect(this.download).toBe("sheet-b-2026-10-03.xlsx");
  });
  render(<ToastProvider><ReportDownloads path="/api/v1/reports/dashboard?asAt=2026-10-03" filename="sheet-b-2026-10-03" /></ToastProvider>);
  fireEvent.click(screen.getByRole("button", { name: "Excel" }));
  await waitFor(() => expect(click).toHaveBeenCalledOnce());
  expect(fetchMock).toHaveBeenCalledWith("/api/v1/reports/dashboard?asAt=2026-10-03&format=xlsx", { credentials: "same-origin" });
});
it("shows export denial without creating a download", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, json: async () => ({ message: "Your export permission was revoked." }) }));
  const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
  render(<ToastProvider><ReportDownloads path="/api/v1/reports/dashboard?asAt=2026-10-03" filename="sheet-b" /></ToastProvider>);
  fireEvent.click(screen.getByRole("button", { name: "PDF" }));
  await screen.findByText("Your export permission was revoked.");
  expect(click).not.toHaveBeenCalled();
});
