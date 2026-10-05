// Real HTTP and browser acceptance, exclusively against the wrapper's fresh synthetic database.
import assert from "node:assert/strict";
import { createHmac, randomBytes, randomUUID } from "node:crypto";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { createServer } from "vite";
import { chromium, expect } from "@playwright/test";
import Decimal from "decimal.js";
import { fileURLToPath } from "node:url";
const database = process.env.CUMS_FINANCIAL_DATABASE;
if (
  !/^cums_financial_\d+$/.test(database ?? "") ||
  process.env.DB_URL !== `jdbc:postgresql://localhost:5432/${database}` ||
  process.env.DEV_SAMPLE_DATA !== "false" ||
  process.env.APP_MAIL_ENABLED !== "false" ||
  process.env.APP_ALLOWED_ORIGIN !== "http://localhost:5185"
) {
  throw new Error("Disposable financial acceptance environment is required.");
}
const origin = "http://localhost:5185";
const today = new Date().toLocaleDateString("en-CA", { timeZone: "Africa/Accra" });
const yearStart = `${Number(today.slice(0, 4)) - 1}-01-01`;
const yesterday = new Date(Date.parse(`${today}T12:00:00Z`) - 86400000).toISOString().slice(0, 10);
const tomorrow = new Date(Date.parse(`${today}T12:00:00Z`) + 86400000).toISOString().slice(0, 10);
const frontendRoot = fileURLToPath(new URL("..", import.meta.url));
const workspaceRoot = fileURLToPath(new URL("../..", import.meta.url));
const password = `Synthetic ${randomBytes(30).toString("base64")}!`;
let server, browser;
function totp(secret) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const character of secret.replace(/=+$/, "").toUpperCase()) {
    const index = alphabet.indexOf(character);
    assert.ok(index >= 0, "Valid Base32 enrollment secret");
    bits += index.toString(2).padStart(5, "0");
  }
  const key = Buffer.from(bits.match(/.{8}/g).map((value) => parseInt(value, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
  const digest = createHmac("sha1", key).update(counter).digest();
  return ((digest.readUInt32BE(digest.at(-1) & 15) & 0x7fffffff) % 1000000)
    .toString()
    .padStart(6, "0");
}
async function call(context, method, path, body, expected, headers = {}) {
  const csrf =
    (await context.cookies(origin)).find((cookie) => cookie.name === "cu_csrf")?.value ?? "";
  const response = await context.request.fetch(`${origin}/api/v1${path}`, {
    method,
    data: body,
    headers: { Origin: origin, "X-CSRF-Token": csrf, ...headers },
  });
  if (expected != null) assert.equal(response.status(), expected, `${method} ${path} status`);
  else if (!response.ok()) {
    const error = await response.json().catch(() => ({}));
    throw new Error(
      `${method} ${path}: ${response.status()} ${error.code ?? ""} ${error.message ?? ""}`,
    );
  }
  return response.status() === 204 ? null : response.json();
}
async function login(context, email, initial) {
  let step = await call(context, "POST", "/auth/login", { email, password: initial });
  if (step.stage === "PASSWORD_CHANGE") {
    step = await call(context, "POST", "/auth/password", {
      challenge: step.challenge,
      newPassword: password,
    });
  }
  assert.equal(step.stage, "TOTP_SETUP");
  const setup = await call(context, "POST", "/auth/totp/setup", { challenge: step.challenge });
  await call(context, "POST", "/auth/totp/confirm", {
    challenge: step.challenge,
    code: totp(setup.secret),
  });
  return call(context, "GET", "/auth/me");
}
async function invite(maker, email, fullName) {
  const invitation = await call(maker, "POST", "/users/invitations/email", { email, fullName });
  const token = new URLSearchParams(new URL(invitation.url).hash.slice(1)).get("token");
  assert.ok(token, "Invitation has a private fragment token");
  const context = await browser.newContext({ baseURL: origin });
  await call(context, "POST", "/auth/invitations/accept", { token, password });
  return { context, profile: await login(context, email, password) };
}
async function approveLedger(context) {
  const page = await context.newPage();
  try {
    await page.goto(`${origin}/approvals`);
    await page.getByRole("button", { name: "Review approval", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Investment impact" })).toBeVisible();
    await expect(
      page.getByRole("button", { name: "Confirm and approve", exact: true }),
    ).toBeEnabled();
    const response = page.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        /\/ledger\/approvals\/.*\/decide$/.test(new URL(response.url()).pathname),
    );
    await page.getByRole("button", { name: "Confirm and approve", exact: true }).click();
    assert.equal((await response).status(), 204);
    await expect(page.getByText("No ledger requests are awaiting approval.")).toBeVisible();
  } finally {
    await page.close();
  }
}
async function historical(maker, checker, memberId, type, amount, mode) {
  const input = {
    memberId,
    type,
    amount,
    effectiveDate: yearStart,
    mode,
    nextCycleDate: mode === "FUTURE_ONLY" ? yearStart : null,
    reason: "Synthetic acceptance correction",
  };
  const preview = await call(maker, "POST", "/ledger/contributions/preview", input);
  assert.equal(preview.repostAllowed, true);
  const request = await call(maker, "POST", "/ledger/contributions/historical", {
    ...input,
    sourceFingerprint: preview.sourceFingerprint,
  });
  await call(
    maker,
    "POST",
    `/ledger/approvals/${request.approvalRequestId}/decide`,
    { decision: "APPROVE" },
    400,
  );
  await approveLedger(checker);
  return preview;
}
try {
  server = await createServer({
    root: frontendRoot,
    configFile: false,
    plugins: [(await import("@vitejs/plugin-react")).default()],
    optimizeDeps: { include: ["heic-to/csp"] },
    server: {
      host: "localhost",
      watch: { ignored: ["**/artifacts/**"] },
      port: 5185,
      strictPort: true,
      proxy: { "/api": "http://localhost:8085" },
    },
  });
  await server.listen();
  browser = await chromium.launch({
    executablePath:
      process.env.CUMS_CHROME_PATH ?? "C:/Program Files/Google/Chrome/Application/chrome.exe",
    headless: true,
  });
  const maker = await browser.newContext({ baseURL: origin });
  const makerProfile = await login(
    maker,
    process.env.DEV_BOOTSTRAP_EMAIL,
    process.env.DEV_BOOTSTRAP_PASSWORD,
  );
  assert.ok(makerProfile.permissions.includes("CYCLE_REPOST"));
  const { context: checker, profile: checkerProfile } = await invite(
    maker,
    "checker@invalid.example",
    "Anonymous checker",
  );
  assert.match(checkerProfile.userId, /^[0-9a-f-]{36}$/);
  // Test role fixture only; all financial writes below use authenticated application endpoints.
  execFileSync(
    "docker",
    [
      "compose",
      "exec",
      "-T",
      "postgres",
      "psql",
      "-U",
      "cums",
      "-d",
      database,
      "-v",
      "ON_ERROR_STOP=1",
      "-c",
      `INSERT INTO user_role_assignments(user_id,role_id,start_date) SELECT '${checkerProfile.userId}'::uuid,id,current_date FROM roles WHERE code='TREASURER'`,
    ],
    { cwd: workspaceRoot, stdio: "pipe" },
  );
  const memberIds = [];
  const period = await call(
    maker,
    "POST",
    `/ledger/fiscal-years/${Number(today.slice(0, 4)) - 1}/create`,
    { reason: "Synthetic historical baseline acceptance" },
  );
  await call(checker, "POST", `/ledger/approvals/${period.approvalRequestId}/decide`, {
    decision: "APPROVE",
  });
  for (const letter of ["A", "B"]) {
    const created = await call(maker, "POST", "/members", {
      fullName: `Anonymous member ${letter}`,
      email: `member-${letter.toLowerCase()}@invalid.example`,
      joinDate: yearStart,
    });
    memberIds.push(created.id);
    await historical(maker, checker, created.id, "SEED", "100", "FUTURE_ONLY");
  }
  console.log(
    "PASS real authenticator enrollment, member creation and independent historical seed approval",
  );
  let cycle = await call(maker, "POST", "/investments", {
    tenor: "CUSTOM",
    customDays: 1,
    institution: "Synthetic institution",
    referenceNumber: "ACCEPTANCE-001",
    purchaseDate: today,
    maturityDate: tomorrow,
    cost: "200",
    maturityValue: "220",
  });
  const request = await call(maker, "POST", `/investments/${cycle.id}/submit`);
  await call(
    maker,
    "POST",
    `/investments/approvals/${request.approvalRequestId}/decide`,
    { decision: "APPROVE" },
    400,
  );
  await call(checker, "POST", `/investments/approvals/${request.approvalRequestId}/decide`, {
    decision: "APPROVE",
  });
  let detail = await call(maker, "GET", `/investments/${cycle.id}`);
  assert.equal(detail.investment.status, "POSTED");
  assert.equal(detail.allocations.length, 2);
  // Date fixture only, guarded by the disposable database prefix above. Simulate the day
  // passing without changing the host clock or weakening production purchase/maturity rules.
  assert.match(cycle.id, /^[0-9a-f-]{36}$/);
  execFileSync(
    "docker",
    [
      "compose",
      "exec",
      "-T",
      "postgres",
      "psql",
      "-U",
      "cums",
      "-d",
      database,
      "-v",
      "ON_ERROR_STOP=1",
      "-c",
      `UPDATE investments SET purchase_date='${yesterday}'::date,maturity_date='${today}'::date WHERE id='${cycle.id}'::uuid AND status='POSTED'`,
    ],
    { cwd: workspaceRoot, stdio: "pipe" },
  );
  const maturityPreview = await call(
    maker,
    "GET",
    `/investments/${cycle.id}/maturity-preview?actualProceeds=220`,
  );
  assert.ok(new Decimal(maturityPreview.totalIncome).equals("20"));
  const maturity = await call(maker, "POST", `/investments/${cycle.id}/mature`, {
    actualProceeds: "220",
  });
  await call(
    maker,
    "POST",
    `/investments/approvals/${maturity.approvalRequestId}/decide`,
    { decision: "APPROVE" },
    400,
  );
  await call(checker, "POST", `/investments/approvals/${maturity.approvalRequestId}/decide`, {
    decision: "APPROVE",
  });
  detail = await call(maker, "GET", `/investments/${cycle.id}`);
  assert.equal(detail.investment.status, "MATURED");
  assert.ok(new Decimal(detail.investment.actualProceeds).equals("220"));
  const sourceId = cycle.id;
  cycle = await call(maker, "POST", `/investments/${sourceId}/rollover-draft`, {
    policy: "ROLL_PRINCIPAL_ONLY",
    draft: {
      tenor: "CUSTOM",
      customDays: 1,
      institution: "Synthetic institution",
      referenceNumber: "ACCEPTANCE-ROLLOVER-001",
      purchaseDate: today,
      maturityDate: tomorrow,
      cost: "200",
      maturityValue: "220",
    },
  });
  detail = await call(maker, "GET", `/investments/${cycle.id}`);
  assert.ok(new Decimal(detail.pool).equals("200"));
  const rollover = await call(maker, "POST", `/investments/${cycle.id}/submit`);
  await call(checker, "POST", `/investments/approvals/${rollover.approvalRequestId}/decide`, {
    decision: "APPROVE",
  });
  assert.equal(
    (await call(maker, "GET", `/investments/${sourceId}`)).investment.status,
    "ROLLED_OVER",
  );
  console.log(
    "PASS real maturity preview/independent approval and principal-only rollover creation/approval (synthetic maturity-date fixture)",
  );
  const preview = await historical(maker, checker, memberIds[0], "ADDITION", "100", "REPOST");
  assert.equal(preview.affectedCycles.length, 2);
  detail = await call(maker, "GET", `/investments/${cycle.id}`);
  assert.equal(detail.investment.revision, 2);
  assert.equal(detail.investment.status, "POSTED");
  const shares = new Map(detail.allocations.map((row) => [row.memberId, row]));
  assert.ok(new Decimal(shares.get(memberIds[0]).costShare).equals("133.33"));
  assert.ok(new Decimal(shares.get(memberIds[1]).costShare).equals("66.67"));
  assert.ok(detail.history.length >= 1);
  const revisedSource = await call(maker, "GET", `/investments/${sourceId}`);
  const actualShares = new Map(revisedSource.maturityAllocations.map(row => [row.memberId, row]));
  assert.ok(new Decimal(actualShares.get(memberIds[0]).actualIncomeShare).equals("13.34"));
  assert.ok(new Decimal(actualShares.get(memberIds[1]).actualIncomeShare).equals("6.66"));
  const dashboard = await call(maker, "GET", `/dashboard?asAt=${today}`);
  assert.ok(new Decimal(dashboard.totals.total.equity).equals("320"));
  console.log(
    "PASS posted investment, maker rejection, exact reverse/repost shares and ledger-derived equity",
  );
  const { context: member, profile: memberProfile } = await invite(
    maker,
    "member-a@invalid.example",
    "Anonymous member A",
  );
  const portal = await call(member, "GET", `/portal?asAt=${today}`);
  assert.ok(JSON.stringify(portal).includes(memberIds[0]));
  await call(member, "GET", `/ledger?from=${yearStart}&to=${today}`, undefined, 403);
  await call(member, "GET", `/members/${memberIds[1]}`, undefined, 403);
  const exported = await call(member, "GET", "/portal/export.json");
  assert.ok(!JSON.stringify(exported).includes(memberIds[1]));
  const method = await call(maker, "POST", "/payments/methods", {
    name: "Synthetic bank",
    instructions: "Test account only",
    active: true,
  });
  const page = await member.newPage();
  await page.setViewportSize({ width: 375, height: 850 });
  const receipt = readFileSync(new URL("../e2e/fixtures/synthetic-receipt.heic", import.meta.url));
  await page.goto(`${origin}/payments`);
  await page.getByRole("button", { name: "Submit receipt", exact: true }).click();
  const form = page.getByRole("dialog");
  await form.getByLabel("Amount paid (GHS)", { exact: true }).fill("25");
  await form.getByRole("combobox", { name: /Payment method/ }).selectOption(method.id);
  await form.getByRole("button", { name: "Create transfer reference" }).click();
  await expect(form.getByText("Then attach the receipt below.", { exact: false })).toBeVisible();
  await form
    .getByLabel("Receipt (JPEG, PNG, PDF or HEIC; up to 10 MB)")
    .setInputFiles({ name: "synthetic.heic", mimeType: "image/heic", buffer: receipt });
  const submitted = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      new URL(response.url()).pathname === "/api/v1/payments",
  );
  await form.getByRole("button", { name: "Submit for verification" }).click();
  const payment = await (await submitted).json();
  await expect(form).toHaveCount(0);
  const originalReceipt = (await call(member, "GET", "/payments")).payments.find(row => row.id === payment.id).proofId;
  await call(maker, "POST", `/payments/${payment.id}/review`, { action: "MORE_INFO", reason: "Upload a clearer synthetic receipt" });
  await page.reload(); await page.getByRole("button", { name: "Replace receipt", exact: true }).click();
  const amendment = page.getByRole("dialog");
  await amendment.getByLabel(/Replacement receipt/).setInputFiles({ name: "replacement.png", mimeType: "image/png", buffer: readFileSync(new URL("../e2e/fixtures/synthetic-receipt.png", import.meta.url)) });
  await amendment.getByRole("button", { name: "Replace and return for verification" }).click();
  await expect(amendment).toHaveCount(0);
  const receiptHistory = await call(member, "GET", `/payments/${payment.id}/receipt-revisions`);
  assert.equal(receiptHistory.length, 1); assert.equal(receiptHistory[0].previousProofId, originalReceipt);
  assert.notEqual(receiptHistory[0].newProofId, originalReceipt);
  const retained = await member.request.get(`/api/v1/payments/proofs/${originalReceipt}`);
  assert.equal(retained.status(), 200); assert.equal((await retained.body()).subarray(0, 3).toString("hex"), "ffd8ff");
  await call(member, "POST", `/payments/${payment.id}/receipt`, { previousProofId: originalReceipt, newProofId: originalReceipt }, 400);
  console.log("PASS actual mobile receipt replacement, retained original proof and owned revision history");
  await call(maker, "POST", `/payments/${payment.id}/verify`, {
    amount: "25",
    effectiveDate: today,
  });
  await call(maker, "POST", `/payments/${payment.id}/decide`, { decision: "APPROVE" }, 400);
  await call(checker, "POST", `/payments/${payment.id}/decide`, { decision: "APPROVE" });
  await call(checker, "POST", `/payments/${payment.id}/decide`, { decision: "APPROVE" }, 409);
  await call(member, "POST", `/payments/${payment.id}/receipt`, { previousProofId: originalReceipt, newProofId: receiptHistory[0].newProofId }, 409);
  const audit = await call(maker, "GET", `/audit?from=${today}&to=${today}&entityId=${payment.id}`);
  const postedChange = audit.events.find(event => event.action === "FINANCIAL_PAYMENT_UPDATE" && event.financialChange?.after?.status === "POSTED");
  assert.equal(postedChange.actorId, checkerProfile.userId);
  assert.equal(postedChange.financialChange.before.status, "VERIFIED_PENDING_APPROVAL");
  assert.equal(postedChange.financialChange.after.verified_amount, "25.00");
  assert.ok(!JSON.stringify(postedChange.financialChange).includes("internal_reference"));
  await call(member, "GET", `/audit?from=${today}&to=${today}`, undefined, 403);
  console.log("PASS actual audited payment before/after values, verified checker attribution and member denial");
  let own = await call(member, "GET", `/portal?asAt=${today}`);
  assert.ok(new Decimal(own.total.equity).equals("238.34"));
  await page.reload();
  await expect(page.getByText("posted", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Replace receipt", exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "Receipt history", exact: true }).click();
  const postedHistory = page.getByRole("dialog");
  await expect(postedHistory.getByRole("link", { name: "Download earlier receipt 1" })).toBeVisible();
  await expect(postedHistory.getByLabel(/Replacement receipt/)).toHaveCount(0);
  await page.keyboard.press("Escape"); await expect(postedHistory).toHaveCount(0);
  await page.close();
  const onlineKey = { "Idempotency-Key": randomUUID() };
  const online = await call(
    member,
    "POST",
    "/payments/online",
    { memberId: memberIds[0], amount: "10", purpose: "ADDITION", method: "MOBILE_MONEY" },
    undefined,
    onlineKey,
  );
  assert.equal(online.provider, "FAKE");
  await call(member, "POST", `/payments/${online.id}/simulate`);
  await call(member, "POST", `/payments/${online.id}/simulate`);
  own = await call(member, "GET", `/portal?asAt=${today}`);
  assert.ok(new Decimal(own.total.equity).equals("248.34"));
  console.log(
    "PASS mobile receipt upload, independent verification/approval, replay rejection and exact-once FakeProvider posting",
  );
  const lossCycle = await call(maker, "POST", "/investments", {
    tenor: "CUSTOM", customDays: 1, institution: "Synthetic loss fixture",
    referenceNumber: "ACCEPTANCE-LOSS-001", purchaseDate: today, maturityDate: tomorrow,
    cost: "50", maturityValue: "55",
  });
  const lossPost = await call(maker, "POST", `/investments/${lossCycle.id}/submit`);
  await call(checker, "POST", `/investments/approvals/${lossPost.approvalRequestId}/decide`, { decision: "APPROVE" });
  assert.match(lossCycle.id, /^[0-9a-f-]{36}$/);
  execFileSync("docker", ["compose", "exec", "-T", "postgres", "psql", "-U", "cums", "-d", database, "-v", "ON_ERROR_STOP=1", "-c",
    `UPDATE investments SET purchase_date='${yesterday}'::date,maturity_date='${today}'::date WHERE id='${lossCycle.id}'::uuid AND status='POSTED'`], { cwd: workspaceRoot, stdio: "pipe" });
  const lossPreview = await call(maker, "GET", `/investments/${lossCycle.id}/maturity-preview?actualProceeds=40`);
  assert.ok(new Decimal(lossPreview.totalIncome).equals("-10"));
  const lossRequest = await call(maker, "POST", `/investments/${lossCycle.id}/mature`, { actualProceeds: "40" });
  await call(checker, "POST", `/investments/approvals/${lossRequest.approvalRequestId}/decide`, { decision: "APPROVE" });
  const lossDetail = await call(maker, "GET", `/investments/${lossCycle.id}`);
  assert.equal(lossDetail.investment.status, "MATURED");
  assert.ok(lossDetail.maturityAllocations.reduce((sum, row) => sum.plus(row.actualIncomeShare), new Decimal(0)).equals("-10"));
  assert.ok(new Decimal((await call(maker, "GET", `/dashboard?asAt=${today}`)).totals.total.equity).equals("345"));
  console.log("PASS profitable maturity/repost exact 13.34/6.66 income and separately approved investment loss (-10)");
  for (const [context, route, heading] of [
    [maker, "/", "Overview"],
    [member, "/portal", "My membership"],
  ]) {
    const page = await context.newPage();
    await page.goto(`${origin}${route}`);
    await expect(page.getByRole("heading", { level: 1, name: heading, exact: true })).toBeVisible();
    await expect(page.getByText("Could not load", { exact: false })).toHaveCount(0);
    await page.close();
  }
  console.log(
    "PASS actual overview/member pages, own-data export and cross-member permission boundaries",
  );
  const granted = await call(maker, "POST", "/users/roles", {
    operation: "ASSIGN",
    userId: memberProfile.userId,
    roleCode: "TREASURER",
    reason: "Synthetic temporary role acceptance",
  });
  await call(checker, "POST", `/users/roles/${granted.approvalRequestId}/decide`, {
    decision: "APPROVE",
  });
  assert.ok((await call(member, "GET", "/auth/me")).permissions.includes("REPORT_EXPORT"));
  const rolePage = await member.newPage();
  await rolePage.goto(`${origin}/reports`);
  await expect(
    rolePage.getByRole("heading", { level: 1, name: "Reports", exact: true }),
  ).toBeVisible();
  const accounts = await call(maker, "POST", "/users/search", {search:"member-a@invalid.example"});
  const assignment = accounts.users
    .find((account) => account.id === memberProfile.userId)
    .roles.find((role) => role.code === "TREASURER");
  assert.ok(assignment);
  const revoked = await call(maker, "POST", "/users/roles", {
    operation: "REVOKE",
    assignmentId: assignment.id,
    reason: "Synthetic role removal acceptance",
  });
  await call(checker, "POST", `/users/roles/${revoked.approvalRequestId}/decide`, {
    decision: "APPROVE",
  });
  assert.ok(!(await call(member, "GET", "/auth/me")).permissions.includes("REPORT_EXPORT"));
  await call(member, "GET", `/dashboard?asAt=${today}`, undefined, 403);
  await rolePage.reload();
  await expect(
    rolePage.getByRole("heading", { level: 1, name: "Access required", exact: true }),
  ).toBeVisible();
  await expect(rolePage.getByRole("link", { name: "Reports", exact: true })).toHaveCount(0);
  await rolePage.close();
  console.log(
    "PASS independently approved role grant/revocation, immediate API denial and refreshed route/menu restriction",
  );
} finally {
  await browser?.close();
  await server?.close();
}
