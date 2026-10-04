// Production-bundle HEIC acceptance under CSP, with synthetic receipts and intercepted APIs only.
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { preview } from "vite";
import { chromium, expect } from "@playwright/test";
const origin = "http://localhost:5186";
const root = fileURLToPath(new URL("..", import.meta.url));
const fixture = fileURLToPath(new URL("../e2e/fixtures/synthetic-receipt.heic", import.meta.url));
const id = "00000000-0000-4000-8000-000000000001";
let server, browser;
try {
  server = await preview({
    root,
    configFile: false,
    preview: {
      host: "localhost",
      port: 5186,
      strictPort: true,
      headers: {
        "Content-Security-Policy":
          "default-src 'self'; script-src 'self'; worker-src 'self' blob:; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; object-src 'none'; base-uri 'self'",
      },
    },
  });
  browser = await chromium.launch({
    executablePath:
      process.env.CUMS_CHROME_PATH ??
      (process.platform === "win32"
        ? "C:/Program Files/Google/Chrome/Application/chrome.exe"
        : undefined),
    headless: true,
  });
  for (const [width, theme] of [
    [375, "light"],
    [1440, "dark"],
  ]) {
    const context = await browser.newContext({ viewport: { width, height: 900 } });
    const page = await context.newPage();
    await page.addInitScript((value) => localStorage.setItem("theme", value), theme);
    let uploaded = 0,
      submitted = 0;
    const unexpected = [],
      securityFailures = [];
    page.on("console", (message) => {
      if (/Content Security Policy|Refused to/.test(message.text()))
        securityFailures.push(message.text());
    });
    await page.route("**/api/v1/**", async (route) => {
      const request = route.request(),
        path = new URL(request.url()).pathname;
      let body;
      if (request.method() === "GET") {
        body = {
          "/api/v1/auth/me": {
            userId: id,
            email: "fixture@invalid.example",
            permissions: ["OWN_DATA_READ"],
            preferredSignInMethod: "TOTP",
          },
          "/api/v1/payments": { payments: [], page: 0, hasNext: false },
          "/api/v1/payments/methods": [
            { id, name: "Synthetic bank", instructions: "Test account only", active: true },
          ],
          "/api/v1/payments/own-member": { memberId: id },
          "/api/v1/payments/online/config": {
            enabled: false,
            development: false,
            provider: "PAYSTACK",
          },
        }[path];
      } else if (path === "/api/v1/payments/manual-reference")
        body = { id, reference: "SYNTHETIC-001" };
      else if (path === "/api/v1/payments/proofs") {
        const bytes = request.postDataBuffer();
        assert.ok(bytes.includes(Buffer.from('filename="receipt.jpg"')));
        const contentType = bytes.indexOf(Buffer.from("Content-Type: image/jpeg"));
        assert.ok(contentType > 0, "Converted JPEG reaches upload endpoint");
        const start = bytes.indexOf(Buffer.from("\r\n\r\n"), contentType) + 4;
        assert.equal(bytes[start], 255);
        assert.equal(bytes[start + 1], 216);
        uploaded++;
        body = { id };
      } else if (path === "/api/v1/payments") {
        submitted++;
        body = { id };
      }
      if (body === undefined) {
        unexpected.push(`${request.method()} ${path}`);
        body = { message: "Missing synthetic response" };
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(body),
      });
    });
    await page.goto(`${origin}/payments`);
    await page.getByRole("button", { name: "Submit receipt", exact: true }).click();
    const form = page.getByRole("dialog");
    await form.getByLabel("Amount paid (GHS)").fill("25");
    await form.getByRole("combobox", { name: /Payment method/ }).selectOption(id);
    await form.getByRole("button", { name: "Create transfer reference" }).click();
    await expect(form.getByText("Then attach the receipt below.", { exact: false })).toBeVisible();
    await form.getByLabel("Receipt (JPEG, PNG, PDF or HEIC; up to 10 MB)").setInputFiles(fixture);
    await form.getByRole("button", { name: "Submit for verification" }).click();
    await expect(form).toHaveCount(0, { timeout: 25000 });
    assert.equal(uploaded, 1);
    assert.equal(submitted, 1);
    assert.deepEqual(unexpected, []);
    assert.deepEqual(securityFailures, []);
    console.log(
      `PASS synthetic HEIC -> anonymous JPEG upload, strict CSP without unsafe-eval, ${width}px ${theme}`,
    );
    await context.close();
  }
} finally {
  await browser?.close();
  await new Promise((resolve) => (server ? server.httpServer.close(resolve) : resolve()));
}
