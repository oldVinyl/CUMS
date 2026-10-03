import type { Profile } from "./auth-api";

const routePermissions: Record<string, string[]> = {
  "/members": ["MEMBER_READ"], "/investments": ["INVESTMENT_READ"], "/ledger": ["LEDGER_READ"],
  "/payments": ["OWN_DATA_READ", "PAYMENT_VERIFY", "APPROVE"], "/portal": ["OWN_DATA_READ"],
  "/approvals": ["APPROVE", "LEDGER_POST", "LEDGER_REVERSE", "FISCAL_YEAR_CLOSE", "INVESTMENT_WRITE", "ROLE_ASSIGN", "PAYMENT_VERIFY", "RECONCILE", "MEMBER_IMPORT"],
  "/reports": ["REPORT_EXPORT"], "/reconciliation": ["RECONCILE", "APPROVE", "AUDIT_READ"],
  "/accounts": ["USER_ADMIN", "ROLE_ASSIGN", "APPROVE"], "/audit": ["AUDIT_READ"],
  "/imports": ["MEMBER_IMPORT", "APPROVE", "AUDIT_READ"],
};
export function canSeeRoute(path: string, profile: Profile | null) {
  if (path === "/" || path === "/settings") return true;
  const required = routePermissions[path];
  return !!profile && !!required && required.some(permission => profile.permissions.includes(permission));
}
