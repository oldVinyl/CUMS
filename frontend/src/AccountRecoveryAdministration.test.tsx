import "@testing-library/jest-dom/vitest";
import { afterEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import AccountRecoveryAdministration from "./AccountRecoveryAdministration";
import type { Profile } from "./auth-api";
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
const profile: Profile = {
  userId: "maker",
  email: "maker@invalid.example",
  permissions: ["USER_ADMIN"],
  preferredSignInMethod: "TOTP",
};
const accounts = [
  { id: "maker", email: profile.email, status: "ACTIVE" },
  { id: "anonymous-target", email: "anonymous@invalid.example", status: "ACTIVE" },
];
it("requires offline verification and a bound preview before creating the approval request", async () => {
  const fetchMock = vi.fn(async (path: string, options?: RequestInit) => ({
    ok: true,
    status: 200,
    json: async () =>
      options?.method === "POST" && path.endsWith("/preview")
        ? { userId: "anonymous-target", securityFingerprint: "fixture-checksum" }
        : [],
  }));
  vi.stubGlobal("fetch", fetchMock);
  render(<AccountRecoveryAdministration profile={profile} accounts={accounts} />);
  expect(screen.queryByRole("option", { name: profile.email })).toBeNull();
  fireEvent.change(screen.getByLabelText(/Recovery account/), {
    target: { value: "anonymous-target" },
  });
  fireEvent.change(screen.getByLabelText(/Verification reason/), {
    target: { value: "Leadership verified identity offline" },
  });
  expect(screen.getByRole("button", { name: "Preview recovery" })).toBeDisabled();
  fireEvent.click(
    screen.getByLabelText("I verified the account holder and their registered email offline"),
  );
  fireEvent.click(screen.getByRole("button", { name: "Preview recovery" }));
  await screen.findByText(
    "Account security snapshot recorded. Changes before approval will require a fresh request.",
  );
  fireEvent.click(screen.getByRole("button", { name: "Request recovery approval" }));
  await waitFor(() =>
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/account-recovery",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({
          userId: "anonymous-target",
          reason: "Leadership verified identity offline",
          securityFingerprint: "fixture-checksum",
        }),
      }),
    ),
  );
});
it("requires independent review confirmation and hides recovery of the checker account", async () => {
  vi.stubGlobal("matchMedia", () => ({ matches: false, addListener() {}, removeListener() {} }));
  const request = {
    id: "fixture-request",
    makerId: "maker",
    userId: "anonymous-target",
    reason: "Verified offline",
    requiredApprovals: 1,
    approvals: 0,
  };
  const fetchMock = vi.fn(async () => ({ ok: true, status: 200, json: async () => [request] }));
  vi.stubGlobal("fetch", fetchMock);
  render(
    <AccountRecoveryAdministration
      profile={{ ...profile, userId: "checker", permissions: ["APPROVE", "ROLE_ASSIGN"] }}
      accounts={accounts}
    />,
  );
  fireEvent.click(await screen.findByRole("button", { name: "Review recovery" }));
  expect(screen.getByRole("button", { name: "Confirm recovery approval" })).toBeDisabled();
  fireEvent.click(screen.getByLabelText("I independently verified the holder and recovery reason"));
  fireEvent.click(screen.getByRole("button", { name: "Confirm recovery approval" }));
  await waitFor(() =>
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/account-recovery/fixture-request/decide",
      expect.objectContaining({ method: "POST" }),
    ),
  );
  cleanup();
  render(
    <AccountRecoveryAdministration
      profile={{ ...profile, userId: "anonymous-target", permissions: ["APPROVE", "ROLE_ASSIGN"] }}
      accounts={accounts}
    />,
  );
  await screen.findByText("Verified offline");
  expect(screen.queryByRole("button", { name: "Review recovery" })).toBeNull();
});
