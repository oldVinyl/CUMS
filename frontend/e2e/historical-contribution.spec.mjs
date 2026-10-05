import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
const today = new Date().toISOString().slice(0, 10);
test("historical contribution preview, review and stale-input reset are accessible", async ({
  page,
}) => {
  const unexpected = [];
  let submitted;
  const member = { id: "anonymous-member", fullName: "Anonymous fixture", memberNumber: "FIX-001" };
  const impact = {
    sourceFingerprint: "fixture-checksum",
    earliestNextCycleDate: `${today.slice(0, 4)}-01-01`,
    repostAllowed: true,
    restriction: null,
    affectedCycles: [
      {
        id: "fixture-cycle",
        revision: 1,
        status: "MATURED",
        purchaseDate: `${today.slice(0, 4)}-01-03`,
        maturityDate: `${today.slice(0, 4)}-02-03`,
        shares: [
          {
            memberId: member.id,
            memberNumber: member.memberNumber,
            oldParticipation: "100",
            participation: "200",
            costShare: "133.33",
            expectedIncome: "13.34",
            oldActualIncome: "10",
            actualIncome: "13.34",
          },
        ],
      },
    ],
  };
  const responses = {
    "/api/v1/auth/me": {
      userId: "anonymous-maker",
      email: "maker@example.invalid",
      permissions: [
        "LEDGER_READ",
        "LEDGER_POST",
        "LEDGER_BACKDATE",
        "CYCLE_REPOST",
        "INVESTMENT_WRITE",
        "MEMBER_READ",
      ],
      preferredSignInMethod: "TOTP",
    },
    "/api/v1/ledger": { entries: [], page: 0, hasNext: false },
    "/api/v1/ledger/approvals": [],
    "/api/v1/ledger/fiscal-years": [],
    "/api/v1/members": { items: [member], page: 0, size: 25, hasNext: false },
  };
  await page.route("**/api/v1/**", async (route) => {
  if (new URL(route.request().url()).pathname === '/api/v1/organization/branding') {
    await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({organisationName:'Harbour Credit Union',primaryColour:'#083a4f',currencyDisplay:'SYMBOL',logoDataUrl:'',passwordMinLength:12})}); return;
  }
    const request = route.request(),
      path = new URL(request.url()).pathname;
    let body = responses[path];
    if (path === "/api/v1/members/search" && request.method() === "POST") body = responses["/api/v1/members"];
    if (request.method() === "POST" && path === "/api/v1/ledger/contributions/preview")
      body = impact;
    else if (request.method() === "POST" && path === "/api/v1/ledger/contributions/historical") {
      submitted = request.postDataJSON();
      body = { approvalRequestId: "anonymous-request" };
    } else if ((request.method() !== "GET" && path !== "/api/v1/members/search") || body === undefined) {
      unexpected.push(`${request.method()} ${path}`);
      body = { message: "Unexpected isolated request" };
    }
    await route.fulfill({
      status: body.message ? 404 : 200,
      contentType: "application/json",
      body: JSON.stringify(body),
    });
  });
  await page.goto("/ledger");
  await page.getByRole("button", { name: "Add contribution", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await dialog.getByLabel("Use a historical date").check();
  await dialog.getByRole("combobox", { name: "Find member" }).fill("FIX");
  await dialog.getByRole("option", { name: "Anonymous fixture · FIX-001" }).click();
  await dialog.getByLabel("Amount (GH₵)").fill("100");
  await dialog.getByLabel("Original effective date").fill(`${today.slice(0, 4)}-01-01`);
  await dialog.getByLabel("Correction reason").fill("Omitted fixture contribution");
  await dialog.getByRole("button", { name: "Preview historical impact" }).click();
  await expect(dialog.getByRole("heading", { name: "Investment impact" })).toBeVisible();
  await expect(
    dialog.getByRole("button", { name: "Request contribution approval" }),
  ).toBeDisabled();
  await dialog.getByText(/revision 1/).click();
  await expect(dialog.getByText("Member FIX-001")).toBeVisible();
  await dialog.getByLabel("Handling of existing cycles").selectOption("REPOST");
  const result = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();
  expect(
    result.violations.map(({ id, nodes }) => ({ id, nodes: nodes.map((node) => node.target) })),
  ).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await dialog.getByLabel("Amount (GH₵)").fill("125");
  await expect(dialog.getByRole("heading", { name: "Investment impact" })).toHaveCount(0);
  await dialog.getByRole("button", { name: "Preview historical impact" }).click();
  await dialog.getByLabel("I reviewed the dates, affected cycles and financial impact").check();
  await dialog.getByRole("button", { name: "Request contribution approval" }).click();
  await expect(dialog).toHaveCount(0);
  expect(submitted).toMatchObject({
    memberId: member.id,
    amount: "125",
    mode: "REPOST",
    sourceFingerprint: "fixture-checksum",
  });
  expect(unexpected).toEqual([]);
});
