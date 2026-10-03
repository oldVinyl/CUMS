// Isolated visual fixtures; never submits mutations to the running financial API.
import { mkdir, writeFile } from "node:fs/promises";

const tabs = await fetch("http://127.0.0.1:9223/json/list").then(response => response.json());
const ws = new WebSocket(tabs.find(tab => tab.type === "page").webSocketDebuggerUrl);
await new Promise(resolve => ws.addEventListener("open", resolve, { once: true }));
let nextId = 1;
const calls = new Map();
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const id = nextId++; calls.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params }));
});
const today = new Date().toLocaleDateString("en-CA", { timeZone: "Africa/Accra" });
const member = { id: "00000000-0000-4000-8000-000000000001", memberNumber: "DEMO-001", fullName: "Ama Demonstration Member", status: "ACTIVE" };
const permissions = ["DASHBOARD_READ_ALL", "MEMBER_READ", "MEMBER_WRITE", "LEDGER_READ", "LEDGER_POST", "LEDGER_REVERSE", "FISCAL_YEAR_CLOSE", "APPROVE", "INVESTMENT_READ", "REPORT_EXPORT", "SETTINGS_WRITE"];
const pending = [{ id: "request-fixture", makerId: "another-maker", actionType: "LEDGER_POST", payload: { amount: "125.50", type: "SEED" },
  memberNumber: member.memberNumber, fullName: member.fullName, originalAmount: null, requiredApprovals: 1, approvals: 0 }];
const entries = [{ id: "entry-1", memberId: member.id, memberNumber: member.memberNumber, fullName: member.fullName, type: "ADDITION", component: "PRINCIPAL", amount: "125.50",
  effectiveDate: today, reference: "APR-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", narration: "Fictitious development contribution", reversalOf: null, reversed: false, canReverse: true },
{ id: "entry-2", memberId: member.id, memberNumber: member.memberNumber, fullName: member.fullName, type: "INCOME_ALLOCATION", component: "INCOME", amount: "-5.00",
  effectiveDate: today, reference: "INV-MAT-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee-ffffffff-1111-2222-3333-444444444444", narration: "Fictitious investment loss", reversalOf: null, reversed: false, canReverse: false }];
ws.addEventListener("message", async event => {
  try {
  const message = JSON.parse(event.data);
  if (message.id) {
    const call = calls.get(message.id); calls.delete(message.id);
    if (message.error) call.reject(new Error(message.error.message)); else call.resolve(message.result);
  } else if (message.method === "Fetch.requestPaused") {
    const { requestId, request } = message.params;
    const path = new URL(request.url).pathname;
    let body;
    if (request.method !== "GET") throw new Error("Visual smoke must never submit a financial mutation");
    if (path === "/api/v1/auth/me") body = { userId: "visual-checker", email: "visual@invalid.example", preferredSignInMethod: "TOTP", permissions };
    else if (path === "/api/v1/members") body = { items: [member], page: 0, size: 25, hasNext: false };
    else if (path === "/api/v1/ledger") body = { entries, page: 0, hasNext: false };
    else if (path === "/api/v1/ledger/approvals") body = pending;
    else if (path === "/api/v1/ledger/fiscal-years") body = [{ year: Number(today.slice(0, 4)), status: "OPEN" }, { year: Number(today.slice(0, 4)) - 1, status: "OPEN" }];
    else if (path === "/api/v1/settings/rollover") body = { defaultRolloverPolicy: "ROLL_PRINCIPAL_AND_INCOME" };
    if (body !== undefined) await send("Fetch.fulfillRequest", { requestId, responseCode: 200, responseHeaders: [{ name: "Content-Type", value: "application/json" }], body: Buffer.from(JSON.stringify(body)).toString("base64") });
    else await send("Fetch.fulfillRequest", { requestId, responseCode: 404, responseHeaders: [{ name: "Content-Type", value: "application/json" }], body: Buffer.from('{"message":"No visual fixture for this request"}').toString("base64") });
  }
  } catch (error) { if (!error.message.includes("Invalid InterceptionId")) throw error; }
});
const evaluate = async expression => (await send("Runtime.evaluate", { expression, returnByValue: true })).result.value;
const waitFor = async expression => {
  for (let attempt = 0; attempt < 70; attempt++) {
    if (await evaluate(expression)) return;
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error(`UI did not become ready: ${expression}`);
};
await send("Page.enable");
await send("Fetch.enable", { patterns: [{ urlPattern: "*/api/v1/*" }] });
await mkdir("artifacts/ui-check/financial", { recursive: true });
try {
  for (const width of [1440, 390]) for (const theme of ["light", "dark"]) for (const route of ["/reports", "/ledger", "/approvals"]) {
    await send("Emulation.setDeviceMetricsOverride", { width, height: 1000, deviceScaleFactor: 1, mobile: width < 600 });
    await send("Page.navigate", { url: `http://localhost:5173${route}` });
    await waitFor(`document.querySelector('h1')?.textContent === '${route.slice(1, 2).toUpperCase() + route.slice(2)}'`);
    if (route === "/reports") {
      await waitFor("!!document.querySelector('select option[value=\"00000000-0000-4000-8000-000000000001\"]')");
      await evaluate(`const select=document.querySelector('select');select.value='${member.id}';select.dispatchEvent(new Event('change',{bubbles:true}));`);
      await waitFor("document.body.textContent.includes('Member statement')");
    }
    if (route === "/ledger") {
      await waitFor("!!document.querySelector('.ledger-entry')");
      await evaluate("[...document.querySelectorAll('button')].find(button=>button.textContent.includes('Add contribution')).click()");
      await waitFor("!!document.querySelector('select option[value=\"00000000-0000-4000-8000-000000000001\"]')");
    }
    if (route === "/approvals") {
      await waitFor("!!document.querySelector('.ledger-entry')");
      await evaluate("[...document.querySelectorAll('button')].find(button=>button.textContent==='Review approval').click()");
      await waitFor("document.body.textContent.includes('Confirm and approve')");
    }
    await evaluate(`localStorage.setItem('theme','${theme}');document.documentElement.dataset.theme='${theme}'`);
    await new Promise(resolve => setTimeout(resolve, 150));
    if (await evaluate("document.documentElement.scrollWidth > innerWidth")) throw new Error(`Horizontal page overflow: ${route} ${width} ${theme}`);
    const shot = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: true });
    await writeFile(`artifacts/ui-check/financial/${route.slice(1)}-${width}-${theme}.png`, Buffer.from(shot.data, "base64"));
    console.log(`PASS ${route} ${width}px ${theme}; no page overflow`);
  }
} finally { ws.close(); }
