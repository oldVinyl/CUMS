import { expect, it } from "vitest";
import { canSeeRoute } from "./navigation";
import type { Profile } from "./auth-api";
const profile = (permissions: string[]) => ({ userId: "fixture", email: "fixture@invalid.example", permissions }) as Profile;
it("never flashes staff links before authentication", () => {
  for (const route of ["/members", "/investments", "/ledger", "/payments", "/approvals", "/reports", "/accounts", "/audit"]) expect(canSeeRoute(route, null)).toBe(false);
  expect(canSeeRoute("/", null)).toBe(true);
});
it("uses permissions rather than role names and supports the dev account's full permission list", () => {
  const member = profile(["OWN_DATA_READ"]);
  expect(canSeeRoute("/portal", member)).toBe(true);
  expect(canSeeRoute("/members", member)).toBe(false);
  const dev = profile(["MEMBER_READ", "INVESTMENT_READ", "LEDGER_READ", "OWN_DATA_READ", "APPROVE", "REPORT_EXPORT", "RECONCILE", "USER_ADMIN", "ROLE_ASSIGN", "AUDIT_READ", "MEMBER_IMPORT"]);
  for (const route of ["/members", "/investments", "/ledger", "/payments", "/approvals", "/reports", "/reconciliation", "/accounts", "/audit", "/imports"]) expect(canSeeRoute(route, dev)).toBe(true);
});
