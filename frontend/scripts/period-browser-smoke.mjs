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
const permissions = ["DASHBOARD_READ_ALL", "MEMBER_READ", "MEMBER_WRITE", "LEDGER_READ", "LEDGER_POST", "LEDGER_REVERSE", "FISCAL_YEAR_CLOSE", "PERIOD_UNLOCK", "APPROVE", "INVESTMENT_READ", "REPORT_EXPORT", "SETTINGS_WRITE", "PAYMENT_VERIFY", "RECONCILE", "OWN_DATA_READ"];
const pending = [{ id: "unlock-fixture", makerId: "another-maker", actionType: "PERIOD_UNLOCK", payload: { year: Number(today.slice(0, 4))-1, reason: "Correct omitted historical source contribution after independent review." }, memberNumber: null, fullName: null, originalAmount: null, requiredApprovals: 2, approvals: 1 }, { id: "request-fixture", makerId: "another-maker", actionType: "LEDGER_POST", payload: { amount: "125.50", type: "SEED" },
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
    else if (path === "/api/v1/payments") body = {payments:[{id:"payment-fixture",memberId:member.id,memberNumber:member.memberNumber,fullName:member.fullName,amount:"125.50",verifiedAmount:"125.25",purpose:"ADDITION",method:"Bank transfer",reference:"CU-2026-000123",status:"VERIFIED_PENDING_APPROVAL",paidDate:today,effectiveDate:today,proofId:"proof-fixture",reason:null,approvalRequestId:"request-payment",makerId:"other-maker",approvals:0,requiredApprovals:1,duplicate:true,provider:null,checkoutUrl:null,feeAmount:"0.00"}],page:0,hasNext:false};
    else if (path === "/api/v1/payments/methods") body=[{id:"method-fixture",name:"Development bank transfer",instructions:"Fictitious account only.\nAdd the generated reference to the transfer narration.",active:true}];
    else if (path === "/api/v1/payments/own-member") body={memberId:member.id};
    else if (path === "/api/v1/payments/online/config") body={provider:"FAKE",enabled:true,development:true};
    else if (path === "/api/v1/reconciliations/preview") body={equity:"1500.00",principal:"1400.00",income:"100.00",integrityDifference:"0.00",invested:"1000.00",expectedCash:"500.00",sourceFingerprint:"fixture"};
    else if (path === "/api/v1/reconciliations") body={records:[{id:"record-fixture",asAt:today,actualHoldings:"1000.00",actualCash:"490.00",snapshot:{equity:"1500.00",invested:"1000.00",expectedCash:"500.00",integrityDifference:"0.00",holdingsDifference:"0.00",cashDifference:"-10.00"},notes:"Fictitious development reconciliation. Bank fee pending evidence.",makerId:"other-maker",status:"PENDING",approvals:0,requiredApprovals:1}],page:0,hasNext:false};
    else if (path === "/api/v1/portal") body={memberId:member.id,memberNumber:member.memberNumber,fullName:member.fullName,asAt:today,balanceBf:{principal:"1000",income:"50",equity:"1050"},ytd:{principal:"400",income:"50",equity:"450"},total:{principal:"1400",income:"100",equity:"1500"},roePercent:"7.14",equityRatioPercent:"20.00",trend:[{date:today,equity:"1500"}],allocations:[{investmentId:"investment-fixture",institution:"Demonstration Treasury",tenor:"91_DAY",status:"POSTED",purchaseDate:today,maturityDate:today,participation:"1000",costShare:"980",expectedIncome:"75.25",actualIncome:null}]};
    else if (path === "/api/v1/portal/ledger") body={entries,hasNext:false,page:0};
    else if (path === "/api/v1/portal/profile") body={version:0,email:"demo@invalid.example",phone:"+233201234567",nextOfKinName:"Demonstration kin",nextOfKinPhone:"+233209876543",nextOfKinRelationship:"Sibling"};
    else if (path === "/api/v1/ledger") body = { entries, page: 0, hasNext: false };
    else if (path === "/api/v1/ledger/approvals") body = pending;
    else if (/^\/api\/v1\/ledger\/fiscal-years\/\d+\/snapshots$/.test(path)) body={ entries: [{memberNumber: member.memberNumber,fullName:member.fullName,principal:"1400.0000",income:"100.0000",equity:"1500.0000",revision:3,ledgerEquity:"1500.0000",reconciled:true}],hasNext:false,page:0};
    else if (path === "/api/v1/dashboard") body={asAt:today,fiscalYear:Number(today.slice(0,4)),members:[],totals:{fullName:"Total",balanceBf:{principal:"0",income:"0",equity:"0"},ytd:{principal:"0",income:"0",equity:"0"},total:{principal:"0",income:"0",equity:"0"},roePercent:"0",equityRatioPercent:"0"},trend:[],balanceForwardSnapshot:{fiscalYear:Number(today.slice(0,4))-1,state:"REVIEW_REQUIRED",snapshotCount:11,missingMembers:1,mismatchedMembers:2}};
    else if (path === "/api/v1/ledger/fiscal-years") body = [{ year: Number(today.slice(0, 4)), status: "OPEN" }, { year: Number(today.slice(0, 4)) - 1, status: "CLOSED" }];
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
await mkdir("artifacts/ui-check/periods", { recursive: true });
try {
  for (const width of [320, 375, 768, 1024, 1440]) for (const theme of ["light", "dark"]) for (const route of ["/ledger", "/", "/portal"]) {
    await send("Emulation.setDeviceMetricsOverride", { width, height: 1000, deviceScaleFactor: 1, mobile: width < 600 });
    await send("Page.navigate", { url: `http://localhost:5173${route}` });
    await waitFor(`document.querySelector('h1')?.textContent === '${route === '/portal' ? 'My membership' : route === '/' ? 'Overview' : route.slice(1, 2).toUpperCase() + route.slice(2)}'`);
    if (route === "/payments") {
      await waitFor("document.body.textContent.includes('CU-2026-000123')");
      await evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent==='Submit receipt').click()");
      await waitFor("!!document.querySelector('input[type=file]')");
    }
    if (route === "/portal") await waitFor("document.body.textContent.includes('Your contact details')");
    if (route === "/reconciliation") await waitFor("document.body.textContent.includes('Fictitious development reconciliation')");
    if (route === "/reports") {
      await waitFor("!!document.querySelector('select option[value=\"00000000-0000-4000-8000-000000000001\"]')");
      await evaluate(`const select=document.querySelector('select');select.value='${member.id}';select.dispatchEvent(new Event('change',{bubbles:true}));`);
      await waitFor("document.body.textContent.includes('Member statement')");
    }
    if (route === "/ledger") {
      await waitFor("!!document.querySelector('.ledger-entry')");
      await evaluate("[...document.querySelectorAll('button')].find(button=>button.textContent.includes('Add contribution')).click()");
      await waitFor("!!document.querySelector('select option[value=\"00000000-0000-4000-8000-000000000001\"]')");
      await evaluate(`const label=[...document.querySelectorAll('label')].find(l=>l.textContent.startsWith('Fiscal year'));const select=label.querySelector('select');select.value='${Number(today.slice(0,4))-1}';select.dispatchEvent(new Event('change',{bubbles:true}));`);
      await waitFor("document.body.textContent.includes('Reconciled with ledger')");
    }
    if (route === "/approvals") {
      await waitFor("!!document.querySelector('.ledger-entry')");
      await evaluate("[...document.querySelectorAll('button')].find(button=>button.textContent==='Review approval').click()");
      await waitFor("document.body.textContent.includes('Confirm and approve')");
    }
    await evaluate(`localStorage.setItem('theme','${theme}');document.documentElement.dataset.theme='${theme}'`);
    await new Promise(resolve => setTimeout(resolve, 150));
    if (await evaluate("document.documentElement.scrollWidth > innerWidth")) throw new Error(`Horizontal page overflow: ${route} ${width} ${theme}`);
    if (route === "/ledger" && !await evaluate("(()=>{const probe=document.createElement('span');probe.style.backgroundColor='var(--surface)';document.body.append(probe);const expected=getComputedStyle(probe).backgroundColor;probe.remove();return [...document.querySelectorAll('.investment-form-grid textarea,.investment-panel .card-title-row > label select')].every(control=>getComputedStyle(control).backgroundColor===expected&&control.getBoundingClientRect().height>=44)})()")) throw new Error(`Unstyled fiscal control: ${width} ${theme}`);
    const shot = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: true });
    await writeFile(`artifacts/ui-check/periods/${route.slice(1)||'overview'}-${width}-${theme}.png`, Buffer.from(shot.data, "base64"));
    console.log(`PASS ${route} ${width}px ${theme}; no page overflow`);
  }
} finally { ws.close(); }
