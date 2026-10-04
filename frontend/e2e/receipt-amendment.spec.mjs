import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { readFileSync } from "node:fs";

test("member receipt replacement sheet/drawer retains evidence and is accessible", async ({
  page,
}) => {
  const today = new Date().toISOString().slice(0, 10),
    unexpected = [];
  let submitted;
  const payment = {
    id: "anonymous-payment",
    memberId: "anonymous-member",
    memberNumber: "FIX-001",
    fullName: "Anonymous fixture",
    amount: "25",
    verifiedAmount: null,
    purpose: "ADDITION",
    method: "Synthetic bank",
    reference: "CU-FIXTURE",
    status: "UNDER_REVIEW",
    paidDate: today,
    effectiveDate: null,
    proofId: "old-proof",
    reason: "Please upload a clearer receipt",
    approvalRequestId: null,
    makerId: null,
    approvals: 0,
    requiredApprovals: 1,
    duplicate: false,
    provider: null,
    checkoutUrl: null,
    feeAmount: "0",
  };
  const responses = {
    "/api/v1/auth/me": {
      userId: "anonymous-owner",
      email: "owner@example.invalid",
      permissions: ["OWN_DATA_READ"],
      preferredSignInMethod: "TOTP",
    },
    "/api/v1/payments": { payments: [payment], page: 0, hasNext: false },
    "/api/v1/payments/methods": [],
    "/api/v1/payments/own-member": { memberId: "anonymous-member" },
    "/api/v1/payments/online/config": { provider: "NONE", enabled: false, development: false },
    "/api/v1/payments/anonymous-payment/receipt-revisions": [
      {
        id: "earlier-revision",
        previousProofId: "original-proof",
        newProofId: "old-proof",
        amendedAt: `${today}T10:00:00Z`,
      },
    ],
  };
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request(),
      path = new URL(request.url()).pathname;
    let body = responses[path],
      status = 200;
    if (request.method() === "POST" && path === "/api/v1/payments/proofs")
      body = { id: "new-proof" };
    else if (request.method() === "POST" && path === "/api/v1/payments/anonymous-payment/receipt") {
      submitted = request.postDataJSON();
      payment.status = "SUBMITTED";
      payment.proofId = "new-proof";
      payment.reason = null;
      status = 204;
    } else if (request.method() !== "GET" || body === undefined) {
      unexpected.push(`${request.method()} ${path}`);
      status = 404;
      body = { message: "Unexpected isolated request" };
    }
    await route.fulfill({
      status,
      contentType: "application/json",
      ...(status === 204 ? {} : { body: JSON.stringify(body) }),
    });
  });
  await page.goto("/payments");
  await page.getByRole("button", { name: "Replace receipt", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("link", { name: "Download earlier receipt 1" })).toBeVisible();
  await dialog
    .getByLabel(/Replacement receipt/)
    .setInputFiles({
      name: "synthetic.png",
      mimeType: "image/png",
      buffer: readFileSync(new URL("./fixtures/synthetic-receipt.png", import.meta.url)),
    });
  const result = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();
  expect(
    result.violations.map(({ id, nodes }) => ({ id, nodes: nodes.map((node) => node.target) })),
  ).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await dialog.getByRole("button", { name: "Replace and return for verification" }).click();
  await expect(dialog).toHaveCount(0);
  expect(submitted).toEqual({ previousProofId: "old-proof", newProofId: "new-proof" });
  await page.getByRole("button", { name: "Receipt history", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Payment receipt history" })).toBeVisible();
  await expect(page.getByLabel(/Replacement receipt/)).toHaveCount(0);
  const historyCheck = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  expect(historyCheck.violations).toEqual([]);
  await page.keyboard.press("Escape");
  expect(unexpected).toEqual([]);
});
