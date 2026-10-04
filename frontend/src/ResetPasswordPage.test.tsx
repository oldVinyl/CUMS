import "@testing-library/jest-dom/vitest";
import { afterEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import ResetPasswordPage from "./ResetPasswordPage";
vi.mock("./toast-context", () => ({
  usePromiseToast:
    () =>
    <T,>(promise: Promise<T>) =>
      promise,
}));
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.history.replaceState(null, "", "/");
});
it("requests an account-neutral recovery email without asking for another factor", async () => {
  const fetchMock = vi.fn(async () => ({ ok: true, status: 202 }));
  vi.stubGlobal("fetch", fetchMock);
  render(
    <MemoryRouter>
      <ResetPasswordPage />
    </MemoryRouter>,
  );
  fireEvent.change(screen.getByLabelText("Account email"), {
    target: { value: "anonymous@invalid.example" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Request recovery email" }));
  await screen.findByText("Recovery request complete");
  expect(fetchMock).toHaveBeenCalledWith(
    "/api/v1/auth/password-reset/request",
    expect.objectContaining({ body: JSON.stringify({ email: "anonymous@invalid.example" }) }),
  );
});
it("keeps the secret in memory, removes the URL fragment and requires matching passwords", async () => {
  window.history.replaceState(null, "", "/reset-password#token=synthetic-secret");
  const fetchMock = vi.fn(async () => ({ ok: true, status: 204 }));
  vi.stubGlobal("fetch", fetchMock);
  render(
    <MemoryRouter>
      <ResetPasswordPage />
    </MemoryRouter>,
  );
  expect(window.location.hash).toBe("");
  fireEvent.change(screen.getByLabelText("New password"), {
    target: { value: "New synthetic password! 2026" },
  });
  expect(screen.getByRole("button", { name: "Set password" })).toBeDisabled();
  fireEvent.change(screen.getByLabelText("Confirm new password"), {
    target: { value: "New synthetic password! 2026" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Set password" }));
  await screen.findByText("Recovery request complete");
  expect(fetchMock).toHaveBeenCalledWith(
    "/api/v1/auth/password-reset/complete",
    expect.objectContaining({
      body: JSON.stringify({ token: "synthetic-secret", password: "New synthetic password! 2026" }),
    }),
  );
  expect(window.sessionStorage.getItem("synthetic-secret")).toBeNull();
});
it("retains the form after a failed or expired link instead of claiming success", async () => {
  window.history.replaceState(null, "", "/reset-password#token=expired-fixture");
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => ({
      ok: false,
      status: 400,
      json: async () => ({ message: "Recovery link is invalid, expired or already used." }),
    })),
  );
  render(
    <MemoryRouter>
      <ResetPasswordPage />
    </MemoryRouter>,
  );
  for (const label of ["New password", "Confirm new password"])
    fireEvent.change(screen.getByLabelText(label), {
      target: { value: "New synthetic password! 2026" },
    });
  fireEvent.click(screen.getByRole("button", { name: "Set password" }));
  await waitFor(() =>
    expect(screen.getByRole("button", { name: "Set password" })).not.toBeDisabled(),
  );
  expect(screen.queryByText("Recovery request complete")).toBeNull();
});
