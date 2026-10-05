import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
async function accessible(page) {
  const result = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();
  expect(
    result.violations.map(({ id, nodes }) => ({ id, nodes: nodes.map((node) => node.target) })),
  ).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
}
test("public password recovery clears the secret fragment and has accessible matching-password controls", async ({
  page,
}) => {
  let submitted;
  const unexpected = [];
  await page.route("**/api/v1/**", async (route) => {
  if (new URL(route.request().url()).pathname === '/api/v1/organization/branding') {
    await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({organisationName:'Harbour Credit Union',primaryColour:'#083a4f',currencyDisplay:'SYMBOL',logoDataUrl:'',passwordMinLength:12})}); return;
  }
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/v1/auth/password-reset/complete") {
      submitted = route.request().postDataJSON();
      await route.fulfill({ status: 204 });
    } else {
      unexpected.push(path);
      await route.fulfill({ status: 404 });
    }
  });
  await page.goto("/reset-password#token=anonymous-one-time-link");
  await expect(page.getByRole("heading", { name: "Set a new password" })).toBeVisible();
  await expect(page).toHaveURL(/\/reset-password$/);
  await page
    .getByLabel("New password", { exact: true })
    .fill("Synthetic replacement password! 2026");
  await expect(page.getByRole("button", { name: "Set password" })).toBeDisabled();
  await page.getByLabel("Confirm new password").fill("Synthetic replacement password! 2026");
  await accessible(page);
  await page.getByRole("button", { name: "Set password" }).click();
  await expect(page.getByText("Recovery request complete")).toBeVisible();
  expect(submitted).toMatchObject({
    token: "anonymous-one-time-link",
    password: "Synthetic replacement password! 2026",
  });
  expect(unexpected).toEqual([]);
});
test("independent account recovery review requires confirmation in the responsive overlay", async ({
  page,
}) => {
  const unexpected = [];
  let decision;
  const request = {
    id: "anonymous-request",
    makerId: "anonymous-maker",
    userId: "anonymous-target",
    reason: "Synthetic offline verification",
    requiredApprovals: 1,
    approvals: 0,
  };
  const responses = {
    "/api/v1/auth/me": {
      userId: "anonymous-checker",
      email: "checker@example.invalid",
      permissions: ["APPROVE", "ROLE_ASSIGN"],
      preferredSignInMethod: "TOTP",
    },
    "/api/v1/users": {
      users: [
        {
          id: "anonymous-target",
          email: "target@example.invalid",
          status: "ACTIVE",
          memberNumber: null,
          roles: [],
        },
      ],
      hasNext: false,
    },
    "/api/v1/users/roles/pending": [],
  };
  await page.route("**/api/v1/**", async (route) => {
  if (new URL(route.request().url()).pathname === '/api/v1/organization/branding') {
    await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({organisationName:'Harbour Credit Union',primaryColour:'#083a4f',currencyDisplay:'SYMBOL',logoDataUrl:'',passwordMinLength:12})}); return;
  }
    const path = new URL(route.request().url()).pathname;
    let body = responses[path];
    if (path === "/api/v1/users/search" && route.request().method() === "POST") body = responses["/api/v1/users"];
    if (path === "/api/v1/account-recovery") body = decision ? [] : [request];
    else if (path === "/api/v1/account-recovery/anonymous-request/decide") {
      decision = route.request().postDataJSON();
      await route.fulfill({ status: 204 });
      return;
    }
    if (body === undefined) {
      unexpected.push(path);
      body = { message: "Unexpected isolated request" };
    }
    await route.fulfill({
      status: body.message ? 404 : 200,
      contentType: "application/json",
      body: JSON.stringify(body),
    });
  });
  await page.goto("/accounts");
  await page.getByRole("button", { name: "Review recovery" }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("button", { name: "Confirm recovery approval" })).toBeDisabled();
  await dialog.getByLabel("I independently verified the holder and recovery reason").check();
  await accessible(page);
  await dialog.getByRole("button", { name: "Confirm recovery approval" }).click();
  await expect(dialog).toHaveCount(0);
  expect(decision).toMatchObject({ decision: "APPROVE" });
  expect(unexpected).toEqual([]);
});
